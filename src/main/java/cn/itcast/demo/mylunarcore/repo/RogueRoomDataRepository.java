// 模拟宇宙房间配置持久化所在包（L2 数据库层）
package cn.itcast.demo.mylunarcore.repo;

// 房间静态数据行模型
import cn.itcast.demo.mylunarcore.model.RogueRoomData;
// Spring JDBC 模板
import org.springframework.jdbc.core.JdbcTemplate;
// 数据访问层 Bean
import org.springframework.stereotype.Repository;

// 查询结果列表
import java.util.List;

/**
 * L2：房间配置持久化（MySQL）；与 {@link RogueRoomCache} 的 L1 配合实现延迟加载。
 */
@Repository // 注册为 Spring 仓储 Bean
public class RogueRoomDataRepository {

    // 执行 SQL 的 JDBC 模板
    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造器注入 JdbcTemplate。
     *
     * @param jdbcTemplate Spring 注入
     */
    public RogueRoomDataRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate; // 保存依赖
    }

    /**
     * 按房间 id 查询单条房间配置。
     *
     * @param roomId 房间主键
     * @return 房间数据；不存在返回 null
     */
    public RogueRoomData findByRoomId(int roomId) {
        // 参数化查询，防止 SQL 注入
        String sql = "SELECT room_id, room_type, pos_x, pos_y FROM rogue_room WHERE room_id = ? LIMIT 1";
        List<RogueRoomData> list = jdbcTemplate.query(sql, (rs, rowNum) ->
                new RogueRoomData(
                        rs.getInt("room_id"),   // 房间 id
                        rs.getInt("room_type"), // 房间类型（战斗/事件/商店等）
                        rs.getInt("pos_x"),     // 地图 X 坐标
                        rs.getInt("pos_y")      // 地图 Y 坐标
                ), roomId); // 绑定 roomId 参数
        return list.isEmpty() ? null : list.get(0); // 无行则 null，供 L1 缓存决定是否用默认值
    }
}
