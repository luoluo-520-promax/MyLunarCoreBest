package cn.itcast.demo.mylunarcore.economy;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 玩家货币热点二级缓存：本地 Caffeine + Redis，写路径采用缓存双写 + 延迟双删保证最终一致。
 */
@Service
public class HotWalletCacheService {

    private static final Logger log = LoggerFactory.getLogger(HotWalletCacheService.class);
    private static final String REDIS_KEY = "lunar:wallet:hot:";
    private static final Duration REDIS_TTL = Duration.ofMinutes(10);
    private static final long DELAY_DELETE_MS = 500L;

    private final Cache<Integer, Map<Integer, Integer>> local = Caffeine.newBuilder()
            .maximumSize(50_000)
            .expireAfterWrite(Duration.ofSeconds(30))
            .build();
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final ScheduledExecutorService delayDelete =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "hot-wallet-delay-delete");
                t.setDaemon(true);
                return t;
            });

    public HotWalletCacheService(ObjectProvider<StringRedisTemplate> redisProvider,
                                 ObjectProvider<ObjectMapper> mapperProvider) {
        this.redis = redisProvider == null ? null : redisProvider.getIfAvailable();
        this.objectMapper = mapperProvider == null || mapperProvider.getIfAvailable() == null
                ? new ObjectMapper()
                : mapperProvider.getIfAvailable();
    }

    public Map<Integer, Integer> get(int playerId) {
        Map<Integer, Integer> hit = local.getIfPresent(playerId);
        if (hit != null) {
            return hit;
        }
        if (redis != null) {
            try {
                String raw = redis.opsForValue().get(REDIS_KEY + playerId);
                if (raw != null && !raw.isBlank()) {
                    Map<Integer, Integer> parsed = objectMapper.readValue(raw, new TypeReference<>() {});
                    local.put(playerId, parsed);
                    return parsed;
                }
            } catch (Exception e) {
                log.debug("hot_wallet redis get failed uid={}", playerId, e);
            }
        }
        return null;
    }

    /** 双写：本地 + Redis 同步更新。 */
    public void put(int playerId, Map<Integer, Integer> balance) {
        if (balance == null) {
            return;
        }
        Map<Integer, Integer> copy = Map.copyOf(balance);
        local.put(playerId, copy);
        if (redis == null) {
            return;
        }
        try {
            redis.opsForValue().set(REDIS_KEY + playerId, objectMapper.writeValueAsString(copy), REDIS_TTL);
        } catch (Exception e) {
            log.warn("hot_wallet redis put failed uid={}", playerId, e);
        }
    }

    /**
     * 延迟双删：先删一次，落库后再延迟删一次，避免并发读回填脏数据。
     */
    public void invalidateWithDelayDoubleDelete(int playerId) {
        invalidateNow(playerId);
        delayDelete.schedule(() -> invalidateNow(playerId), DELAY_DELETE_MS, TimeUnit.MILLISECONDS);
    }

    public void invalidateNow(int playerId) {
        local.invalidate(playerId);
        if (redis == null) {
            return;
        }
        try {
            redis.delete(REDIS_KEY + playerId);
        } catch (Exception e) {
            log.debug("hot_wallet redis delete failed uid={}", playerId, e);
        }
    }
}
