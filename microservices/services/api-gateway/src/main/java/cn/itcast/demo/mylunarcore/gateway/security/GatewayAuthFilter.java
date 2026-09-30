// 网关 JWT 鉴权过滤器所在包

// 当前类所属包：cn.itcast.demo.mylunarcore.gateway.security
package cn.itcast.demo.mylunarcore.gateway.security;



// Jackson：JSON 序列化异常

// Jackson JSON 库
import com.fasterxml.jackson.core.JsonProcessingException;

// Jackson：对象与 JSON 互转

// Jackson JSON 库
import com.fasterxml.jackson.databind.ObjectMapper;

// JWT 载荷（claims）类型

// JJWT 令牌库
import io.jsonwebtoken.Claims;

// JWT 解析/校验失败时抛出的异常

// JJWT 令牌库
import io.jsonwebtoken.JwtException;

// JJWT 构建与解析 JWT 的入口

// JJWT 令牌库
import io.jsonwebtoken.Jwts;

// 根据密钥字节生成 HMAC 密钥对象

// JJWT 令牌库
import io.jsonwebtoken.security.Keys;

// 过滤器执行顺序

// Spring 框架类
import org.springframework.core.Ordered;

// Gateway 过滤器链

// Spring 框架类
import org.springframework.cloud.gateway.filter.GatewayFilterChain;

// Gateway 全局过滤器接口

// Spring 框架类
import org.springframework.cloud.gateway.filter.GlobalFilter;

// 标准 HTTP 头名常量（如 Authorization）

// Spring 框架类
import org.springframework.http.HttpHeaders;

// HTTP 方法枚举

// Spring 框架类
import org.springframework.http.HttpMethod;

// HTTP 状态码

// Spring 框架类
import org.springframework.http.HttpStatus;

// Content-Type 常量

// Spring 框架类
import org.springframework.http.MediaType;

// 应用内路径容器

// Spring 框架类
import org.springframework.http.server.PathContainer;

// 响应式请求对象

// Spring 框架类
import org.springframework.http.server.reactive.ServerHttpRequest;

// 响应式响应对象

// Spring 框架类
import org.springframework.http.server.reactive.ServerHttpResponse;

// 注册为 Spring 组件

// Spring 框架类
import org.springframework.stereotype.Component;

// Ant 风格路径匹配

// Spring 框架类
import org.springframework.util.AntPathMatcher;

// 单次请求-响应交换上下文

// Spring 框架类
import org.springframework.web.server.ServerWebExchange;

// Reactor 异步类型

// Project Reactor 响应式
import reactor.core.publisher.Mono;



// HMAC 对称密钥类型

// JDK 加密
import javax.crypto.SecretKey;

// UTF-8 字符集

// JDK NIO
import java.nio.charset.StandardCharsets;

// 键值对 Map

// JDK 集合或工具
import java.util.Map;



/**

 * 网关全局限流前的 JWT 鉴权：校验 Bearer Token，解析 uid/username/role 并写入下游请求头。

 */

@Component // 由 Spring 扫描并加入 Gateway 过滤器链

public class GatewayAuthFilter implements GlobalFilter, Ordered {



    // Authorization 头中 Bearer 前缀（注意末尾有空格）

    private static final String BEARER_PREFIX = "Bearer ";

    // 白名单路径匹配器

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();



    // 鉴权开关、JWT 密钥、issuer、排除路径等配置

    private final GatewayAuthProperties authProperties;

    // 将 401 错误体序列化为 JSON

    private final ObjectMapper objectMapper;

    // 用配置中的 jwtSecret 生成的 HMAC 验签密钥

    private final SecretKey secretKey;



    /**

     * 构造器：注入配置与 JSON 工具，并初始化验签密钥。

     */

    public GatewayAuthFilter(GatewayAuthProperties authProperties, ObjectMapper objectMapper) {

        this.authProperties = authProperties; // 保存鉴权配置

        this.objectMapper = objectMapper; // 保存 JSON 工具

        // 将配置中的密钥字符串转为 UTF-8 字节后生成 SecretKey（长度须满足 HMAC 要求）

        this.secretKey = Keys.hmacShaKeyFor(authProperties.getJwtSecret().getBytes(StandardCharsets.UTF_8));

    }



    /**

     * 过滤器主逻辑：未启用/白名单/OPTIONS 放行；否则校验 JWT 并透传用户头。

     */

