// 网关限流过滤器所在包
package cn.itcast.demo.mylunarcore.gateway.ratelimit;

// Jackson：JSON 序列化异常类型
import com.fasterxml.jackson.core.JsonProcessingException;
// Jackson：将 Java 对象转为 JSON 的核心类
import com.fasterxml.jackson.databind.ObjectMapper;
// Gateway 过滤器链：把请求交给下一个过滤器或路由
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
// Gateway 全局过滤器接口
import org.springframework.cloud.gateway.filter.GlobalFilter;
// 过滤器执行顺序接口（数值越小越先执行）
import org.springframework.core.Ordered;
// HTTP 方法枚举（GET、POST、OPTIONS 等）
import org.springframework.http.HttpMethod;
// HTTP 状态码枚举（如 429 Too Many Requests）
import org.springframework.http.HttpStatus;
// 响应 Content-Type 常量（如 application/json）
import org.springframework.http.MediaType;
// 请求路径容器（解析后的路径片段）
import org.springframework.http.server.PathContainer;
// 响应式 HTTP 请求对象
import org.springframework.http.server.reactive.ServerHttpRequest;
// 响应式 HTTP 响应对象
import org.springframework.http.server.reactive.ServerHttpResponse;
// 标记为 Spring 组件，自动注册到容器
import org.springframework.stereotype.Component;
// Ant 风格路径匹配器（支持 **、* 通配符）
import org.springframework.util.AntPathMatcher;
// 一次 HTTP 交换（请求 + 响应）的上下文
import org.springframework.web.server.ServerWebExchange;
// Reactor 异步单值/空类型
import reactor.core.publisher.Mono;

// 字符集 UTF-8
import java.nio.charset.StandardCharsets;
// 当前时间戳
import java.time.Instant;
// 键值对 Map
import java.util.Map;
// 线程安全的并发 HashMap
import java.util.concurrent.ConcurrentHashMap;
// 线程安全的整数计数器
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 网关全局限流过滤器：按「全局 QPS」和「单用户 QPS」做秒级计数限流。
 * <p>超过阈值时返回 HTTP 429 与统一 JSON 错误体。</p>
 */
@Component // 注册为 Spring Bean，由 Gateway 自动加载
public class GatewayRateLimitFilter implements GlobalFilter, Ordered {

    // 路径匹配器，用于判断请求是否命中排除规则
    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    // 从配置文件读取的限流参数（开关、QPS、排除路径等）
    private final GatewayRateLimitProperties properties;
    // 将错误响应序列化为 JSON 字节
    private final ObjectMapper objectMapper;
    // 当前秒内全站请求计数
    private final AtomicInteger globalCounter = new AtomicInteger(0);
    // 当前秒内每个用户（uid 或 IP）的请求计数
    private final ConcurrentHashMap<String, AtomicInteger> userCounters = new ConcurrentHashMap<>();
    // 当前计数窗口对应的秒级时间戳（volatile 保证多线程可见）
    private volatile long currentSecond = Instant.now().getEpochSecond();

    /**
     * 构造器：注入配置与 JSON 工具。
     *
     * @param properties  限流配置属性
     * @param objectMapper  JSON 序列化器
     */
    public GatewayRateLimitFilter(GatewayRateLimitProperties properties, ObjectMapper objectMapper) {
        this.properties = properties; // 保存限流配置
        this.objectMapper = objectMapper; // 保存 JSON 工具
    }

    /**
     * 过滤器主逻辑：在请求进入下游服务前检查是否超限。
     */
    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 限流总开关关闭时，直接放行
        if (!properties.isEnabled()) {
            return chain.filter(exchange);
        }
        // 浏览器 CORS 预检 OPTIONS 请求不做限流
        if (exchange.getRequest().getMethod() == HttpMethod.OPTIONS) {
            return chain.filter(exchange);
        }

