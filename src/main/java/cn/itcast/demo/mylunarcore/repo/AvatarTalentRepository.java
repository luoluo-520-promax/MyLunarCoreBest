package cn.itcast.demo.mylunarcore.repo;

// 角色天赋进度实体：player_id + avatar_id + talent_id 唯一确定一条天赋等级记录
import cn.itcast.demo.mylunarcore.model.AvatarTalentEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 角色天赋表 {@code avatar_talent} 的 JDBC 仓储。
 * <p>
 * 主键语义为（player_id, avatar_id, talent_id）；升级走 MySQL
 * {@code INSERT ... ON DUPLICATE KEY UPDATE}，并用 {@code GREATEST} 保证等级只升不降。
 */
@Repository
public class AvatarTalentRepository {

    // 热库 JDBC，执行天赋查询与 upsert
    private final JdbcTemplate jdbcTemplate;

    /**
     * @param jdbcTemplate Spring 注入的数据源模板
     */
    public AvatarTalentRepository(JdbcTemplate jdbcTemplate) {
        // 保存模板供各查询方法复用同一连接池
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 列出某玩家某角色的全部天赋行，按 talent_id 升序便于客户端按树节点展示。
     *
     * @param playerId 玩家 ID
     * @param avatarId 角色（avatar）配置 ID
     * @return 天赋实体列表；无数据时为空列表而非 null
     */
    public List<AvatarTalentEntity> listByAvatar(int playerId, int avatarId) {
        // 按玩家+角色过滤，talent_id 排序保证树形 UI 稳定
        String sql = "SELECT * FROM avatar_talent WHERE player_id = ? AND avatar_id = ? ORDER BY talent_id ASC";
        // mapRow 将 ResultSet 列映射为 AvatarTalentEntity
        return jdbcTemplate.query(sql, (rs, rowNum) -> mapRow(rs), playerId, avatarId);
    }

    /**
     * 查询单条天赋；不存在返回 null。
     *
     * @param playerId 玩家 ID
     * @param avatarId 角色 ID
     * @param talentId 天赋节点 ID
     * @return 实体或 null
     */
    public AvatarTalentEntity find(int playerId, int avatarId, int talentId) {
        // LIMIT 1：三元组主键下最多一行
        String sql = "SELECT * FROM avatar_talent WHERE player_id = ? AND avatar_id = ? AND talent_id = ? LIMIT 1";
        List<AvatarTalentEntity> list = jdbcTemplate.query(sql, (rs, rowNum) -> mapRow(rs), playerId, avatarId, talentId);
        // 空列表表示尚未解锁/未写入该天赋
        return list.isEmpty() ? null : list.get(0);
    }

    /**
     * 升级（或首次激活）天赋到 {@code targetLevel}。
     * <ul>
     *   <li>新行：插入 level=targetLevel、activated=1；</li>
     *   <li>已存在：level 取当前与目标的较大值，并强制 activated=1、刷新 updated_at。</li>
     * </ul>
     *
     * @param playerId    玩家 ID
     * @param avatarId    角色 ID
     * @param talentId    天赋节点 ID
     * @param targetLevel 期望达到的等级（不会因并发写回更低等级）
     * @return 写库后重新查询的最新实体
     */
    public AvatarTalentEntity upgrade(int playerId, int avatarId, int talentId, int targetLevel) {
        // ON DUPLICATE KEY：依赖 uk(player_id,avatar_id,talent_id)；GREATEST 防等级回退
        String sql = "INSERT INTO avatar_talent(player_id, avatar_id, talent_id, level, activated) VALUES(?, ?, ?, ?, 1) " +
                "ON DUPLICATE KEY UPDATE level = GREATEST(level, VALUES(level)), activated = 1, updated_at = NOW()";
        jdbcTemplate.update(sql, playerId, avatarId, talentId, targetLevel);
        // 再查一次返回完整时间戳与最终 level，供上层协议回包
        return find(playerId, avatarId, talentId);
    }

    /**
     * 将 {@code avatar_talent} 一行映射为实体；activated 列以 1/0 表示布尔。
     */
    private AvatarTalentEntity mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        AvatarTalentEntity e = new AvatarTalentEntity();
        e.setPlayerId(rs.getInt("player_id")); // 所属玩家
        e.setAvatarId(rs.getInt("avatar_id")); // 所属角色配置 ID
        e.setTalentId(rs.getInt("talent_id")); // 天赋树节点 ID
        e.setLevel(rs.getInt("level")); // 当前天赋等级
        e.setActivated(rs.getInt("activated") == 1); // 1=已激活，其它视为未激活
        e.setCreatedAt(rs.getTimestamp("created_at")); // 首次写入时间
        e.setUpdatedAt(rs.getTimestamp("updated_at")); // 最近升级/激活时间
        return e;
    }
}
