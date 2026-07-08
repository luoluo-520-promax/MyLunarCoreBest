// 召唤物配置仓储所在包
package cn.itcast.demo.mylunarcore.repo;

// Spring JDBC 访问模板
import lombok.Getter;
import org.springframework.jdbc.core.JdbcTemplate;
// 声明为 Spring 仓储 Bean
import org.springframework.stereotype.Repository;

// JDBC 结果集
import java.sql.ResultSet;
// SQL 异常
import java.sql.SQLException;

/**
 * 召唤物单位配置：持续回合、属性与可否被选中，表 {@code summon_unit_config}。
 */
@Repository // Spring 数据访问 Bean
public class SummonUnitConfigRepository {

    private final JdbcTemplate jdbcTemplate; // 执行 SQL 的模板

    /**
     * 构造器注入 JdbcTemplate。
     *
     * @param jdbcTemplate Spring 注入
     */
    public SummonUnitConfigRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate; // 保存依赖
    }

    /**
     * 按召唤物配置 id 查询单行。
     *
     * @param summonConfigId 配置主键
     * @return 配置行；无则 null
     */
    public SummonUnitRow findById(int summonConfigId) {
        // 查询 id、持续回合、HP、可否被选中等静态字段
        String sql = "SELECT id, duration, hp, attack, can_be_targeted " +
                "FROM summon_unit_config WHERE id = ? LIMIT 1";
        try {
            return jdbcTemplate.queryForObject(sql, this::map, summonConfigId); // 期望恰好一行
        } catch (Exception e) {
            return null; // 无行或异常时返回 null，由调用方兜底
        }
    }

    /**
     * 将 ResultSet 一行映射为 {@link SummonUnitRow}。
     *
     * @param rs     结果集
     * @param rowNum 行号（未使用）
     * @return 不可变配置行
     * @throws SQLException 读列失败
     */
    private SummonUnitRow map(ResultSet rs, int rowNum) throws SQLException {
        Integer hp = (Integer) rs.getObject("hp"); // HP 列可能为 NULL
        return new SummonUnitRow(
                rs.getInt("id"),       // 配置 id
                rs.getInt("duration"), // 持续回合数
                hp == null ? 0 : hp    // NULL 时默认 HP 为 0
        );
    }

    /**
     * 召唤物配置快照（只读值对象，当前仅暴露 id/duration/hp）。
     */
    @Getter
    public static class SummonUnitRow {
        /**
         * @return 召唤物配置 id
         */ // 返回配置 id
        private final int summonConfigId; // 配置 id，对应表主键
        /**
         * @return 持续回合数
         */ // 返回持续回合
        private final int duration;       // 召唤物在场持续回合数
        /**
         * @return 基础生命值
         */ // 返回 HP
        private final int hp;             // 基础生命值

        /**
         * 构造不可变配置行。
         *
         * @param summonConfigId 配置 id
         * @param duration       持续回合
         * @param hp             基础 HP
         */
        public SummonUnitRow(int summonConfigId, int duration, int hp) {
            this.summonConfigId = summonConfigId; // 赋值配置 id
            this.duration = duration;             // 赋值持续回合
            this.hp = hp;                         // 赋值 HP
        }

    }
}