        // 取出应用内路径（不含网关前缀）
        String requestPath = extractPath(exchange.getRequest());
        // 登录、健康检查等白名单路径不限流
        if (isExcludedPath(requestPath)) {
            return chain.filter(exchange);
        }

        // 若进入新的一秒，重置计数器
        rollWindowIfNecessary();
        // 全局限流：本秒全局请求数 +1 后是否超过 globalQps
        if (globalCounter.incrementAndGet() > properties.getGlobalQps()) {
            return tooManyRequests(exchange.getResponse(), "global_rate_limit_exceeded");
        }

        // 按用户或 IP 维度限流
        String userKey = resolveUserKey(exchange.getRequest());
        // 为每个 userKey 懒创建独立计数器
        AtomicInteger userCounter = userCounters.computeIfAbsent(userKey, key -> new AtomicInteger(0));
        // 单用户本秒请求数 +1 后是否超过 userQps
        if (userCounter.incrementAndGet() > properties.getUserQps()) {
            return tooManyRequests(exchange.getResponse(), "user_rate_limit_exceeded");
        }

        // 未超限：继续过滤器链
        return chain.filter(exchange);
    }

    /**
     * 秒级滑动窗口：进入新秒时清零全局与用户计数。
     */
    private void rollWindowIfNecessary() {
        long second = Instant.now().getEpochSecond(); // 当前秒
        if (second != currentSecond) { // 秒数变化，需要滚动窗口
            synchronized (this) { // 双重检查，避免并发重复清零
                if (second != currentSecond) {
                    currentSecond = second; // 更新窗口起点
                    globalCounter.set(0); // 重置全站计数
                    userCounters.clear(); // 重置所有用户计数
                }
            }
        }
    }

    /**
     * 解析限流维度键：优先用 X-User-Id，否则用客户端 IP。
     */
    private String resolveUserKey(ServerHttpRequest request) {
        String uid = request.getHeaders().getFirst("X-User-Id"); // 网关鉴权后透传的用户 ID
        if (uid != null && !uid.isBlank()) {
            return "uid:" + uid; // 已登录用户按 uid 限流
        }
        // 未登录：用远程 IP（可能为 null）
        String ip = request.getRemoteAddress() == null ? "unknown" : String.valueOf(request.getRemoteAddress().getAddress());
        return "ip:" + ip; // 匿名用户按 IP 限流
    }

    /**
     * 判断路径是否匹配配置中的任一排除模式。
     */
    private boolean isExcludedPath(String path) {
        for (String pattern : properties.getExcludePaths()) { // 遍历白名单模式
            if (PATH_MATCHER.match(pattern, path)) { // Ant 风格匹配
                return true; // 命中则不限流
            }
        }
        return false; // 未命中任何排除规则
    }

    /**
     * 从请求中提取应用内路径字符串。
     */
    private String extractPath(ServerHttpRequest request) {
        PathContainer path = request.getPath().pathWithinApplication(); // 去掉网关路由前缀后的路径
        return path.value(); // 转为字符串，如 /auth/login
    }

    /**
     * 构造 429 响应并写入 JSON 错误体。
     */
    private Mono<Void> tooManyRequests(ServerHttpResponse response, String message) {
        response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS); // HTTP 429
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON); // 声明 JSON 响应
        byte[] body = toJsonBytes(Map.of("code", 42901, "message", message, "data", null)); // 统一错误结构
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body))); // 异步写出响应体
    }

    /**
     * 将 Map 序列化为 UTF-8 字节；失败时使用硬编码兜底 JSON。
     */
    private byte[] toJsonBytes(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsBytes(payload); // 正常序列化
        } catch (JsonProcessingException ex) {
            // 序列化异常时的固定兜底内容
            return "{\"code\":42901,\"message\":\"too_many_requests\",\"data\":null}".getBytes(StandardCharsets.UTF_8);
        }
    }

    /**
     * 过滤器顺序：-90 表示在鉴权（-100）之后、路由之前执行。
     */
    @Override
    public int getOrder() {
        return -90;
    }
}