    @Override

    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {

        // 鉴权总开关关闭时直接放行

        if (!authProperties.isEnabled()) {

            return chain.filter(exchange);

        }

        // CORS 预检 OPTIONS 不校验 Token

        if (exchange.getRequest().getMethod() == HttpMethod.OPTIONS) {

            return chain.filter(exchange);

        }



        // 取出应用内路径（不含网关路由前缀）

        String requestPath = extractPath(exchange.getRequest());

        // 登录、健康检查等白名单路径不校验

        if (isExcludedPath(requestPath)) {

            return chain.filter(exchange);

        }



        // 读取 Authorization 头

        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);

        // 缺少头或非 Bearer 格式 → 401

        if (authHeader == null || !authHeader.startsWith(BEARER_PREFIX)) {

            return unauthorized(exchange.getResponse(), "missing_or_invalid_authorization_header");

        }



        // 去掉 "Bearer " 前缀得到原始 JWT 字符串

        String token = authHeader.substring(BEARER_PREFIX.length()).trim();

        // 验签并解析 claims；失败返回 null

        Claims claims = parseClaims(token);

        if (claims == null) {

            return unauthorized(exchange.getResponse(), "invalid_token");

        }

        // 优先从自定义 claim "uid" 取用户 ID

        Object uidValue = claims.get("uid");

        if (uidValue == null) {

            // 兼容：部分 Token 把 uid 放在 subject 里

            uidValue = claims.getSubject();

        }

        if (uidValue == null) {

            return unauthorized(exchange.getResponse(), "missing_uid_claim");

        }

        String uid = String.valueOf(uidValue); // 转为字符串写入请求头

        String username = String.valueOf(claims.getOrDefault("username", "")); // 用户名，缺省空串

        String role = String.valueOf(claims.getOrDefault("role", "")); // 角色，缺省空串



        // 剥离入站伪造身份头后，再写入网关签发的身份头

        ServerHttpRequest request = exchange.getRequest().mutate()

                .headers(headers -> {

                    headers.remove("X-User-Id");

                    headers.remove("X-User-Name");

                    headers.remove("X-User-Role");

                })

                .header("X-User-Id", uid)

                .header("X-User-Name", username)

                .header("X-User-Role", role)

                .build();

        // 用新请求替换 exchange 中的请求，继续过滤器链

        return chain.filter(exchange.mutate().request(request).build());

    }



    /**

     * 解析并校验 JWT；异常时返回 null（由调用方返回 401）。

     */

    private Claims parseClaims(String token) {

        try {

            return Jwts.parser()

                    .verifyWith(secretKey) // 使用 HMAC 验签

                    .requireIssuer(authProperties.getJwtIssuer()) // 校验 issuer 与 auth-service 一致

                    .build()

                    .parseSignedClaims(token) // 解析带签名的 JWT

                    .getPayload(); // 取出载荷（claims）

        } catch (JwtException | IllegalArgumentException ex) {

            // 签名错误、过期、issuer 不匹配等均视为无效 Token

            return null;

        }

    }



    /**

     * 判断路径是否命中鉴权白名单（Ant 模式）。

     */

    private boolean isExcludedPath(String path) {

        for (String pattern : authProperties.getExcludePaths()) { // 遍历配置的白名单

            if (PATH_MATCHER.match(pattern, path)) { // 如 /auth/** 匹配 /auth/login

                return true;

            }

        }

        return false;

    }



    /**

     * 提取应用内路径字符串。

     */

    private String extractPath(ServerHttpRequest request) {

        PathContainer path = request.getPath().pathWithinApplication();

        return path.value();

    }



    /**

     * 返回 401 Unauthorized 及统一 JSON 错误体。

     */

    private Mono<Void> unauthorized(ServerHttpResponse response, String message) {

        response.setStatusCode(HttpStatus.UNAUTHORIZED); // HTTP 401

        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);

        byte[] body = toJsonBytes(Map.of("code", 40101, "message", message, "data", null));

        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)));

    }



    /**

     * Map 转 JSON 字节；序列化失败时使用硬编码兜底。

     */

    private byte[] toJsonBytes(Map<String, Object> payload) {

        try {

            return objectMapper.writeValueAsBytes(payload);

        } catch (JsonProcessingException ex) {

            return "{\"code\":40101,\"message\":\"unauthorized\",\"data\":null}".getBytes(StandardCharsets.UTF_8);

        }

    }



    /**

     * 过滤器顺序：-100 表示在限流（-90）之前执行，优先完成鉴权。

     */

    @Override

    public int getOrder() {

        return -100;

    }

}


