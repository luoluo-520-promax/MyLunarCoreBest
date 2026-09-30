package cn.itcast.demo.mylunarcore.tx;

import cn.itcast.demo.mylunarcore.common.AppLogger;
import cn.itcast.demo.mylunarcore.common.LogCategory;
import org.slf4j.Logger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Redis 分布式锁（SET NX EX + Lua 安全解锁），对齐 Redisson 互斥语义。
 * <p>
 * 多节点 {@code center.mode=remote} 下 Wallet/Gacha 扣费必须走此路径，
 * 避免仅靠 DB MVCC 在并发请求下出现双扣风险窗口。
 * 当 {@code lunarcore.redis.redisson-enabled=true} 时由 {@link RedissonDistributedLockService} 接管。
 */
@Service
@ConditionalOnProperty(prefix = "lunarcore.redis", name = "enabled", havingValue = "true")
@ConditionalOnProperty(prefix = "lunarcore.redis", name = "redisson-enabled", havingValue = "false", matchIfMissing = true)
public class RedisDistributedLockService implements DistributedLockService {

    private static final Logger log = AppLogger.logger(LogCategory.SYSTEM, RedisDistributedLockService.class);

    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('get', KEYS[1]) == ARGV[1] then
              return redis.call('del', KEYS[1])
            else
              return 0
            end
            """, Long.class);

    private final StringRedisTemplate redis;

    public RedisDistributedLockService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    @Override
    public <T> T withLock(String lockKey, long waitTime, long leaseTime, TimeUnit unit, Supplier<T> action) {
        String token = UUID.randomUUID().toString();
        long waitMs = unit.toMillis(waitTime);
        long leaseMs = Math.max(500L, unit.toMillis(leaseTime));
        long deadline = System.currentTimeMillis() + waitMs;
        boolean acquired = false;
        while (System.currentTimeMillis() <= deadline) {
            Boolean ok = redis.opsForValue().setIfAbsent(lockKey, token, Duration.ofMillis(leaseMs));
            if (Boolean.TRUE.equals(ok)) {
                acquired = true;
                break;
            }
            try {
                Thread.sleep(25L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (!acquired) {
            log.warn("redis_lock acquire failed key={}", lockKey);
            throw new LockAcquireException(lockKey);
        }
        try {
            return action.get();
        } finally {
            try {
                redis.execute(UNLOCK_SCRIPT, Collections.singletonList(lockKey), token);
            } catch (Exception e) {
                log.warn("redis_lock unlock failed key={}: {}", lockKey, e.toString());
            }
        }
    }
}
