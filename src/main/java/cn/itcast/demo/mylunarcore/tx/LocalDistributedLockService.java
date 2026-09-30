package cn.itcast.demo.mylunarcore.tx;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 单机回退锁：{@code lunarcore.redis.enabled=false} 时使用。
 */
@Service
@ConditionalOnProperty(prefix = "lunarcore.redis", name = "enabled", havingValue = "false", matchIfMissing = true)
public class LocalDistributedLockService implements DistributedLockService {

    private final ConcurrentHashMap<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    @Override
    public <T> T withLock(String lockKey, long waitTime, long leaseTime, TimeUnit unit, Supplier<T> action) {
        ReentrantLock lock = locks.computeIfAbsent(lockKey, k -> new ReentrantLock());
        boolean acquired;
        try {
            acquired = lock.tryLock(waitTime, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LockAcquireException(lockKey);
        }
        if (!acquired) {
            throw new LockAcquireException(lockKey);
        }
        try {
            return action.get();
        } finally {
            lock.unlock();
            if (!lock.hasQueuedThreads() && !lock.isLocked()) {
                locks.remove(lockKey, lock);
            }
        }
    }
}
