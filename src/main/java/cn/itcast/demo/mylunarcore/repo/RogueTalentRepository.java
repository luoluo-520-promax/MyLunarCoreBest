// 模拟宇宙天赋表数据访问所在包
package cn.itcast.demo.mylunarcore.repo;

// 天赋实体，对应 rogue_talent 表
import cn.itcast.demo.mylunarcore.model.RogueTalentEntity;
// JDBC 模板
import org.springframework.jdbc.core.JdbcTemplate;
// 仓储注解
import org.springframework.stereotype.Repository;

// 查询结果列表
import java.util.List;

/**
 * {@code rogue_talent} 表访问：按玩家列出天赋等级与激活状态。
 */
@Repository // Spring 数据访问 Bean
public class RogueTalentRepository {

    // 执行 SQL 的 JDBC 模板
    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造器注入 JdbcTemplate。
     *
     * @param jdbcTemplate Spring 注入
     */
    public RogueTalentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate; // 保存依赖
    }

    /**
     * 列出玩家全部天赋行，按 talent_id 升序。
     *
     * @param playerId 玩家 id
     * @return 天赋实体列表（可能为空，不会为 null）
     */
    public List<RogueTalentEntity> listByPlayerId(int playerId) {
        String sql = "SELECT player_id, talent_id, level, activated, created_at, updated_at " +
                "FROM rogue_talent WHERE player_id=? ORDER BY talent_id ASC"; // 按天赋 id 排序便于展示
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            RogueTalentEntity e = new RogueTalentEntity(); // 新建实体
            e.setPlayerId(rs.getInt("player_id")); // 玩家 id（联合主键之一）
            e.setTalentId(rs.getInt("talent_id")); // 天赋 id（联合主键之一）
            e.setLevel(rs.getInt("level")); // 当前等级
            e.setActivated(rs.getInt("activated") == 1); // TINYINT 1 表示已激活
            e.setCreatedAt(rs.getTimestamp("created_at")); // 创建时间
            e.setUpdatedAt(rs.getTimestamp("updated_at")); // 更新时间
            return e;
        }, playerId);
    }

    /**
     * 加载单条天赋记录（player_id + talent_id）。
     *
     * @param playerId 玩家 id
     * @param talentId 天赋 id
     * @return 实体；不存在则 null
     */
    public RogueTalentEntity load(int playerId, int talentId) {
        String sql = "SELECT player_id, talent_id, level, activated, created_at, updated_at " +
                "FROM rogue_talent WHERE player_id=? AND talent_id=? LIMIT 1";
        List<RogueTalentEntity> list = jdbcTemplate.query(sql, (rs, rowNum) -> {
            RogueTalentEntity e = new RogueTalentEntity(); // 行映射与 listByPlayerId 一致
            e.setPlayerId(rs.getInt("player_id"));
            e.setTalentId(rs.getInt("talent_id"));
            e.setLevel(rs.getInt("level"));
            e.setActivated(rs.getInt("activated") == 1);
            e.setCreatedAt(rs.getTimestamp("created_at"));
            e.setUpdatedAt(rs.getTimestamp("updated_at"));
            return e;
        }, playerId, talentId);
        return list.isEmpty() ? null : list.get(0); // 无行返回 null
    }

    /**
     * 升级/激活天赋：不允许降级（level 取 GREATEST），但允许从未激活 → 激活。
     *
     * @param playerId       玩家 id
     * @param talentId       天赋 id
     * @param upgradeToLevel 目标等级
     * @return 更新后的行数据（再次 load）
     */
    public RogueTalentEntity upgradeTalent(int playerId, int talentId, int upgradeToLevel) {
        // INSERT ... ON DUPLICATE KEY UPDATE：MySQL upsert，新行 activated=1
        String upsertSql = "INSERT INTO rogue_talent(player_id, talent_id, level, activated) " +
                "VALUES(?, ?, ?, 1) " +
                "ON DUPLICATE KEY UPDATE " +
                "level = GREATEST(level, VALUES(level)), " + // 等级只升不降
                "activated = 1"; // 强制标记为已激活

        jdbcTemplate.update(upsertSql, playerId, talentId, upgradeToLevel); // 执行 upsert
        return load(playerId, talentId); // 读回最新行返回给调用方
    }
}
