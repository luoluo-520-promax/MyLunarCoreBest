// NPC 静态配置仓储所在包
package cn.itcast.demo.mylunarcore.repo;

// Lombok Getter
import lombok.Getter;
// Spring JDBC 模板
import org.springframework.jdbc.core.JdbcTemplate;
// 仓储层 Bean
import org.springframework.stereotype.Repository;

// 结果集行读取
import java.sql.ResultSet;
// SQL 异常
import java.sql.SQLException;

/**
 * NPC 静态配置：对话与 Rogue 事件关联等，表 {@code npc_config}。
 */
@Repository // 注册为数据访问 Bean
public class NpcConfigRepository {

    private final JdbcTemplate jdbcTemplate; // 执行 SQL 的 JDBC 模板

    /**
     * 构造器注入 JdbcTemplate。
     *
     * @param jdbcTemplate Spring 注入
     */
    public NpcConfigRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate; // 保存依赖
    }

    /**
     * 按 NPC id 查询配置行。
     *
     * @param npcId NPC 配置 id
     * @return 配置行；不存在返回 null
     */
    public NpcRow findById(int npcId) {
        // 查询对话 id 与 Rogue 事件关联 id
        String sql = "SELECT id, dialogue_id, rogue_event_id " +
                "FROM npc_config WHERE id = ? LIMIT 1"; // 参数化查询防注入
        try {
            return jdbcTemplate.queryForObject(sql, this::map, npcId); // 期望单行
        } catch (Exception e) {
            return null; // 无数据或查询失败
        }
    }

    /**
     * 将 JDBC 行映射为 {@link NpcRow}。
     *
     * @param rs     结果集
     * @param rowNum 行号
     * @return NPC 配置行
     * @throws SQLException 读列失败
     */
    private NpcRow map(ResultSet rs, int rowNum) throws SQLException {
        return new NpcRow(
                rs.getInt("id"),              // NPC 配置 id
                rs.getInt("dialogue_id"),      // 对话树/对话配置 id
                rs.getInt("rogue_event_id")    // 关联 Rogue 事件 id
        );
    }

    /**
     * NPC 配置查询结果（不可变值对象）。
     */
    @Getter // 生成 getter
    public static class NpcRow {
        private final int id;           // NPC id
        private final int dialogueId;   // 对话配置 id
        private final int rogueEventId; // Rogue 事件 id

        /**
         * 构造查询结果行。
         *
         * @param id           NPC id
         * @param dialogueId   对话 id
         * @param rogueEventId Rogue 事件 id
         */
        public NpcRow(int id, int dialogueId, int rogueEventId) {
            this.id = id;                       // 赋值 NPC id
            this.dialogueId = dialogueId;       // 赋值对话 id
            this.rogueEventId = rogueEventId;   // 赋值事件 id
        }
    }
}
