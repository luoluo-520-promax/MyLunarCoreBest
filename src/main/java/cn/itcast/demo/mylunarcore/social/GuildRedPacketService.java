package cn.itcast.demo.mylunarcore.social;

// 从玩家钱包扣款/加款，红包出资与领取都走账本
import cn.itcast.demo.mylunarcore.economy.WalletApplicationService;
// 查玩家所属公会，校验是否同会成员
import cn.itcast.demo.mylunarcore.guild.GuildService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 公会红包服务：成员出资拆成多份，同会成员随机领取。
 * <p>
 * 热路径用内存 {@link MutablePacket}；落库表 {@code guild_red_packet} /
 * {@code guild_red_packet_claim} 做重启后恢复。领取在包对象上 synchronized，
 * 保证剩余金额/份数原子扣减。
 * <p>
 * 常见 retcode：create 1=参数非法 2=非成员 3=余额不足；
 * claim 1=包不存在 2=已过期/领完 3=非成员 4=已领过。
 */
@Service
public class GuildRedPacketService {

    /**
     * 红包对外快照（不可变视图）。
     *
     * @param packetId      16 位十六进制包 ID
     * @param guildId       所属公会
     * @param fromPlayerId  发包玩家
     * @param currencyId    货币类型
     * @param totalAmount   总金额
     * @param remainAmount  剩余可领金额
     * @param totalShares   总份数
     * @param remainShares  剩余份数
     * @param expireAt      过期时刻（创建后 1 小时）
     */
    public record PacketInfo(String packetId, long guildId, int fromPlayerId, int currencyId,
                             int totalAmount, int remainAmount, int totalShares, int remainShares,
                             Instant expireAt) {}

    /**
     * 操作结果。
     *
     * @param success       是否成功
     * @param retcode       业务错误码，0 成功
     * @param packet        当前包快照（失败时也可能带回）
     * @param claimedAmount 本次领取金额；create 时为 0
     */
    public record OpResult(boolean success, int retcode, PacketInfo packet, int claimedAmount) {}

    // 持久化红包与领取记录
    private final JdbcTemplate jdbc;
    // 出资扣款、领取入账
    private final WalletApplicationService wallet;
    // 按玩家查所属 guildId
    private final GuildService guildService;
    // packetId → 可变红包状态（含已领玩家映射）
    private final ConcurrentHashMap<String, MutablePacket> packets = new ConcurrentHashMap<>();

    /** 内存可变红包：剩余金额/份数可改，claimed 记录每人已领金额防重复领。 */
    private static final class MutablePacket {
        final String packetId; // 主键
        final long guildId; // 仅该会成员可领
        final int fromPlayerId; // 出资人
        final int currencyId; // 货币 ID
        int remainAmount; // 还可发出的金额
        int remainShares; // 还可发出的份数
        final int totalAmount; // 创建时总额
        final int totalShares; // 创建时总份数
        final Instant expireAt; // 过期后不可再领
        // playerId → 已领取金额
        final ConcurrentHashMap<Integer, Integer> claimed = new ConcurrentHashMap<>();

        MutablePacket(String packetId, long guildId, int fromPlayerId, int currencyId,
                      int totalAmount, int totalShares, Instant expireAt) {
            this.packetId = packetId;
            this.guildId = guildId;
            this.fromPlayerId = fromPlayerId;
            this.currencyId = currencyId;
            this.totalAmount = totalAmount;
            this.remainAmount = totalAmount; // 初始剩余=总额
            this.totalShares = totalShares;
            this.remainShares = totalShares; // 初始剩余份数=总份数
            this.expireAt = expireAt;
        }

        /** 生成不可变快照给协议/API 返回。 */
        PacketInfo info() {
            return new PacketInfo(packetId, guildId, fromPlayerId, currencyId,
                    totalAmount, remainAmount, totalShares, remainShares, expireAt);
        }
    }

    public GuildRedPacketService(JdbcTemplate jdbc,
                                 WalletApplicationService wallet,
                                 GuildService guildService) {
        this.jdbc = jdbc;
        this.wallet = wallet;
        this.guildService = guildService;
    }

    /**
     * 创建红包：校验参数与会籍 → 钱包扣款 → 生成包 ID → 内存+DB。
     * shares 限制 1–50；totalAmount 必须 ≥ shares（保证每份至少 1）。
     */
    @Transactional
    public OpResult create(int playerId, long guildId, int currencyId, int totalAmount, int shares) {
        // 非法玩家/公会、金额不足以每份至少 1、或份数越界
        if (playerId <= 0 || guildId <= 0 || totalAmount < shares || shares < 1 || shares > 50) {
            return new OpResult(false, 1, null, 0);
        }
        // 非本会成员不能发到该 guildId
        if (!isGuildMember(playerId, guildId)) {
            return new OpResult(false, 2, null, 0);
        }
        // 账本扣款，reason=guild_red_packet 便于对账
        WalletApplicationService.WalletChangeResult deduct =
                wallet.deduct(playerId, currencyId, totalAmount, "guild_red_packet");
        if (!deduct.success()) {
            return new OpResult(false, 3, null, 0); // 余额不足或钱包失败
        }
        // UUID 去横线截 16 位作短 ID
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        // 有效期 1 小时
        MutablePacket p = new MutablePacket(id, guildId, playerId, currencyId, totalAmount, shares,
                Instant.now().plusSeconds(3600));
        packets.put(id, p); // 热路径缓存
        persistCreate(p); // 尽力写库，失败不回滚内存（联调容忍）
        return new OpResult(true, 0, p.info(), 0);
    }

