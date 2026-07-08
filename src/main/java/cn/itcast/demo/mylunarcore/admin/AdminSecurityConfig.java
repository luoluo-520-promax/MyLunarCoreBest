// 后台管理端 Spring Security 配置所在包
package cn.itcast.demo.mylunarcore.admin;

// 声明 Spring 容器中的 Bean
import org.springframework.context.annotation.Bean;
// 声明配置类
import org.springframework.context.annotation.Configuration;
// HTTP 状态码枚举
import org.springframework.http.HttpStatus;
// 认证管理器：执行用户名密码校验
import org.springframework.security.authentication.AuthenticationManager;
// 从 Spring 容器获取 AuthenticationManager 的配置入口
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
// 启用方法级安全注解（如 @PreAuthorize）
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
// HTTP 安全规则构建器
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
// 启用 Web 安全
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
// 关闭/抽象化部分 HTTP 配置的快捷类
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
// Session 创建策略枚举
import org.springframework.security.config.http.SessionCreationPolicy;
// 密码编码器工厂
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
// 密码编码器接口
import org.springframework.security.crypto.password.PasswordEncoder;
// 安全过滤器链
import org.springframework.security.web.SecurityFilterChain;
// 未认证时直接返回指定 HTTP 状态
import org.springframework.security.web.authentication.HttpStatusEntryPoint;

// Servlet 响应对象
import jakarta.servlet.http.HttpServletResponse;

/**
 * 后台 HTTP API：RBAC + 方法级 {@link org.springframework.security.access.prepost.PreAuthorize}。
 * 游戏 Netty 端口业务不受此过滤器影响。
 */
@Configuration // 本类为 Spring 配置类
@EnableWebSecurity // 启用 Spring Security Web 防护
@EnableMethodSecurity // 启用 @PreAuthorize 等方法级权限
public class AdminSecurityConfig {

    /**
     * 密码编码器。
     *
     * @return 委派编码器；根据前缀自动选择具体算法
     */
    @Bean // 注册为 Spring Bean，供登录校验注入
    public PasswordEncoder passwordEncoder() {
        // 创建支持 {bcrypt} 等前缀的委派编码器
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /**
     * 管理端安全过滤链。
     *
     * @param http Spring Security HTTP 构建器
     * @return 过滤链
     * @throws Exception 构建期间异常
     */
    @Bean // 定义 HTTP 请求如何鉴权、如何处理异常
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable); // 前后端分离 API 通常关闭 CSRF
        http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)); // 需要时创建 Session（登录态）
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/admin/login").permitAll() // 登录接口匿名可访问
                .requestMatchers("/api/admin/**").authenticated() // 其余管理接口需已登录
                .anyRequest().permitAll() // 非 /api/admin 路径（如游戏接口）放行
        );
        http.formLogin(AbstractHttpConfigurer::disable); // 不用表单登录页
        http.httpBasic(AbstractHttpConfigurer::disable); // 不用 HTTP Basic
        // 统一返回 JSON 错误体，避免默认 HTML 错误页影响前后端分离调用。
        http.exceptionHandling(ex -> ex
                .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)) // 未登录 → 401
                .accessDeniedHandler((request, response, accessDeniedException) -> { // 已登录但无权限 → 403 JSON
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"error\":\"forbidden\"}");
                })
        );
        return http.build(); // 构建并返回过滤器链
    }

    /**
     * 暴露认证管理器，供登录接口显式认证。
     *
     * @param config Spring 认证配置
     * @return 认证管理器
     * @throws Exception 初始化异常
     */
    @Bean // 供 AdminLoginController 调用 authenticate
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
