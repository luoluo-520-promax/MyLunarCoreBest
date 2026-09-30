// game_data 表访问：按键读取 JSON 快照（游戏静态配置/资源）
package cn.itcast.demo.mylunarcore.repo;

// 游戏数据键值实体，对应 game_data 表一行
import cn.itcast.demo.mylunarcore.model.GameDataEntity;

// Spring JDBC 模板，简化 query 调用
import org.springframework.jdbc.core.JdbcTemplate;

// Spring 数据访问 Bean 注解
import org.springframework.stereotype.Repository;

// 查询结果列表，JdbcTemplate.query 返回 List
import java.util.List;

/**
 * L2：游戏资源与配置类数据的 MySQL 访问（毫秒级 I/O），保证可维护与可靠存储。
 */
@Repository // 声明为仓储 Bean，供 Service 注入
public class GameDataRepository {

    private final JdbcTemplate jdbcTemplate; // JDBC 执行模板（数据源由 Spring 注入）

    /**
     * 构造器注入 JdbcTemplate。
     *
     * @param jdbcTemplate Spring 注入的数据源访问模板
     */
    public GameDataRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate; // 保存注入的 jdbcTemplate 引用
    }

    /**
     * 按业务键查询一条 game_data 记录。
     *
     * @param dataKey 业务唯一键（如配置名）
     * @return 实体或 null（键不存在时）
     */
    public GameDataEntity findByKey(String dataKey) {
        // 参数化查询防 SQL 注入；LIMIT 1 最多返回一行
        String sql = "SELECT data_key, payload_json, updated_at FROM game_data WHERE data_key = ? LIMIT 1";
        List<GameDataEntity> list = jdbcTemplate.query(sql, (rs, rowNum) -> {
            GameDataEntity e = new GameDataEntity(); // 新建实体
            e.setDataKey(rs.getString("data_key"));       // 业务键
            e.setPayloadJson(rs.getString("payload_json")); // JSON 正文
            e.setUpdatedAt(rs.getTimestamp("updated_at"));  // 最后更新时间
            return e; // 返回映射结果
        }, dataKey); // 绑定 dataKey 参数
        return list.isEmpty() ? null : list.get(0); // 无行返回 null，否则取首行
    }

    /**
     * 插入或更新一条 game_data 记录。
     *
     * @param dataKey      业务唯一键
     * @param payloadJson  JSON 正文
     * @return 受影响行数
     */
    public int upsert(String dataKey, String payloadJson) {
        String sql = """
                INSERT INTO game_data (data_key, payload_json)
                VALUES (?, ?)
                ON DUPLICATE KEY UPDATE payload_json = VALUES(payload_json)
                """;
        return jdbcTemplate.update(sql, dataKey, payloadJson);
    }
}
