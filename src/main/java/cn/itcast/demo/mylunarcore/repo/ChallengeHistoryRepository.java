// 挑战玩法数据仓储所在包：challenge_history 表
package cn.itcast.demo.mylunarcore.repo;

// 挑战历史领域实体
import cn.itcast.demo.mylunarcore.model.ChallengeHistoryEntity;
// Spring JDBC 模板
import org.springframework.jdbc.core.JdbcTemplate;
// 数据访问层组件
import org.springframework.stereotype.Repository;

// 列表接口
import java.util.List;
// Optional 容器，表示可能无记录
import java.util.Optional;

/**
 * 挑战历史表访问：按玩家与关卡查询、插入或更新最佳成绩与领奖状态。
 */
@Repository // challenge_history 表 JDBC 访问 Bean
public class ChallengeHistoryRepository {

    // Spring JDBC 模板（构造函数注入）
    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造器注入 JDBC 模板。
     *
     * @param jdbcTemplate Spring 注入
     */
    public ChallengeHistoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate; // 保存引用
    }

    /**
     * 按玩家与关卡 ID 查询一条历史最佳记录。
     *
     * @param playerId    玩家 id
     * @param challengeId 关卡 id
     * @return Optional 包装的实体；无记录则为 empty
     */
    public Optional<ChallengeHistoryEntity> findByPlayerAndChallenge(int playerId, int challengeId) {
        // 联合主键或唯一索引：player_id + challenge_id
        String sql = "SELECT id, player_id, challenge_id, group_id, stars, score, taken_reward, created_at, updated_at " +
                "FROM challenge_history WHERE player_id=? AND challenge_id=? LIMIT 1";
        List<ChallengeHistoryEntity> list = jdbcTemplate.query(sql, (rs, rowNum) -> {
            ChallengeHistoryEntity e = new ChallengeHistoryEntity(); // 新建实体
            e.setId(rs.getLong("id")); // 行主键
            e.setPlayerId(rs.getInt("player_id")); // 玩家 id
            e.setChallengeId(rs.getInt("challenge_id")); // 关卡 id
            e.setGroupId(rs.getInt("group_id")); // 所属挑战组 id
            e.setStars(rs.getInt("stars")); // 星级掩码（最佳成绩）
            e.setScore(rs.getInt("score")); // 最佳分数
            e.setTakenReward(rs.getInt("taken_reward")); // 已领奖励标记
            e.setCreatedAt(rs.getTimestamp("created_at")); // 创建时间
            e.setUpdatedAt(rs.getTimestamp("updated_at")); // 更新时间
            return e;
        }, playerId, challengeId); // 绑定两个查询参数
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0)); // 无行则 empty
    }

    /**
     * 插入或合并更新玩家在某关卡的最佳星级与分数。
     * 使用 MySQL ON DUPLICATE KEY UPDATE，stars/score 取 GREATEST 保留更好成绩。
     *
     * @param playerId    玩家 id
     * @param challengeId 关卡 id
     * @param groupId     挑战组 id
     * @param starsMask   星级位掩码
     * @param score       本次分数
     */
    public void upsertBestResult(int playerId, int challengeId, int groupId, int starsMask, int score) {
        String sql = "INSERT INTO challenge_history(player_id, challenge_id, group_id, stars, score, taken_reward) " +
                "VALUES(?, ?, ?, ?, ?, 0) " + // 新行 taken_reward 初始 0
                "ON DUPLICATE KEY UPDATE " +
                "group_id=VALUES(group_id), " + // 更新组 id
                "stars=GREATEST(stars, VALUES(stars)), " + // 保留更高星级
                "score=GREATEST(score, VALUES(score))"; // 保留更高分数
        jdbcTemplate.update(sql, playerId, challengeId, groupId, starsMask, score);
    }

    /**
     * 统计满足筛选条件的历史条数（用于分页 total）。
     *
     * @param playerId    玩家 id（必填）
     * @param groupId     可选组 id 过滤；null 或 ≤0 忽略
     * @param challengeId 可选关卡 id 过滤；null 或 ≤0 忽略
     * @return 匹配行数
     */
    public int countHistory(int playerId, Integer groupId, Integer challengeId) {
        StringBuilder sb = new StringBuilder("SELECT COUNT(1) FROM challenge_history WHERE player_id=?"); // 基础 WHERE
        if (groupId != null && groupId > 0) {
            sb.append(" AND group_id=").append(groupId.intValue()); // 追加组过滤（整型拼接，非用户输入）
        }
        if (challengeId != null && challengeId > 0) {
            sb.append(" AND challenge_id=").append(challengeId.intValue()); // 追加关卡过滤
        }
        return jdbcTemplate.queryForObject(sb.toString(), Integer.class, playerId); // 仅 playerId 为绑定参数
    }

    /**
     * 分页列出挑战历史记录，按 updated_at、id 降序。
     *
     * @param playerId    玩家 id
     * @param groupId     可选组过滤
     * @param challengeId 可选关卡过滤
     * @param offset      SQL OFFSET
     * @param limit       每页条数
     * @return 历史实体列表
     */
    public List<ChallengeHistoryEntity> listHistory(int playerId,
                                                   Integer groupId,
                                                   Integer challengeId,
                                                   int offset,
                                                   int limit) {
        StringBuilder sb = new StringBuilder(
                "SELECT id, player_id, challenge_id, group_id, stars, score, taken_reward, created_at, updated_at " +
                        "FROM challenge_history WHERE player_id=?"
        ); // 动态拼接可选过滤条件
        if (groupId != null && groupId > 0) {
            sb.append(" AND group_id=").append(groupId.intValue());
        }
        if (challengeId != null && challengeId > 0) {
            sb.append(" AND challenge_id=").append(challengeId.intValue());
        }
        sb.append(" ORDER BY updated_at DESC, id DESC LIMIT ? OFFSET ?"); // 分页与排序
        return jdbcTemplate.query(sb.toString(), (rs, rowNum) -> {
            ChallengeHistoryEntity e = new ChallengeHistoryEntity(); // 与 find 方法相同的行映射
            e.setId(rs.getLong("id"));
            e.setPlayerId(rs.getInt("player_id"));
            e.setChallengeId(rs.getInt("challenge_id"));
            e.setGroupId(rs.getInt("group_id"));
            e.setStars(rs.getInt("stars"));
            e.setScore(rs.getInt("score"));
            e.setTakenReward(rs.getInt("taken_reward"));
            e.setCreatedAt(rs.getTimestamp("created_at"));
            e.setUpdatedAt(rs.getTimestamp("updated_at"));
            return e;
        }, playerId, limit, offset); // playerId + 分页参数
    }

    /**
     * 列出玩家在某挑战组内的全部历史记录（不分页）。
     *
     * @param playerId 玩家 id
     * @param groupId  挑战组 id
     * @return 该组内所有关卡历史
     */
    public List<ChallengeHistoryEntity> listAllInGroup(int playerId, int groupId) {
        String sql = "SELECT id, player_id, challenge_id, group_id, stars, score, taken_reward, created_at, updated_at " +
                "FROM challenge_history WHERE player_id=? AND group_id=?";
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            ChallengeHistoryEntity e = new ChallengeHistoryEntity(); // 行映射逻辑与上文一致
            e.setId(rs.getLong("id"));
            e.setPlayerId(rs.getInt("player_id"));
            e.setChallengeId(rs.getInt("challenge_id"));
            e.setGroupId(rs.getInt("group_id"));
            e.setStars(rs.getInt("stars"));
            e.setScore(rs.getInt("score"));
            e.setTakenReward(rs.getInt("taken_reward"));
            e.setCreatedAt(rs.getTimestamp("created_at"));
            e.setUpdatedAt(rs.getTimestamp("updated_at"));
            return e;
        }, playerId, groupId);
    }
}
