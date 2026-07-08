// 战斗相关数据库访问层所在包：maze_skill_action 行为序列表
package cn.itcast.demo.mylunarcore.repo;

// Lombok Getter
import lombok.Getter;
// Spring JDBC 模板
import org.springframework.jdbc.core.JdbcTemplate;
// 行映射接口
import org.springframework.jdbc.core.RowMapper;
// 仓储注解
import org.springframework.stereotype.Repository;

// JDBC 结果集
import java.sql.ResultSet;
// SQL 异常
import java.sql.SQLException;
// 空列表常量
import java.util.Collections;
// 列表类型
import java.util.List;

/**
 * 迷宫技能行为序列：按技能 ID 加载有序 {@code action} 行，驱动战斗内技能效果链。
 */
@Repository // 迷宫技能行为表数据访问 Bean
public class MazeSkillActionRepository {

    // Spring JDBC 模板
    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造器注入 JDBC 模板。
     *
     * @param jdbcTemplate Spring 注入
     */
    public MazeSkillActionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate; // 保存依赖
    }

    /**
     * 按技能 ID 查询行为链，按 action_order 升序；异常时返回空列表。
     *
     * @param skillId 所属技能 id
     * @return 有序行为行列表，不会为 null
     */
    public List<MazeSkillActionRow> findBySkillId(int skillId) {
        // 同一 skill_id 下按 action_order 排序，保证执行顺序与配置一致
        String sql = "SELECT id, skill_id, action_type, action_category, action_order, params " +
                "FROM maze_skill_action " +
                "WHERE skill_id = ? " +
                "ORDER BY action_order ASC";
        try {
            return jdbcTemplate.query(sql, new MazeSkillActionRowMapper(), skillId); // 多行映射
        } catch (Exception e) {
            return Collections.emptyList(); // 查库失败时返回空链，避免 NPE
        }
    }

    /** 将 ResultSet 一行映射为 {@link MazeSkillActionRow} 的 RowMapper 实现 */
    private static class MazeSkillActionRowMapper implements RowMapper<MazeSkillActionRow> {
        /**
         * Spring JDBC 回调：映射单行。
         *
         * @param rs     结果集
         * @param rowNum 行号（从 0 起）
         * @return 行为配置行
         * @throws SQLException 读列失败
         */
        @Override
        public MazeSkillActionRow mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new MazeSkillActionRow(
                    rs.getInt("id"),              // 行为行主键
                    rs.getInt("skill_id"),        // 所属技能 id
                    rs.getInt("action_type"),     // 行为类型（伤害/Buff 等）
                    rs.getInt("action_category"), // 行为分类（预留扩展）
                    rs.getInt("action_order"),    // 同技能内执行顺序
                    rs.getString("params")        // params 列 JSON 原始字符串
            );
        }
    }

    /** 单条技能行为配置（不可变） */
    @Getter // 生成 getter
    public static class MazeSkillActionRow {
        private final int id;              // maze_skill_action 主键
        private final int skillId;         // 所属技能 ID
        private final int actionType;      // 行为类型（扣血、加 Buff 等）
        private final int actionCategory;  // 行为分类（预留）
        private final int actionOrder;     // 同技能内执行顺序
        private final String paramsJson;   // params JSON 原始字符串

        /**
         * 构造一行技能行为配置。
         *
         * @param id             行 id
         * @param skillId        技能 id
         * @param actionType     行为类型
         * @param actionCategory 行为分类
         * @param actionOrder    执行顺序
         * @param paramsJson     参数 JSON
         */
        public MazeSkillActionRow(int id, int skillId, int actionType, int actionCategory, int actionOrder, String paramsJson) {
            this.id = id;                       // 赋值主键
            this.skillId = skillId;             // 赋值技能 id
            this.actionType = actionType;       // 赋值行为类型
            this.actionCategory = actionCategory; // 赋值分类
            this.actionOrder = actionOrder;     // 赋值顺序
            this.paramsJson = paramsJson;       // 赋值 JSON
        }
    }
}
