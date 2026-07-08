// 后台 RBAC 领域模型与数据访问所在包
package cn.itcast.demo.mylunarcore.admin;

// 账号被禁用异常
import org.springframework.security.authentication.DisabledException;
// Spring Security 权限接口
import org.springframework.security.core.GrantedAuthority;
// 权限实现：字符串权限码
import org.springframework.security.core.authority.SimpleGrantedAuthority;
// 内置 UserDetails 构建器
import org.springframework.security.core.userdetails.User;
// 登录用户详情接口
import org.springframework.security.core.userdetails.UserDetails;
// 按用户名加载用户的 SPI
import org.springframework.security.core.userdetails.UserDetailsService;
// 用户不存在异常
import org.springframework.security.core.userdetails.UsernameNotFoundException;
// 标记为业务服务 Bean
import org.springframework.stereotype.Service;

// 列表
import java.util.List;

/**
 * 加载后台用户及其通过 RBAC 聚合得到的权限标识（{@link SimpleGrantedAuthority}）。
 */
@Service // Spring Security 自动使用此 Bean 校验登录用户
public class AdminUserDetailsService implements UserDetailsService {

    // RBAC 数据访问
    private final AdminRbacRepository rbacRepository;

    /**
     * 构造器注入仓储。
     */
    public AdminUserDetailsService(AdminRbacRepository rbacRepository) {
        this.rbacRepository = rbacRepository;
    }

    @Override // 登录时 Spring Security 调用此方法加载用户与权限
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        // 查库，不存在则抛 UsernameNotFoundException
        AdminUserRecord user = rbacRepository.findByUsername(username)
                .orElseThrow(() -> new UsernameNotFoundException(username));
        if (user.status() != 1) { // status≠1 视为禁用
            throw new DisabledException("admin user disabled");
        }
        List<String> codes = rbacRepository.findPermissionCodesByUserId(user.id());
        // 权限码转为 GrantedAuthority 列表
        List<GrantedAuthority> authorities = codes.stream()
                .map(c -> (GrantedAuthority) new SimpleGrantedAuthority(c))
                .toList();
        // 构建 Spring Security 用户对象（含密码哈希供比对）
        return User.builder()
                .username(user.username())
                .password(user.passwordHash())
                .authorities(authorities)
                .build();
    }
}
