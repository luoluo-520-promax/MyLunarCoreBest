// 战斗相关数据库访问层所在包：maze_buff 元数据
package cn.itcast.demo.mylunarcore.repo;

// Spring JDBC 模板
import org.springframework.jdbc.core.JdbcTemplate;
// 仓储注解
import org.springframework.stereotype.Repository;

// JDBC 结果集
import java.sql.ResultSet;
// SQL 异常
import java.sql.SQLException;
// 线程安全空 Map 工厂
import java.util.Collections;
// 哈希 Map 实现
import java.util.HashMap;
// Map 接口
import java.util.Map;

/**
 * 迷宫 Buff 元数据：查询 Buff 最大层数等，带简单内存缓存减轻重复读库。
 */
@Repository // 迷宫 Buff 元数据访问 Bean
public class MazeBuffRepository {

    // Spring JDBC 模板
    private final JdbcTemplate jdbcTemplate;
    // buffId → maxStack 本地缓存（Collections.synchronizedMap 保证多线程安全）
    private final Map<Integer, Integer> maxStackCache = Collections.synchronizedMap(new HashMap<Integer, Integer>());

    /**
     * 构造器注入 JDBC 模板。
     *
     * @param jdbcTemplate Spring 注入
     */
    public MazeBuffRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate; // 保存依赖
    }

    /**
     * 查询 Buff 最大叠层；无效 id 或查库失败时返回 1。
     *
     * @param buffId Buff 配置 id
     * @return 最大可叠层数，至少为 1
     */
    public int findMaxStack(int buffId) {
        if (buffId <= 0) {
            return 1; // 非法 id 直接返回默认最大 1 层
        }
        Integer cached = maxStackCache.get(buffId); // 先查进程内缓存
        if (cached != null) {
            return cached; // 命中缓存，避免重复 SQL
        }

        // 未命中缓存则查 maze_buff 表
        String sql = "SELECT id, max_stack FROM maze_buff WHERE id = ? LIMIT 1";
        try {
            Integer v = jdbcTemplate.queryForObject(sql, (rs, rowNum) -> map(rs), buffId); // 读 max_stack 列
            maxStackCache.put(buffId, v); // 写入缓存供后续 O(1) 命中
            return v; // 返回数据库配置值
        } catch (Exception e) {
            return 1; // 无配置或异常时默认最多 1 层
        }
    }

    /**
     * 从结果集读取 max_stack 列。
     *
     * @param rs 结果集
     * @return max_stack 整数值
     * @throws SQLException 读列失败
     */
    private Integer map(ResultSet rs) throws SQLException {
        return rs.getInt("max_stack"); // 映射为 Integer（自动装箱）
    }
}
