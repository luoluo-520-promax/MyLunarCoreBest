package cn.itcast.demo.mylunarcore.repo;

import cn.itcast.demo.mylunarcore.model.AvatarEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.List;

/**
 * 角色实例仓储：负责 avatar 表与 player 表中昵称/等级相关字段的读写。
 * <p>
 * 这里既包含角色创建、角色装备皮肤更新，也包含玩家昵称与角色/玩家成长进度的回写，
 * 因为这些字段在业务上通常会被“角色成长、换装、改名”等同一批操作一起触发。
 */
@Repository
public class AvatarRepository {

    /** JDBC 执行入口，所有 SQL 都通过它绑定参数并执行。 */
    private final JdbcTemplate jdbcTemplate;

    /**
     * 通过构造器注入数据库模板。
     *
     * @param jdbcTemplate Spring 注入的 JDBC 模板
     */
    public AvatarRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 按玩家 id 与角色模板 id 查询角色实例。
     * <p>
     * SQL 的含义是：一个玩家在同一个 avatar_id 下只能有一条实例记录，
     * 因而这里用 LIMIT 1 直接取唯一行，找不到则返回 null。
     *
     * @param playerId 玩家 id
     * @param avatarId 角色模板 id
     * @return 匹配的角色实体；不存在时返回 null
     */
    public AvatarEntity findAvatar(int playerId, int avatarId) {
        String sql = "SELECT * FROM avatar WHERE player_id = ? AND avatar_id = ? LIMIT 1";
        List<AvatarEntity> list = jdbcTemplate.query(sql, (rs, rowNum) -> mapRow(rs), playerId, avatarId);
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 创建角色实例，默认不带皮肤。
     * <p>
     * 这个重载只是为了给调用方提供一个简化入口，内部统一转到三参数版本。
     *
     * @param playerId 玩家 id
     * @param avatarId 角色模板 id
     * @return 新插入的主键 id；失败时返回 0
     */
    public long insertAvatar(int playerId, int avatarId) {
        return insertAvatar(playerId, avatarId, 0);
    }

    /**
     * 创建角色实例，并写入默认等级、经验、突破、命座、锁定状态和已装备皮肤。
     * <p>
     * SQL 中的固定值含义：
     * <ul>
     *   <li>level=1：新角色从 1 级开始；</li>
     *   <li>exp=0：初始经验为 0；</li>
     *   <li>promotion=0、rank=0：不做任何突破/命座；</li>
     *   <li>locked=0：默认不锁定；</li>
     *   <li>equipped_skin_id：传入的皮肤 id，若小于 0 则钳制为 0。</li>
     * </ul>
     *
     * @param playerId 玩家 id
     * @param avatarId 角色模板 id
     * @param equippedSkinId 已装备皮肤 id；小于 0 时会写入 0
     * @return 新插入行的主键 id；写入失败或未回填主键时返回 0
     */
    public long insertAvatar(int playerId, int avatarId, int equippedSkinId) {
        String sql = "INSERT INTO avatar(player_id, avatar_id, level, exp, promotion, rank, locked, equipped_skin_id) "
                + "VALUES(?, ?, 1, 0, 0, 0, 0, ?)";
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, new String[]{"id"});
            ps.setInt(1, playerId);
            ps.setInt(2, avatarId);
            ps.setInt(3, Math.max(0, equippedSkinId));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 0 : key.longValue();
    }

    /**
     * 更新角色当前穿戴的皮肤。
     * <p>
     * 该字段用于角色换装后立即回写数据库，避免重连时皮肤状态丢失。
     *
     * @param playerId 玩家 id
     * @param avatarId 角色模板 id
     * @param equippedSkinId 新的皮肤 id
     * @return 受影响行数
     */
    public int updateEquippedSkin(int playerId, int avatarId, int equippedSkinId) {
        String sql = "UPDATE avatar SET equipped_skin_id = ?, updated_at = NOW() "
                + "WHERE player_id = ? AND avatar_id = ?";
        return jdbcTemplate.update(sql, Math.max(0, equippedSkinId), playerId, avatarId);
    }

