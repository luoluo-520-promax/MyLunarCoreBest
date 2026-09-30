package cn.itcast.demo.mylunarcore.guild;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 公会（社团）核心业务服务，负责公会的全生命周期管理：
 * <ul>
 *   <li>创建公会：校验玩家未加入其他公会、名称合法后落库，并让创建者成为会长；</li>
 *   <li>加入/退出公会：维护 guild_member 成员表与 guild 人数冗余字段的一致性；</li>
 *   <li>贡献机制：成员贡献同时累加个人贡献/周贡献，并转化为公会经验（升级途径）；</li>
 *   <li>公会商店：以贡献点作为货币购买道具，支持按自然周（周一为界）限量购买。</li>
 * </ul>
 * 公会战闭环见 {@link GuildWarService}；排期见 {@link GuildRaidScheduler}。
 * 所有数据均持久化到 MySQL，并尽可能在内存结构上提供兜底。
 */
@Service
public class GuildService {

    /** 公会概要信息，用于协议下发的公会摘要。 */
    public record GuildInfo(long guildId, String name, String notice, int level, int exp,
                            int leaderId, int memberCount, int maxMembers) {}

    /** 公会成员信息：role 0=普通成员、2=会长；contribution 为累计贡献，weeklyContrib 为本周贡献。 */
    public record MemberInfo(int playerId, int role, int contribution, int weeklyContrib) {}

    /** 公会商店商品：售价为贡献点（contributionCost），带每周限购次数（weeklyLimit）。 */
    public record ShopProduct(int productId, int itemId, int count, int contributionCost,
                              int weeklyLimit, String name) {}

