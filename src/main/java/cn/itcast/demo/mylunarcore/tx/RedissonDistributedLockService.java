package cn.itcast.demo.mylunarcore.tx;

import cn.itcast.demo.mylunarcore.common.AppLogger;
import cn.itcast.demo.mylunarcore.common.LogCategory;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Redisson {@link RLock} 实现：多节点钱包/抽卡/IAP 发货互斥。
 * 开启：{@code lunarcore.redis.enabled=true} 且 {@code lunarcore.redis.redisson-enabled=true}。
 */
@Service
@Primary
@ConditionalOnProperty(prefix = "lunarcore.redis", name = "redisson-enabled", havingValue = "true")
public class RedissonDistributedLockService implements DistributedLockService {

    private static final Logger log = AppLogger.logger(LogCategory.SYSTEM, RedissonDistributedLockService.class);

    private final RedissonClient redisson;

    public RedissonDistributedLockService(RedissonClient redisson) {
        this.redisson = redisson;
    }

    @Override
    public <T> T withLock(String lockKey, long waitTime, long leaseTime, TimeUnit unit, Supplier<T> action) {
        RLock lock = redisson.getLock(lockKey);
        boolean acquired = false;
        try {
            acquired = lock.tryLock(waitTime, leaseTime, unit);
            if (!acquired) {
                log.warn("redisson_lock acquire failed key={}", lockKey);
                throw new LockAcquireException(lockKey);
            }
            return action.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LockAcquireException(lockKey);
        } finally {
            if (acquired && lock.isHeldByCurrentThread()) {
                try {
                    lock.unlock();
                } catch (Exception e) {
                    log.warn("redisson_lock unlock failed key={}: {}", lockKey, e.toString());
                }
            }
        }
    }
}
