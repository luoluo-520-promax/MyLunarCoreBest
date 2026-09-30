package cn.itcast.demo.mylunarcore.common;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.tx.DistributedLockService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 多节点定时任务单点执行锁（ShedLock 语义）：优先 Redis SET NX，其次 {@link DistributedLockService}。
 */
@Component
public class ClusterJobLock {

    private final StringRedisTemplate redis;
    private final LunarCoreProperties properties;
    private final DistributedLockService distributedLockService;

    public ClusterJobLock(ObjectProvider<StringRedisTemplate> redisProvider,
                          LunarCoreProperties properties,
                          DistributedLockService distributedLockService) {
        this.redis = redisProvider.getIfAvailable();
        this.properties = properties;
        this.distributedLockService = distributedLockService;
    }

    /**
     * @return true 表示本节点抢到锁并已执行 action；false 表示其他节点已执行或跳过。
     */
    public boolean tryRun(String jobName, Duration lease, Runnable action) {
        Boolean ran = tryRun(jobName, lease, () -> {
            action.run();
            return Boolean.TRUE;
        });
        return Boolean.TRUE.equals(ran);
    }

    public <T> T tryRun(String jobName, Duration lease, Supplier<T> action) {
        String key = "lunar:job:" + jobName;
        long leaseMs = Math.max(1_000L, lease.toMillis());
        if (redis != null && properties.getRedis().isEnabled()) {
            Boolean ok = redis.opsForValue().setIfAbsent(key, properties.getCenter().getLocalNodeId(),
                    Duration.ofMillis(leaseMs));
            if (!Boolean.TRUE.equals(ok)) {
                return null;
            }
            try {
                return action.get();
            } finally {
                // 短任务可提前释放；长任务依赖 TTL
                try {
                    String holder = redis.opsForValue().get(key);
                    if (properties.getCenter().getLocalNodeId().equals(holder)) {
                        redis.delete(key);
                    }
                } catch (Exception ignored) {
                    // TTL 兜底
                }
            }
        }
        try {
            return distributedLockService.withLock(key, 0, leaseMs, TimeUnit.MILLISECONDS, action);
        } catch (DistributedLockService.LockAcquireException e) {
            return null;
        }
    }
}