    /** 公会商店商品配置的 JSON 反序列化载体（忽略未知字段，兼容运营配置增删列）。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ShopProductJson(int productId, int itemId, int count, int contributionCost,
                                  int weeklyLimit, String name) {
        /** 将配置模型转换为业务模型。 */
        ShopProduct toProduct() {
            return new ShopProduct(productId, itemId, count, contributionCost, weeklyLimit, name);
        }
    }

    /** 业务操作结果：success 标记是否成功，retcode 为对客协议错误码（0=成功）。 */
    public record OpResult(boolean success, int retcode) {}

    /** 普通成员角色编号。 */
    private static final int ROLE_MEMBER = 0;
    /** 会长角色编号。 */
    private static final int ROLE_LEADER = 2;
    /** 公会名称最大长度（超出则创建失败）。 */
    private static final int MAX_NAME_LEN = 24;
    /** 公会默认人数上限。 */
    private static final int DEFAULT_MAX_MEMBERS = 30;

    /** JDBC 模板，用于对 guild / guild_member / guild_shop_purchase 等表执行 SQL。 */
    private final JdbcTemplate jdbc;
    /** Jackson 对象映射器，用于读取公会商店 JSON 配置。 */
    private final ObjectMapper objectMapper;
    /** 数据目录（默认 data），公会商店配置文件 GuildShopConfigs.json 所在目录。 */
    private final Path dataDir;
    /** 贡献排行接口，贡献时同步更新排行数据。 */
    private final GuildContributionRank contributionRank;
    /** 公会商店商品目录（productId -> 商品），进程内缓存，支持热加载。 */
    private final Map<Integer, ShopProduct> shopCatalog = new ConcurrentHashMap<>();

    public GuildService(JdbcTemplate jdbc,
                        ObjectMapper objectMapper,
                        GuildContributionRank contributionRank,
                        @Value("${lunarcore.data-dir:data}") String dataDir) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.contributionRank = contributionRank;
        this.dataDir = Path.of(dataDir);
    }

    /** 服务启动时加载公会商店配置。 */
    @PostConstruct
    public void loadShop() {
        reloadShopCatalog();
    }

    /**
     * 从 data/GuildShopConfigs.json 重载公会商店目录。
     * 支持运营热更新：清空旧目录后用最新配置重建；文件缺失/损坏时保持原目录不变。
     */
    public void reloadShopCatalog() {
        Path file = dataDir.resolve("GuildShopConfigs.json");
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            List<ShopProductJson> list = objectMapper.readValue(Files.readString(file), new TypeReference<>() {});
            shopCatalog.clear();
            for (ShopProductJson p : list) {
                shopCatalog.put(p.productId(), p.toProduct());
            }
        } catch (IOException ignored) {
            // 配置缺失时商店为空
        }
    }

    /** 返回当前公会商店全部商品（快照）。 */
    public List<ShopProduct> listShopProducts() {
        return List.copyOf(shopCatalog.values());
    }

    /**
     * 创建公会。创建者自动成为会长（role=2）。
     * 错误码：2=参数非法（名称空/超长）、4=玩家已属于其他公会、5=数据库写入失败。
     */
    @Transactional
    public OpResult create(int leaderId, String name, String notice) {
        if (leaderId <= 0 || name == null || name.isBlank() || name.length() > MAX_NAME_LEN) {
            return new OpResult(false, 2);
        }
        if (findGuildIdByPlayer(leaderId) != null) {
            return new OpResult(false, 4);
        }
        String now = Instant.now().toString();
        String safeNotice = notice == null ? "" : notice;
        KeyHolder keys = new GeneratedKeyHolder();
        try {
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement("""
                        INSERT INTO guild (name, notice, level, exp, leader_id, member_count, max_members, created_at)
                        VALUES (?, ?, 1, 0, ?, 1, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, name.trim());
                ps.setString(2, safeNotice);
                ps.setInt(3, leaderId);
                ps.setInt(4, DEFAULT_MAX_MEMBERS);
                ps.setString(5, now);
                return ps;
            }, keys);
        } catch (Exception e) {
            return new OpResult(false, 5);
        }
        Number key = keys.getKey();
        if (key == null) {
            return new OpResult(false, 5);
        }
        long guildId = key.longValue();
        // 同时写入会长成员记录，保持 member_count 与实际成员表一致
        jdbc.update("""
                INSERT INTO guild_member (guild_id, player_id, role, contribution, weekly_contrib, joined_at)
                VALUES (?, ?, ?, 0, 0, ?)
                """, guildId, leaderId, ROLE_LEADER, now);
        return new OpResult(true, 0);
    }

    /**
     * 玩家加入指定公会。
     * 错误码：2=参数非法、4=玩家已有公会、3=公会不存在、6=公会人数已满。
     */
    @Transactional
    public OpResult join(int playerId, long guildId) {
        if (playerId <= 0 || guildId <= 0) {
            return new OpResult(false, 2);
        }
        if (findGuildIdByPlayer(playerId) != null) {
            return new OpResult(false, 4);
        }
        GuildInfo guild = loadGuild(guildId);
        if (guild == null) {
            return new OpResult(false, 3);
        }
        if (guild.memberCount() >= guild.maxMembers()) {
            return new OpResult(false, 6);
        }
        String now = Instant.now().toString();
        jdbc.update("""
                INSERT INTO guild_member (guild_id, player_id, role, contribution, weekly_contrib, joined_at)
                VALUES (?, ?, ?, 0, 0, ?)
                """, guildId, playerId, ROLE_MEMBER, now);
        jdbc.update("UPDATE guild SET member_count = member_count + 1 WHERE guild_id = ?", guildId);
        return new OpResult(true, 0);
    }

    /**
     * 玩家退出公会。
     * <p>若是会长：仍有其他成员时禁止退出（错误码 7），仅剩自己时公会整体解散（删成员+删公会）。
     * 普通成员直接删除成员记录并回减人数（不小于 0）。
     */
    @Transactional
    public OpResult leave(int playerId) {
        Long guildId = findGuildIdByPlayer(playerId);
        if (guildId == null) {
            return new OpResult(false, 3);
        }
        MemberInfo me = loadMember(guildId, playerId);
        if (me != null && me.role() == ROLE_LEADER) {
            Integer others = jdbc.query("""
                    SELECT player_id FROM guild_member WHERE guild_id = ? AND player_id <> ? LIMIT 1
                    """, rs -> rs.next() ? rs.getInt(1) : null, guildId, playerId);
            if (others != null) {
                return new OpResult(false, 7);
            }
            jdbc.update("DELETE FROM guild_member WHERE guild_id = ?", guildId);
            jdbc.update("DELETE FROM guild WHERE guild_id = ?", guildId);
            return new OpResult(true, 0);
        }
        jdbc.update("DELETE FROM guild_member WHERE guild_id = ? AND player_id = ?", guildId, playerId);
        jdbc.update("UPDATE guild SET member_count = GREATEST(member_count - 1, 0) WHERE guild_id = ?", guildId);
        return new OpResult(true, 0);
    }

    /**
     * 成员贡献：个人贡献 + 周贡献 + 公会经验同步增加，并记录到贡献排行。
     * amount 上限 10000，防止异常超大值破坏经济平衡。
     */
    @Transactional
    public OpResult contribute(int playerId, int amount) {
        if (amount <= 0 || amount > 10_000) {
            return new OpResult(false, 2);
        }
        Long guildId = findGuildIdByPlayer(playerId);
        if (guildId == null) {
            return new OpResult(false, 3);
        }
        jdbc.update("""
                UPDATE guild_member SET contribution = contribution + ?, weekly_contrib = weekly_contrib + ?
                WHERE guild_id = ? AND player_id = ?
                """, amount, amount, guildId, playerId);
        jdbc.update("UPDATE guild SET exp = exp + ? WHERE guild_id = ?", amount, guildId);
        contributionRank.recordContribution(guildId, playerId, amount);
        return new OpResult(true, 0);
    }

    /**
     * 公会商店购买：校验商品存在、玩家有足够贡献点、未达周限购次数；
     * 扣减贡献点并记录购买次数（week_key 以自然周为粒度，ON DUPLICATE KEY 实现累加）。
     */
    @Transactional
    public OpResult buyShop(int playerId, int productId) {
        ShopProduct product = shopCatalog.get(productId);
        if (product == null) {
            return new OpResult(false, 3);
        }
        Long guildId = findGuildIdByPlayer(playerId);
        if (guildId == null) {
            return new OpResult(false, 4);
        }
        MemberInfo me = loadMember(guildId, playerId);
        if (me == null || me.contribution() < product.contributionCost()) {
            return new OpResult(false, 5);
        }
        String weekKey = currentWeekKey();
        int bought = loadBought(guildId, playerId, productId, weekKey);
        if (bought >= product.weeklyLimit()) {
            return new OpResult(false, 6);
        }
        // 带条件扣减：贡献点不足时 update 影响行数为 0，兜底并发下的余额检查
        jdbc.update("""
                UPDATE guild_member SET contribution = contribution - ?
                WHERE guild_id = ? AND player_id = ? AND contribution >= ?
                """, product.contributionCost(), guildId, playerId, product.contributionCost());
        int updated = jdbc.update("""
                INSERT INTO guild_shop_purchase (guild_id, player_id, product_id, week_key, buy_count)
                VALUES (?, ?, ?, ?, 1)
                ON DUPLICATE KEY UPDATE buy_count = buy_count + 1
                """, guildId, playerId, productId, weekKey);
        if (updated <= 0) {
            return new OpResult(false, 5);
        }
        return new OpResult(true, 0);
    }

    /** 查询玩家当前所属公会概要；未加入则返回 null。 */
    public GuildInfo loadGuildForPlayer(int playerId) {
        Long guildId = findGuildIdByPlayer(playerId);
        return guildId == null ? null : loadGuild(guildId);
    }

    /** 按公会 ID 加载公会概要；不存在返回 null。 */
    public GuildInfo loadGuild(long guildId) {
        try {
            return jdbc.query("""
                    SELECT guild_id, name, notice, level, exp, leader_id, member_count, max_members
                    FROM guild WHERE guild_id = ?
                    """, rs -> {
                if (!rs.next()) {
                    return null;
                }
                return new GuildInfo(
                        rs.getLong("guild_id"),
                        rs.getString("name"),
                        rs.getString("notice"),
                        rs.getInt("level"),
                        rs.getInt("exp"),
                        rs.getInt("leader_id"),
                        rs.getInt("member_count"),
                        rs.getInt("max_members"));
            }, guildId);
        } catch (Exception e) {
            return null;
        }
    }

    /** 列出公会全部成员（角色、累计贡献、周贡献），按数据库原始顺序返回。 */
    public List<MemberInfo> listMembers(long guildId) {
        try {
            return jdbc.query("""
                    SELECT player_id, role, contribution, weekly_contrib FROM guild_member WHERE guild_id = ?
                    """, (rs, rowNum) -> new MemberInfo(
                    rs.getInt("player_id"),
                    rs.getInt("role"),
                    rs.getInt("contribution"),
                    rs.getInt("weekly_contrib")), guildId);
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 加载单个公会成员信息；不存在返回 null。 */
    public MemberInfo loadMember(long guildId, int playerId) {
        try {
            return jdbc.query("""
                    SELECT player_id, role, contribution, weekly_contrib FROM guild_member
                    WHERE guild_id = ? AND player_id = ?
                    """, rs -> rs.next()
                    ? new MemberInfo(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4))
                    : null, guildId, playerId);
        } catch (Exception e) {
            return null;
        }
    }

    /** 查询玩家本周对指定商品已购买次数（用于协议回显限购进度）。 */
    public int boughtThisWeek(int playerId, int productId) {
        Long guildId = findGuildIdByPlayer(playerId);
        if (guildId == null) {
            return 0;
        }
        return loadBought(guildId, playerId, productId, currentWeekKey());
    }

    /** 按玩家 ID 查找其所属公会 ID；未加入任何公会返回 null。 */
    public Long findGuildIdByPlayer(int playerId) {
        try {
            return jdbc.query("""
                    SELECT guild_id FROM guild_member WHERE player_id = ?
                    """, rs -> rs.next() ? rs.getLong(1) : null, playerId);
        } catch (Exception e) {
            return null;
        }
    }

    /** 查询某公会成员在指定自然周内购买某商品的次数；查询失败按 0 处理。 */
    private int loadBought(long guildId, int playerId, int productId, String weekKey) {
        try {
            Integer n = jdbc.query("""
                    SELECT buy_count FROM guild_shop_purchase
                    WHERE guild_id = ? AND player_id = ? AND product_id = ? AND week_key = ?
                    """, rs -> rs.next() ? rs.getInt(1) : 0, guildId, playerId, productId, weekKey);
            return n == null ? 0 : n;
        } catch (Exception e) {
            return 0;
        }
    }

    /** 生成当前自然周 key（所在周的周一日期，如 2026-08-10），用于周限购统计。 */
    static String currentWeekKey() {
        LocalDate monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return monday.toString();
    }
}
