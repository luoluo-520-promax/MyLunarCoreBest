// 管理后台 Spring Security 配置所在包
package cn.itcast.demo.mylunarcore.admin;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;

/**
 * 后台 HTTP API：Session + CSRF + RBAC。
 * 游戏 Netty 端口业务不受此过滤器影响。
 * <p>
 * 设计意图：
 * <ul>
 *   <li>登录成功后使用 HTTP Session 保存认证上下文，适合运营后台这类浏览器访问场景；</li>
 *   <li>使用 CookieCsrfTokenRepository 让前端通过 Cookie + 请求头/参数携带 CSRF 令牌，
 *       防止后台接口被跨站请求伪造；</li>
 *   <li>通过自定义过滤器接入 IP 白名单和内部 API Token，给敏感接口再加一层网络/密钥隔离。</li>
 * </ul>
 */
@Configuration // 声明为 Spring 配置类
@EnableWebSecurity // 启用 Spring Security Web 支持
@EnableMethodSecurity // 允许 @PreAuthorize 这类方法级鉴权注解生效
public class AdminSecurityConfig {

    /**
     * 密码编码器工厂。
     * <p>
     * 返回 DelegatingPasswordEncoder 的原因：它支持多种编码前缀（如 bcrypt、argon2），
     * 既能兼容旧密码哈希，也方便未来平滑升级算法，而不是把所有账号一次性重置。
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /**
     * 内部 API 鉴权过滤器 Bean。
     * <p>
     * 负责校验 {@code /internal/**} 请求的 X-Internal-Token，并可叠加 IP/CIDR 白名单。
     */
    @Bean
    public InternalApiAuthFilter internalApiAuthFilter(LunarCoreProperties properties) {
        return new InternalApiAuthFilter(properties);
    }

    /**
     * 管理后台 IP 白名单过滤器 Bean。
     * <p>
     * 当 {@code lunarcore.admin.ip-whitelist} 配置为空时放行，便于本地开发；
     * 一旦配置了白名单，则只允许白名单内 IP 访问 /api/admin/**。
     */
    @Bean
    public AdminIpWhitelistFilter adminIpWhitelistFilter(LunarCoreProperties properties) {
        return new AdminIpWhitelistFilter(properties);
    }

    /**
     * 核心安全链：定义 CSRF、会话、授权规则、过滤器顺序与异常响应。
     *
     * @param http Spring Security 的 HTTP 构建器
     * @param internalApiAuthFilter 内部 API Token 校验过滤器
     * @param adminIpWhitelistFilter 管理后台 IP 白名单过滤器
     * @return 构建完成的安全过滤链
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   InternalApiAuthFilter internalApiAuthFilter,
                                                   AdminIpWhitelistFilter adminIpWhitelistFilter) throws Exception {
        // 将 CSRF token 暴露为请求属性名称 _csrf，供前端模板/JS 读取
        CsrfTokenRequestAttributeHandler requestHandler = new CsrfTokenRequestAttributeHandler();
        requestHandler.setCsrfRequestAttributeName("_csrf");

        // CSRF：写操作默认开启；/internal/** 由内部密钥保护，故显式忽略
        http.csrf(csrf -> csrf
                .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                .csrfTokenRequestHandler(requestHandler)
                .ignoringRequestMatchers("/internal/**")
        );
        // Session：按需创建，适合登录后持久化后台会话
        http.sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED));
        // URL 级授权：
        // - 健康检查公开；
        // - Prometheus 指标需要登录；
        // - /admin/** 旧前端静态资源公开；
        // - /api/admin/login 与 /api/admin/csrf 允许匿名访问以便获取登录态与 CSRF；
        // - 其他后台 API 需要认证；
        // - /internal/** 仅放行到过滤器层，真正鉴权在 InternalApiAuthFilter 中完成。
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                .requestMatchers("/actuator/prometheus").authenticated()
                .requestMatchers("/admin/**").permitAll()
                .requestMatchers("/api/admin/login", "/api/admin/csrf").permitAll()
                .requestMatchers("/api/admin/**").authenticated()
                // Token 由 InternalApiAuthFilter 校验；此处仅放行过滤器链路
                .requestMatchers("/internal/**").permitAll()
                .anyRequest().denyAll()
        );
        // 让白名单过滤器与内部 API 过滤器都位于用户名密码认证过滤器之前，确保在认证前拦截
        http.addFilterBefore(adminIpWhitelistFilter, UsernamePasswordAuthenticationFilter.class);
        http.addFilterBefore(internalApiAuthFilter, UsernamePasswordAuthenticationFilter.class);
        // 禁用默认表单登录与 HTTP Basic：后台只走本项目自定义登录接口
        http.formLogin(AbstractHttpConfigurer::disable);
        http.httpBasic(AbstractHttpConfigurer::disable);
        // 统一异常响应：认证失败 401，权限不足 403，响应体为简洁 JSON，避免前端再解析 HTML 错误页
        http.exceptionHandling(ex -> ex
                .authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
                .accessDeniedHandler((request, response, accessDeniedException) -> {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"error\":\"forbidden\"}");
                })
        );
        return http.build();
    }

    /**
     * 暴露 Spring Security 认证管理器。
     * <p>
     * 后台登录接口会显式调用该管理器执行用户名/密码认证；
     * 这里直接从 {@link AuthenticationConfiguration} 取出容器中最终组装好的实现。
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }
}