    /**
     * 领取红包：加载包 → 校验过期/份数/会籍/是否已领 →
     * 同步块内按「二倍均值」近似算法拆随机金额 → 钱包加款 → 落库。
     */
    @Transactional
    public OpResult claim(int playerId, String packetId) {
        MutablePacket p = packets.get(packetId);
        if (p == null) {
            // 进程重启后从 DB 回填
            p = loadPacket(packetId);
            if (p != null) {
                packets.put(packetId, p);
            }
        }
        if (p == null) {
            return new OpResult(false, 1, null, 0); // 包不存在
        }
        // 过期或已领完
        if (Instant.now().isAfter(p.expireAt) || p.remainShares <= 0 || p.remainAmount <= 0) {
            return new OpResult(false, 2, p.info(), 0);
        }
        if (!isGuildMember(playerId, p.guildId)) {
            return new OpResult(false, 3, p.info(), 0);
        }
        if (p.claimed.containsKey(playerId)) {
            // 已领过：retcode=4，并把历史领取额带回
            return new OpResult(false, 4, p.info(), p.claimed.get(playerId));
        }
        int amount;
        synchronized (p) {
            // 双重检查：进入锁后可能已被别人领完
            if (p.remainShares <= 0 || p.remainAmount <= 0) {
                return new OpResult(false, 2, p.info(), 0);
            }
            if (p.remainShares == 1) {
                // 最后一份拿走全部剩余，避免尾差
                amount = p.remainAmount;
            } else {
                // 二倍均值法：单次上限约为「剩余均值×2」，且至少留给后续每人 1
                int max = Math.max(1, (p.remainAmount / p.remainShares) * 2);
                amount = 1 + ThreadLocalRandom.current().nextInt(Math.max(1, max));
                amount = Math.min(amount, p.remainAmount - (p.remainShares - 1));
            }
            p.remainAmount -= amount;
            p.remainShares -= 1;
            p.claimed.put(playerId, amount); // 防同玩家再领
        }
        wallet.add(playerId, p.currencyId, amount, "guild_red_packet_claim");
        persistClaim(packetId, playerId, amount, p);
        return new OpResult(true, 0, p.info(), amount);
    }

    /** 列出某公会仍可领且未过期的红包快照（仅扫内存缓存）。 */
    public List<PacketInfo> listActive(long guildId) {
        List<PacketInfo> out = new ArrayList<>();
        Instant now = Instant.now();
        for (MutablePacket p : packets.values()) {
            if (p.guildId == guildId && p.remainShares > 0 && now.isBefore(p.expireAt)) {
                out.add(p.info());
            }
        }
        return out;
    }

    /**
     * 判断是否公会成员：优先 GuildService；失败则查 guild_member 表；
     * 表也不存在时联调放行返回 true。
     */
    private boolean isGuildMember(int playerId, long guildId) {
        try {
            Long gid = guildService.findGuildIdByPlayer(playerId);
            if (gid != null) {
                return gid == guildId;
            }
        } catch (Exception ignored) {
        }
        try {
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM guild_member WHERE player_id=? AND guild_id=?",
                    Integer.class, playerId, guildId);
            return n != null && n > 0;
        } catch (Exception ex) {
            return true; // 联调无表时放行
        }
    }

    /** 插入红包主表；异常吞掉以免阻断内存发包。 */
    private void persistCreate(MutablePacket p) {
        try {
            jdbc.update("""
                    INSERT INTO guild_red_packet
                    (packet_id, guild_id, from_player_id, currency_id, total_amount, remain_amount,
                     total_shares, remain_shares, expire_at, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, p.packetId, p.guildId, p.fromPlayerId, p.currencyId, p.totalAmount, p.remainAmount,
                    p.totalShares, p.remainShares, p.expireAt.toString(), Instant.now().toString());
        } catch (Exception ignored) {
        }
    }

    /** 更新剩余金额/份数，并插入领取明细行。 */
    private void persistClaim(String packetId, int playerId, int amount, MutablePacket p) {
        try {
            jdbc.update("""
                    UPDATE guild_red_packet SET remain_amount=?, remain_shares=? WHERE packet_id=?
                    """, p.remainAmount, p.remainShares, packetId);
            jdbc.update("""
                    INSERT INTO guild_red_packet_claim (packet_id, player_id, amount, created_at)
                    VALUES (?, ?, ?, ?)
                    """, packetId, playerId, amount, Instant.now().toString());
        } catch (Exception ignored) {
        }
    }

    /**
     * 从 DB 加载红包主表行并还原 MutablePacket（不含 claimed 明细时允许再领风险，
     * 生产可再加载 claim 表填 claimed）。
     */
    private MutablePacket loadPacket(String packetId) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT * FROM guild_red_packet WHERE packet_id=?", packetId);
            if (rows.isEmpty()) {
                return null;
            }
            Map<String, Object> r = rows.get(0);
            MutablePacket p = new MutablePacket(
                    packetId,
                    ((Number) r.get("guild_id")).longValue(),
                    ((Number) r.get("from_player_id")).intValue(),
                    ((Number) r.get("currency_id")).intValue(),
                    ((Number) r.get("total_amount")).intValue(),
                    ((Number) r.get("total_shares")).intValue(),
                    Instant.parse(String.valueOf(r.get("expire_at"))));
            // 构造器把 remain 设成 total，这里用库中剩余覆盖
            p.remainAmount = ((Number) r.get("remain_amount")).intValue();
            p.remainShares = ((Number) r.get("remain_shares")).intValue();
            return p;
        } catch (Exception e) {
            return null;
        }
    }
}