    /**
     * 更新玩家昵称。
     * <p>
     * 这里直接更新 player 表而不是 avatar 表，因为昵称属于玩家公共身份信息，
     * 不跟随某个角色实例变化。
     *
     * @param playerId 玩家 id
     * @param nickname 新昵称
     * @return 受影响行数
     */
    public int updateNickname(int playerId, String nickname) {
        String sql = "UPDATE player SET nickname = ?, updated_at = NOW() WHERE uid = ?";
        return jdbcTemplate.update(sql, nickname, playerId);
    }

    /**
     * 判断玩家是否已经设置过昵称。
     * <p>
     * 业务上可用于创角后强制改名、首次进入游戏时跳转取名流程等。
     *
     * @param playerId 玩家 id
     * @return true 表示昵称非空；false 表示没有昵称或昵称为空白
     */
    public boolean hasNickname(int playerId) {
        String sql = "SELECT nickname FROM player WHERE uid = ? LIMIT 1";
        String nickname = jdbcTemplate.query(sql, rs -> rs.next() ? rs.getString("nickname") : null, playerId);
        return nickname != null && !nickname.isBlank();
    }

    /**
     * 回写角色的成长进度：等级、经验、突破。
     * <p>
     * 这通常在角色升级、突破、经验结算后调用。
     *
     * @param playerId 玩家 id
     * @param avatarId 角色模板 id
     * @param level 新等级
     * @param exp 新经验
     * @param promotion 新突破阶段
     * @return 受影响行数
     */
    public int updateAvatarProgress(int playerId, int avatarId, int level, long exp, int promotion) {
        String sql = "UPDATE avatar SET level = ?, exp = ?, promotion = ?, updated_at = NOW() WHERE player_id = ? AND avatar_id = ?";
        return jdbcTemplate.update(sql, level, exp, promotion, playerId, avatarId);
    }

    /**
     * 回写命座/星魂层数（rank，上限由业务侧钳制）。
     */
    public int updateRank(int playerId, int avatarId, int rank) {
        String sql = "UPDATE avatar SET rank = ?, updated_at = NOW() WHERE player_id = ? AND avatar_id = ?";
        return jdbcTemplate.update(sql, Math.max(0, rank), playerId, avatarId);
    }

    /**
     * 回写玩家主等级与经验。
     * <p>
     * 与角色成长区分开来：这是玩家账号维度的成长数据，不属于单个角色。
     *
     * @param playerId 玩家 id
     * @param level 新等级
     * @param exp 新经验
     * @return 受影响行数
     */
    public int updatePlayerProgress(int playerId, int level, long exp) {
        String sql = "UPDATE player SET level = ?, exp = ?, updated_at = NOW() WHERE uid = ?";
        return jdbcTemplate.update(sql, level, exp, playerId);
    }

    /**
     * 将 avatar 表的一行映射为 {@link AvatarEntity}。
     * <p>
     * 这里会把 locked 列从数据库中的 0/1 转成 Java boolean，
     * 并尽量安全读取 equipped_skin_id，避免某些旧表结构缺列导致异常。
     */
    private AvatarEntity mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        AvatarEntity a = new AvatarEntity();
        a.setId(rs.getLong("id"));
        a.setPlayerId(rs.getInt("player_id"));
        a.setAvatarId(rs.getInt("avatar_id"));
        a.setLevel(rs.getInt("level"));
        a.setExp(rs.getLong("exp"));
        a.setPromotion(rs.getInt("promotion"));
        a.setRank(rs.getInt("rank"));
        a.setLocked(rs.getInt("locked") != 0);
        a.setEquippedSkinId(readEquippedSkinId(rs));
        a.setCreatedAt(rs.getTimestamp("created_at"));
        a.setUpdatedAt(rs.getTimestamp("updated_at"));
        return a;
    }

    /**
     * 读取 equipped_skin_id 列。
     * <p>
     * 某些历史库可能没有这个列，因此这里做了容错：读不到时回退 0，
     * 保证老数据不会因为列差异而导致整行查询失败。
     */
    private static int readEquippedSkinId(java.sql.ResultSet rs) throws java.sql.SQLException {
        try {
            return rs.getInt("equipped_skin_id");
        } catch (java.sql.SQLException e) {
            return 0;
        }
    }
}
