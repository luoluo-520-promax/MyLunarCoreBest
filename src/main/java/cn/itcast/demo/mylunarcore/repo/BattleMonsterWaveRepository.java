// 战斗相关数据库访问层所在包：battle_monster_wave 波次配置
package cn.itcast.demo.mylunarcore.repo;

// Lombok Getter
import lombok.Getter;
// Spring JDBC 模板
import org.springframework.jdbc.core.JdbcTemplate;
// 结果集行映射接口
import org.springframework.jdbc.core.RowMapper;
// 仓储注解
import org.springframework.stereotype.Repository;

// JDBC 结果集
import java.sql.ResultSet;
// SQL 异常
import java.sql.SQLException;
// 列表
import java.util.List;

/**
 * 战斗关卡波次配置表 {@code battle_monster_wave}：按关卡 ID 加载波次顺序与怪物 JSON，供 {@link cn.itcast.demo.mylunarcore.battle.BattleMonsterWaveSimpleFactory} 构建 {@link cn.itcast.demo.mylunarcore.battle.WaveRuntime}。
 */
@Repository // 战斗波次配置数据访问 Bean
public class BattleMonsterWaveRepository {

    // Spring JDBC 模板
    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造器注入 JDBC 模板。
     *
     * @param jdbcTemplate Spring 注入
     */
    public BattleMonsterWaveRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate; // 保存依赖
    }

    /**
     * 按关卡 ID 查询所有波次，按 wave_order 升序。
     *
     * @param battleStageId 战斗关卡/阶段 id
     * @return 波次配置列表（可能为空）
     */
    public List<WaveConfig> loadWavesByStageId(int battleStageId) {
        // 同一 battle_stage_id 下按 wave_order 排序
        String sql = "SELECT id, battle_stage_id, wave_order, monsters, custom_level " +
                "FROM battle_monster_wave " +
                "WHERE battle_stage_id = ? " +
                "ORDER BY wave_order ASC";
        return jdbcTemplate.query(sql, new WaveConfigRowMapper(), battleStageId); // 多行映射
    }

    /** 单条波次配置行（不可变值对象） */
    @Getter // 生成字段 getter
    public static class WaveConfig {
        private final int id;              // 配置表波次行主键
        private final int battleStageId;   // 所属关卡 ID
        private final int waveOrder;       // 波次顺序（升序排列）
        private final String monstersJson; // monsters 列 JSON（怪物 ID 列表等）
        private final int customLevel;     // 自定义等级覆盖（0 表示使用默认）

        /**
         * 构造一行不可变波次配置。
         *
         * @param id             行 id
         * @param battleStageId  关卡 id
         * @param waveOrder      波次顺序
         * @param monstersJson   怪物 JSON
         * @param customLevel    自定义等级
         */
        public WaveConfig(int id, int battleStageId, int waveOrder, String monstersJson, int customLevel) {
            this.id = id;                       // 赋值主键
            this.battleStageId = battleStageId; // 赋值关卡 id
            this.waveOrder = waveOrder;         // 赋值波次顺序
            this.monstersJson = monstersJson;   // 赋值怪物 JSON
            this.customLevel = customLevel;     // 赋值自定义等级
        }
    }

    /** 将 ResultSet 一行映射为 {@link WaveConfig} 的 RowMapper */
    private static class WaveConfigRowMapper implements RowMapper<WaveConfig> {
        /**
         * 映射单行波次配置。
         *
         * @param rs     结果集
         * @param rowNum 行号
         * @return WaveConfig 实例
         * @throws SQLException 读列失败
         */
        @Override
        public WaveConfig mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new WaveConfig(
                    rs.getInt("id"),              // 行主键
                    rs.getInt("battle_stage_id"), // 关卡 id
                    rs.getInt("wave_order"),      // 波次顺序
                    rs.getString("monsters"),     // 怪物 JSON 字符串
                    rs.getInt("custom_level")     // 自定义等级
            );
        }
    }
}
