// 登录接口 IP / 账号维度的令牌桶限流
package cn.itcast.demo.mylunarcore.net;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 登录请求限流器。
 * <p>
 * 使用 Bucket4j 令牌桶分别按客户端 IP 与账号名维护独立配额，
 * 防止暴力破解密码、刷登录接口占满业务线程与数据库连接。
 * 配置项：lunarcore.rate-limit.login-enabled、login-ip-per-minute、login-account-per-minute。
 */
@Component
public class GameLoginRateLimiter {

    private final LunarCoreProperties properties;

    /** IP → 令牌桶：同一 IP 每分钟最多 N 次登录尝试。 */
    private final ConcurrentHashMap<String, Bucket> ipBuckets = new ConcurrentHashMap<>();

    /** 账号（小写）→ 令牌桶：同一账号每分钟最多 M 次尝试，防针对单账号爆破。 */
    private final ConcurrentHashMap<String, Bucket> accountBuckets = new ConcurrentHashMap<>();

    public GameLoginRateLimiter(LunarCoreProperties properties) {
        this.properties = properties;
    }

    /**
     * 尝试消耗一次登录配额。
     * 先检查 IP 桶，再检查账号桶（username 非空时）；任一桶耗尽则拒绝。
     *
     * @param clientIp 客户端 IP，null/空时使用 "unknown" 作为键
     * @param username 登录用户名，可为 null（仅 IP 限流）
     * @return true 表示允许本次登录；false 表示被限流（应返回 RET_RATE_LIMITED）
     */
    public boolean tryAcquire(String clientIp, String username) {
        if (!properties.getRateLimit().isLoginEnabled()) {
            return true;
        }
        String ipKey = clientIp == null || clientIp.isBlank() ? "unknown" : clientIp;
        if (!resolveIpBucket(ipKey).tryConsume(1)) {
            return false;
        }
        if (username != null && !username.isBlank()) {
            return resolveAccountBucket(username.trim().toLowerCase()).tryConsume(1);
        }
        return true;
    }

    /** 懒创建 IP 维度令牌桶，容量与 refill 速率来自配置 loginIpPerMinute。 */
    private Bucket resolveIpBucket(String ip) {
        return ipBuckets.computeIfAbsent(ip, ignored -> newBucket(properties.getRateLimit().getLoginIpPerMinute()));
    }

    /** 懒创建账号维度令牌桶。 */
    private Bucket resolveAccountBucket(String account) {
        return accountBuckets.computeIfAbsent(account, ignored -> newBucket(properties.getRateLimit().getLoginAccountPerMinute()));
    }

    /**
     * 构造每分钟补充 permitsPerMinute 个令牌的经典令牌桶。
     * capacity 与 refill 量相同，表示桶满时可突发处理一整分钟配额。
     */
    private static Bucket newBucket(int permitsPerMinute) {
        int capacity = Math.max(1, permitsPerMinute);
        Bandwidth limit = Bandwidth.classic(capacity, Refill.greedy(capacity, Duration.ofMinutes(1)));
        return Bucket.builder().addLimit(limit).build();
    }
}
