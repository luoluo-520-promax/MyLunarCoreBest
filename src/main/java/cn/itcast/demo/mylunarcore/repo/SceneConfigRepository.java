// 场景平面/楼层配置仓储所在包
package cn.itcast.demo.mylunarcore.repo;

// Spring JDBC 模板
import lombok.Getter;
import org.springframework.jdbc.core.JdbcTemplate;
// 仓储 Bean 注解
import org.springframework.stereotype.Repository;

// ResultSet 行映射
import java.sql.ResultSet;
// SQL 异常
import java.sql.SQLException;

/**
 * 场景平面/楼层布局：读取 {@code scene_config} 中 groups JSON，用于实例化场景实体。
 */
@Repository // 数据访问层 Bean
public class SceneConfigRepository {

    // 执行 SQL 查询的 JDBC 模板
    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造器注入 JdbcTemplate。
     *
     * @param jdbcTemplate Spring 注入
     */
    public SceneConfigRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate; // 保存依赖
    }

    /**
     * 按位面 id 与楼层 id 查询场景分组 JSON。
     *
     * @param planeId 位面/世界 id
     * @param floorId 楼层 id
     * @return 场景行；无数据返回 null
     */
    public SceneRow findGroups(int planeId, int floorId) {
        // plane_id + floor_id 联合定位一行 scene_config
        String sql = "SELECT plane_id, floor_id, groups FROM scene_config WHERE plane_id = ? AND floor_id = ? LIMIT 1";
        try {
            return jdbcTemplate.queryForObject(sql, this::map, planeId, floorId); // 单行映射
        } catch (Exception e) {
            return null; // 无行或查询异常时视为未配置
        }
    }

    /**
     * JDBC RowMapper：将一行转为 {@link SceneRow}。
     *
     * @param rs     结果集
     * @param rowNum 行号
     * @return 场景配置行
     * @throws SQLException 读列失败
     */
    private SceneRow map(ResultSet rs, int rowNum) throws SQLException {
        return new SceneRow(
                rs.getInt("plane_id"),   // 位面 id
                rs.getInt("floor_id"),   // 楼层 id
                rs.getString("groups")   // 实体分组 JSON 字符串
        );
    }

    /**
     * 场景配置查询结果：不可变值对象。
     */
    @Getter
    public static class SceneRow {
        /**
         * @return 位面 id
         */ // 返回位面 id
        private final int planeId;       // 位面 id
        /**
         * @return 楼层 id
         */ // 返回楼层 id
        private final int floorId;       // 楼层 id
        /**
         * @return 分组 JSON 字符串
         */ // 返回 groups JSON
        private final String groupsJson; // 实体分组 JSON 字符串

        /**
         * 构造一行查询结果。
         *
         * @param planeId    位面 id
         * @param floorId    楼层 id
         * @param groupsJson 分组 JSON
         */
        public SceneRow(int planeId, int floorId, String groupsJson) {
            this.planeId = planeId;           // 赋值位面 id
            this.floorId = floorId;           // 赋值楼层 id
            this.groupsJson = groupsJson;     // 赋值 JSON
        }

    }
}
