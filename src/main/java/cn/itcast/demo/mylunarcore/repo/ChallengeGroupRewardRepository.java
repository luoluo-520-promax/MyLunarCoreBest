// 挑战玩法数据仓储所在包：challenge_group_reward 表
package cn.itcast.demo.mylunarcore.repo;

// 挑战组奖励领域实体
import cn.itcast.demo.mylunarcore.model.ChallengeGroupRewardEntity;
// Spring JDBC 模板
import org.springframework.jdbc.core.JdbcTemplate;
// 数据访问层组件
import org.springframework.stereotype.Repository;

// 列表接口
import java.util.List;
// Optional 容器（可能无记录）
import java.util.Optional;

/**
 * 挑战组奖励表访问：确保行存在、查询与更新已领星级。
 */
@Repository // challenge_group_reward 表 JDBC 访问 Bean
public class ChallengeGroupRewardRepository {

    // Spring JDBC 模板（构造函数注入）
    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造器注入 JDBC 模板。
     *
     * @param jdbcTemplate Spring 注入
     */
    public ChallengeGroupRewardRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate; // 保存引用
    }

    /**
     * 确保玩家在某挑战组存在进度行（不存在则插入 taken_stars=0）。
     *
     * @param playerId 玩家 id
     * @param groupId  挑战组 id
     */
    public void ensureRow(int playerId, int groupId) {
        // ON DUPLICATE KEY UPDATE 幂等：已存在则不修改
        String sql = "INSERT INTO challenge_group_reward(player_id, group_id, taken_stars) " +
                "VALUES(?, ?, 0) ON DUPLICATE KEY UPDATE player_id=player_id";
        jdbcTemplate.update(sql, playerId, groupId);
    }

    /**
     * 查询指定玩家在某组的领奖进度（必要时先 ensureRow）。
     *
     * @param playerId 玩家 id
     * @param groupId  挑战组 id
     * @return Optional 包装的实体
     */
    public Optional<ChallengeGroupRewardEntity> find(int playerId, int groupId) {
        ensureRow(playerId, groupId); // 保证至少有一行默认数据
        String sql = "SELECT id, player_id, group_id, taken_stars, created_at, updated_at " +
                "FROM challenge_group_reward WHERE player_id=? AND group_id=? LIMIT 1";
        List<ChallengeGroupRewardEntity> list = jdbcTemplate.query(sql, (rs, rowNum) -> {
            ChallengeGroupRewardEntity e = new ChallengeGroupRewardEntity(); // 新建实体
            e.setId(rs.getLong("id")); // 行主键
            e.setPlayerId(rs.getInt("player_id")); // 玩家 id
            e.setGroupId(rs.getInt("group_id")); // 挑战组 id
            e.setTakenStars(rs.getInt("taken_stars")); // 已领取星级位掩码
            e.setCreatedAt(rs.getTimestamp("created_at")); // 创建时间
            e.setUpdatedAt(rs.getTimestamp("updated_at")); // 更新时间
            return e;
        }, playerId, groupId);
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0)); // ensureRow 后通常非空
    }

    /**
     * 更新已领取星级掩码；返回 JDBC 受影响行数。
     *
     * @param playerId       玩家 id
     * @param groupId        挑战组 id
     * @param takenStarsMask 新的已领星级掩码
     * @return 更新行数（0 或 1）
     */
    public int updateTakenStars(int playerId, int groupId, int takenStarsMask) {
        String sql = "UPDATE challenge_group_reward SET taken_stars=? WHERE player_id=? AND group_id=? LIMIT 1";
        return jdbcTemplate.update(sql, takenStarsMask, playerId, groupId); // 按联合条件更新
    }
}
