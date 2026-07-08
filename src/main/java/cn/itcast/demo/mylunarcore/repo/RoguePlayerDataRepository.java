// 模拟宇宙玩家扩展数据持久化所在包
package cn.itcast.demo.mylunarcore.repo;

// Rogue 玩家扩展数据实体，对应 rogue_player_data 表
import cn.itcast.demo.mylunarcore.model.RoguePlayerDataEntity;
// Spring JDBC 模板
import org.springframework.jdbc.core.JdbcTemplate;
// 仓储层 Bean 注解
import org.springframework.stereotype.Repository;

// 查询结果列表
import java.util.List;

/**
 * {@code rogue_player_data} 表访问：按玩家加载或插入默认行，供 Rogue 协议读写持久化数据。
 */
@Repository // 注册为 Spring 数据访问 Bean
public class RoguePlayerDataRepository {

    private final JdbcTemplate jdbcTemplate; // 执行 SQL 的 JDBC 模板

    /**
     * 构造器注入 JdbcTemplate。
     *
     * @param jdbcTemplate Spring 注入
     */
    public RoguePlayerDataRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate; // 保存依赖
    }

    /**
     * 加载玩家 Rogue 扩展数据；若不存在则先插入默认行再查询。
     *
     * @param playerId 玩家 id
     * @return 扩展数据实体；异常情况下可能为 null
     */
    public RoguePlayerDataEntity loadOrCreate(int playerId) {
        ensureRow(playerId); // 保证表中有该玩家的行（幂等插入）
        // 查询玩家 Rogue 扩展字段全列
        String sql = "SELECT player_id, talents, unlocked_miracles, selected_path, completed_runs, highest_floor, total_score, created_at, updated_at " +
                "FROM rogue_player_data WHERE player_id=? LIMIT 1";
        List<RoguePlayerDataEntity> list = jdbcTemplate.query(sql, (rs, rowNum) -> {
            RoguePlayerDataEntity e = new RoguePlayerDataEntity(); // 新建实体
            e.setPlayerId(rs.getInt("player_id")); // 玩家 id（主键）
            e.setTalentsJson(rs.getString("talents")); // 天赋树 JSON 快照
            e.setUnlockedMiraclesJson(rs.getString("unlocked_miracles")); // 已解锁奇物 JSON
            int sp = rs.getInt("selected_path"); // 命途 id，列可能为 NULL
            e.setSelectedPath(rs.wasNull() ? null : sp); // 未选命途则为 null
            e.setCompletedRuns(rs.getInt("completed_runs")); // 累计通关次数
            e.setHighestFloor(rs.getInt("highest_floor")); // 历史最高层
            e.setTotalScore(rs.getLong("total_score")); // 累计总分
            e.setCreatedAt(rs.getTimestamp("created_at")); // 创建时间
            e.setUpdatedAt(rs.getTimestamp("updated_at")); // 更新时间
            return e; // 返回映射结果
        }, playerId); // 绑定 playerId
        return list.isEmpty() ? null : list.get(0); // 理论上 ensureRow 后必有一行
    }

    /**
     * 若玩家尚无扩展数据行，则插入默认值（幂等 upsert）。
     *
     * @param playerId 玩家 id
     */
    public void ensureRow(int playerId) {
        // ON DUPLICATE KEY UPDATE player_id=player_id：已存在则不改动任何列
        String sql = "INSERT INTO rogue_player_data(player_id, talents, unlocked_miracles, selected_path, completed_runs, highest_floor, total_score) " +
                "VALUES(?, NULL, NULL, NULL, 0, 0, 0) " +
                "ON DUPLICATE KEY UPDATE player_id=player_id";
        jdbcTemplate.update(sql, playerId); // 执行 upsert
    }

    /**
     * 更新玩家选择的命途/路径 id。
     *
     * @param playerId 玩家 id
     * @param pathId   命途 id
     * @return JDBC 受影响行数
     */
    public int updateSelectedPath(int playerId, int pathId) {
        ensureRow(playerId); // 确保行存在再更新
        String sql = "UPDATE rogue_player_data SET selected_path=? WHERE player_id=? LIMIT 1";
        return jdbcTemplate.update(sql, pathId, playerId); // 绑定 pathId 与 playerId
    }

    /**
     * 更新天赋树 JSON 快照。
     *
     * @param playerId    玩家 id
     * @param talentsJson 天赋 JSON 字符串
     * @return JDBC 受影响行数
     */
    public int updateTalentsJson(int playerId, String talentsJson) {
        ensureRow(playerId); // 确保行存在
        String sql = "UPDATE rogue_player_data SET talents=? WHERE player_id=? LIMIT 1";
        return jdbcTemplate.update(sql, talentsJson, playerId);
    }

    /**
     * 更新已解锁奇物列表 JSON。
     *
     * @param playerId              玩家 id
     * @param unlockedMiraclesJson  奇物 JSON 字符串
     * @return JDBC 受影响行数
     */
    public int updateUnlockedMiraclesJson(int playerId, String unlockedMiraclesJson) {
        ensureRow(playerId); // 确保行存在
        String sql = "UPDATE rogue_player_data SET unlocked_miracles=? WHERE player_id=? LIMIT 1";
        return jdbcTemplate.update(sql, unlockedMiraclesJson, playerId);
    }

    /**
     * 一局 Rogue 结束后累加通关次数、最高层与总分。
     *
     * @param playerId    玩家 id
     * @param finalFloor  本局到达层数
     * @param addScore    本局得分增量
     * @param completed   是否算通关（true 则 completed_runs +1）
     * @return JDBC 受影响行数
     */
    public int applyRunEndStats(int playerId, int finalFloor, long addScore, boolean completed) {
        ensureRow(playerId); // 确保行存在
        int addRuns = completed ? 1 : 0; // 仅通关时 completed_runs 加 1
        // GREATEST 保证最高层只增不减；分数与次数用 Math.max 防止负增量
        String sql = "UPDATE rogue_player_data SET " +
                "completed_runs = completed_runs + ?, " +
                "highest_floor = GREATEST(highest_floor, ?), " +
                "total_score = total_score + ? " +
                "WHERE player_id=? LIMIT 1";
        return jdbcTemplate.update(sql, Math.max(0, addRuns), Math.max(0, finalFloor), Math.max(0L, addScore), playerId);
    }
}
