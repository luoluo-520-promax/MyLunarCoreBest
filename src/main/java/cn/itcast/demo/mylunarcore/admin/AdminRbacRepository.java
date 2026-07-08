// 后台 RBAC 领域模型与数据访问所在包
package cn.itcast.demo.mylunarcore.admin;

// Spring JDBC 模板，简化 SQL 执行
import org.springframework.jdbc.core.JdbcTemplate;
// 标记为数据访问层组件
import org.springframework.stereotype.Repository;

// 列表类型
import java.util.List;
// 可能为空单个结果的容器
import java.util.Optional;

/**
 * 后台 RBAC 数据访问：用户、权限（经角色聚合）。
 */
@Repository // 注册为 Spring Bean，注入 JdbcTemplate
public class AdminRbacRepository {

    // JDBC 操作模板
    private final JdbcTemplate jdbcTemplate;

    /**
     * 构造器注入 JdbcTemplate。
     */
    public AdminRbacRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 按用户名查询后台用户（最多一条）。
     */
    public Optional<AdminUserRecord> findByUsername(String username) {
        // 查询 admin_user 表
        String sql = "SELECT id, username, password_hash, status FROM admin_user WHERE username = ? LIMIT 1";
        List<AdminUserRecord> list = jdbcTemplate.query(sql, (rs, rowNum) -> new AdminUserRecord(
                rs.getLong("id"),
                rs.getString("username"),
                rs.getString("password_hash"),
                rs.getInt("status")
        ), username);
        // 无行返回 empty，否则取第一条
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    /**
     * 通过用户→角色→权限关联，去重后的权限标识列表。
     */
    public List<String> findPermissionCodesByUserId(long userId) {
        // 三表关联：用户角色、角色权限、权限码
        String sql = """
                SELECT DISTINCT p.perm_code
                FROM admin_permission p
                INNER JOIN admin_role_permission rp ON rp.permission_id = p.id
                INNER JOIN admin_user_role ur ON ur.role_id = rp.role_id
                WHERE ur.user_id = ?
                """;
        return jdbcTemplate.queryForList(sql, String.class, userId);
    }
}
