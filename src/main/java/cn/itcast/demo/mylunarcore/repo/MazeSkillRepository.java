// 战斗相关数据库访问层所在包：maze_skill 主表
package cn.itcast.demo.mylunarcore.repo;

// Lombok Getter，为 MazeSkill 生成访问器
import lombok.Getter;
// Spring JDBC 模板
import org.springframework.jdbc.core.JdbcTemplate;
// 声明为 Spring 仓储 Bean
import org.springframework.stereotype.Repository;

// JDBC 查询结果集
import java.sql.ResultSet;
// JDBC 访问异常
import java.sql.SQLException;

/**
 * 迷宫技能主表 {@code maze_skill}：按 ID 查询技能类型、触发与秘境修正等静态定义。
 */
@Repository // 迷宫技能主表数据访问 Bean
public class MazeSkillRepository {

    // Spring JDBC 模板，执行 SQL
    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造器注入 JDBC 模板。
     *
     * @param jdbcTemplate Spring 注入
     */
    public MazeSkillRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate; // 保存依赖
    }

    /**
     * 按主键查技能静态定义；不存在或异常返回 null。
     *
     * @param skillId 技能 id
     * @return MazeSkill 或 null
     */
    public MazeSkill findById(int skillId) {
        // 查询技能名称、类型、是否进战、秘境修正等列
        String sql = "SELECT id, name, description, skill_type, trigger_battle, adventure_modifier " +
                "FROM maze_skill WHERE id = ? LIMIT 1";
        try {
            return jdbcTemplate.queryForObject(sql, (rs, rowNum) -> map(rs), skillId); // 单行映射
        } catch (Exception e) {
            return null; // 无行或 SQL 异常时由调用方按 null 处理
        }
    }

    /**
     * 将 ResultSet 当前行映射为 {@link MazeSkill}。
     *
     * @param rs 结果集（光标已在目标行）
     * @return 不可变技能定义
     * @throws SQLException 读列失败
     */
    private MazeSkill map(ResultSet rs) throws SQLException {
        return new MazeSkill(
                rs.getInt("id"),                    // 技能 id
                rs.getString("name"),               // 技能名称
                rs.getString("description"),        // 技能描述
                rs.getInt("skill_type"),            // 技能类型枚举
                rs.getInt("trigger_battle"),        // 是否触发战斗（0/1）
                rs.getInt("adventure_modifier"));   // 秘境/冒险修正系数
    }

    /** 技能静态定义（内存中的只读值对象） */
    @Getter // 生成各字段 getter
    public static class MazeSkill {
        private final int id;                 // 技能 id
        private final String name;            // 名称
        private final String description;     // 描述文本
        private final int skillType;          // 技能类型
        private final int triggerBattle;      // 触发战斗标记
        private final int adventureModifier;  // 冒险修正

        /**
         * 构造不可变技能定义。
         *
         * @param id                技能 id
         * @param name              名称
         * @param description       描述
         * @param skillType         类型
         * @param triggerBattle     是否触发战斗
         * @param adventureModifier   冒险修正
         */
        public MazeSkill(int id, String name, String description, int skillType, int triggerBattle, int adventureModifier) {
            this.id = id;                           // 赋值 id
            this.name = name;                       // 赋值名称
            this.description = description;         // 赋值描述
            this.skillType = skillType;             // 赋值类型
            this.triggerBattle = triggerBattle;     // 赋值触发战斗
            this.adventureModifier = adventureModifier; // 赋值修正
        }
    }
}
