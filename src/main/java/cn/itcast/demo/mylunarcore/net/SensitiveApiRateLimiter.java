package cn.itcast.demo.mylunarcore.net;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 敏感接口 UID/IP 细粒度限流（抽卡、购买体力等）。
 */
@Component
public class SensitiveApiRateLimiter {

    private final LunarCoreProperties properties;
    private final ConcurrentHashMap<String, Bucket> ipBuckets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Bucket> uidBuckets = new ConcurrentHashMap<>();

    public SensitiveApiRateLimiter(LunarCoreProperties properties) {
        this.properties = properties;
    }

    public boolean tryAcquireGacha(String clientIp, int playerId) {
        if (!properties.getRateLimit().isSensitiveEnabled() && !properties.getRateLimit().isGachaEnabled()) {
            return true;
        }
        return tryAcquire("gacha", clientIp, playerId,
                properties.getRateLimit().getGachaIpPerMinute(),
                properties.getRateLimit().getGachaUidPerMinute());
    }

    public boolean tryAcquireStaminaBuy(String clientIp, int playerId) {
        if (!properties.getRateLimit().isSensitiveEnabled()) {
            return true;
        }
        return tryAcquire("stamina", clientIp, playerId,
                properties.getRateLimit().getStaminaIpPerMinute(),
                properties.getRateLimit().getStaminaUidPerMinute());
    }

    private boolean tryAcquire(String biz, String clientIp, int playerId, int ipPerMin, int uidPerMin) {
        String ipKey = biz + ":ip:" + (clientIp == null || clientIp.isBlank() ? "unknown" : clientIp);
        if (ipPerMin > 0 && !bucket(ipBuckets, ipKey, ipPerMin).tryConsume(1)) {
            return false;
        }
        if (playerId > 0 && uidPerMin > 0) {
            String uidKey = biz + ":uid:" + playerId;
            return bucket(uidBuckets, uidKey, uidPerMin).tryConsume(1);
        }
        return true;
    }

    private static Bucket bucket(ConcurrentHashMap<String, Bucket> map, String key, int perMinute) {
        return map.computeIfAbsent(key, k -> Bucket.builder()
                .addLimit(Bandwidth.classic(Math.max(1, perMinute),
                        Refill.greedy(Math.max(1, perMinute), Duration.ofMinutes(1))))
                .build());
    }
}
