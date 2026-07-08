// 怪物静态配置仓储所在包
package cn.itcast.demo.mylunarcore.repo;

// Lombok Getter，为内部类生成访问器
import lombok.Getter;
// Spring JDBC 模板
import org.springframework.jdbc.core.JdbcTemplate;
// 仓储注解
import org.springframework.stereotype.Repository;

// JDBC 结果集
import java.sql.ResultSet;
// SQL 异常
import java.sql.SQLException;

/**
 * 场景怪物静态配置表 {@code monster_config}：属性与模型 ID，供战斗与场景生成使用。
 */
@Repository // 注册为数据访问 Bean
public class MonsterConfigRepository {

    private final JdbcTemplate jdbcTemplate; // JDBC 模板，执行查询

    /**
     * 构造器注入 JdbcTemplate。
     *
     * @param jdbcTemplate Spring 注入
     */
    public MonsterConfigRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate; // 保存引用
    }

    /**
     * 按怪物模板 id 查询配置行。
     *
     * @param monsterId 怪物配置 id
     * @return 配置行；不存在或异常时 null
     */
    public MonsterRow findById(int monsterId) {
        // 查询怪物静态属性列（name/attack 等未映射到 MonsterRow，按需扩展）
        String sql = "SELECT id, name, level, hp, attack, defense, speed, model_id, buffs " +
                "FROM monster_config WHERE id = ? LIMIT 1";
        try {
            return jdbcTemplate.queryForObject(sql, this::map, monsterId); // 单行映射
        } catch (Exception e) {
            return null; // 无此怪物配置或查询失败
        }
    }

    /**
     * ResultSet 行 → {@link MonsterRow}（仅映射战斗所需字段子集）。
     *
     * @param rs     结果集
     * @param rowNum 行号
     * @return 怪物配置行
     * @throws SQLException 读列失败
     */
    private MonsterRow map(ResultSet rs, int rowNum) throws SQLException {
        return new MonsterRow(
                rs.getInt("id"),      // 怪物模板 id
                rs.getInt("level"),   // 默认等级
                rs.getInt("hp"),      // 基础 HP
                rs.getString("buffs") // 初始 Buff JSON 字符串
        );
    }

    /** 怪物配置只读行（战斗侧使用的精简视图） */
    @Getter // Lombok 生成 getId/getLevel/getHp/getBuffsJson
    public static class MonsterRow {
        private final int id;         // 怪物模板 id
        private final int level;      // 等级
        private final int hp;         // 生命值
        private final String buffsJson; // 初始 Buff JSON

        /**
         * 构造不可变怪物配置行。
         *
         * @param id        怪物 id
         * @param level     等级
         * @param hp        HP
         * @param buffsJson Buff JSON
         */
        public MonsterRow(int id, int level, int hp, String buffsJson) {
            this.id = id;               // 赋值 id
            this.level = level;         // 赋值等级
            this.hp = hp;               // 赋值 HP
            this.buffsJson = buffsJson; // 赋值 Buff JSON
        }
    }
}
