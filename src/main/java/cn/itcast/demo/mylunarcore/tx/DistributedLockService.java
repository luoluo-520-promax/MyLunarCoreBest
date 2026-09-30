package cn.itcast.demo.mylunarcore.tx;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 分布式锁抽象（Redisson 语义兼容）：多节点下钱包/抽卡扣费必须跨进程互斥。
 * <p>
 * 生产推荐接入 Redisson {@code RLock}；默认提供 Redis SET NX + 本地回退实现。
 *
 * <p>本接口统一了三种实现的调用契约：
 * <ul>
 *   <li>{@link RedissonDistributedLockService}：生产推荐，使用 Redisson RLock
 *       （自动续期 + 可重入），配置 {@code lunarcore.redis.redisson-enabled=true} 时生效；</li>
 *   <li>{@link RedisDistributedLockService}：纯 Redis SET NX EX + Lua 解锁，无 Redisson 依赖；</li>
 *   <li>{@link LocalDistributedLockService}：单机 JVM 锁（ReentrantLock），
 *       仅用于 Redis 未启用时的本地回退。</li>
 * </ul>
 */
public interface DistributedLockService {

    /**
     * 在指定锁的临界区内执行业务动作。
     *
     * @param lockKey   锁的标识键（如 "lunar:lock:wallet:{playerId}"），同一键在不同节点上互斥
     * @param waitTime  等待获取锁的超时时间（单位由 unit 指定），超过则抛 {@link LockAcquireException}
     * @param leaseTime 持有锁的最长租约时间（单位由 unit 指定），防止持有者崩溃后锁被永久占用
     * @param unit      时间单位
     * @param action    需要互斥执行的业务逻辑
     * @param <T>       业务逻辑返回类型
     * @return 业务逻辑的执行结果
     * @throws LockAcquireException 等待超时或获取失败时抛出
     */
    <T> T withLock(String lockKey, long waitTime, long leaseTime, TimeUnit unit, Supplier<T> action);

    /**
     * 玩家钱包专用锁：键为 {@code lunar:lock:wallet:{playerId}}，
     * 等待 3 秒 / 租约 8 秒。钱包扣费、回补等敏感操作应使用本方法互斥。
     */
    default <T> T withPlayerWalletLock(int playerId, Supplier<T> action) {
        return withLock("lunar:lock:wallet:" + playerId, 3, 8, TimeUnit.SECONDS, action);
    }

    /**
     * 抽卡整单互斥：与钱包锁不同 key，避免长事务占住钱包锁；等待 3s / 租约 15s。
     * <p>
     * 抽卡一单可能包含多次扣费与多次发奖，耗时较长，因此租约时间比钱包锁更长（15s），
     * 并且与钱包锁使用不同 key，避免互相排队阻塞。
     */
    default <T> T withPlayerGachaLock(int playerId, Supplier<T> action) {
        return withLock("lunar:lock:gacha:" + playerId, 3, 15, TimeUnit.SECONDS, action);
    }

    /**
     * 获取锁失败的运行时异常：携带锁 key 便于定位。
     */
    class LockAcquireException extends RuntimeException {
        public LockAcquireException(String key) {
            super("failed to acquire lock: " + key);
        }
    }
}
