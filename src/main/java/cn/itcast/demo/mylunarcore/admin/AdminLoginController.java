// 后台管理 HTTP 接口所在包
package cn.itcast.demo.mylunarcore.admin;

// HTTP 请求对象
import jakarta.servlet.http.HttpServletRequest;
// HTTP 响应对象
import jakarta.servlet.http.HttpServletResponse;
// Spring 响应实体包装
import org.springframework.http.ResponseEntity;
// 认证管理器
import org.springframework.security.authentication.AuthenticationManager;
// 用户名密码认证令牌
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
// 认证成功后的主体
import org.springframework.security.core.Authentication;
// 安全上下文（当前登录用户）
import org.springframework.security.core.context.SecurityContext;
// 安全上下文持有者（线程局部）
import org.springframework.security.core.context.SecurityContextHolder;
// 登出时清理上下文
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
// 将 SecurityContext 存入 HttpSession
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
// POST 映射
import org.springframework.web.bind.annotation.PostMapping;
// JSON 请求体绑定
import org.springframework.web.bind.annotation.RequestBody;
// 类级别路径前缀
import org.springframework.web.bind.annotation.RequestMapping;
// REST 控制器
import org.springframework.web.bind.annotation.RestController;

// 键值对 Map
import java.util.Map;

/**
 * 后台登录/登出；登录成功后权限写入 Session，供后续带 Cookie 的请求使用。
 */
@RestController // 返回值直接作为 JSON 响应体
@RequestMapping("/api/admin") // 本类接口前缀
public class AdminLoginController {

    // 执行用户名密码认证
    private final AuthenticationManager authenticationManager;

    /**
     * 构造器注入认证管理器。
     */
    public AdminLoginController(AuthenticationManager authenticationManager) {
        this.authenticationManager = authenticationManager;
    }

    @PostMapping("/login") // POST /api/admin/login
    public ResponseEntity<Map<String, Object>> login(
            @RequestBody LoginRequest req, // JSON：username、password
            HttpServletRequest request,
            HttpServletResponse response) {
        // 构造待认证的令牌
        UsernamePasswordAuthenticationToken token =
                new UsernamePasswordAuthenticationToken(req.username(), req.password());
        Authentication auth = authenticationManager.authenticate(token); // 校验密码与加载权限
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(auth);
        SecurityContextHolder.setContext(context); // 当前线程绑定登录态
        new HttpSessionSecurityContextRepository().saveContext(context, request, response); // 写入 Session 供后续请求
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/logout") // POST /api/admin/logout
    public ResponseEntity<Map<String, Boolean>> logout(HttpServletRequest request, HttpServletResponse response) {
        SecurityContextLogoutHandler handler = new SecurityContextLogoutHandler();
        handler.logout(request, response, null); // 使 Session 失效并清理上下文
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** 登录请求体：用户名与密码 */
    public record LoginRequest( // 登录请求体记录
            String username, // 登录用户名
            String password // 登录密码
    ) {
        // record 自动生成构造器、getter（username()、password()）
    }
}
