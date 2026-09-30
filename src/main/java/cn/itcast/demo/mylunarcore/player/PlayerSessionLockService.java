// 按玩家 uid 串行化 Session / PlayerData 读写的锁服务
package cn.itcast.demo.mylunarcore.player;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * 玩家级细粒度锁服务。
 * <p>
 * Netty IO 线程、异步加载线程池、周期持久化线程可能同时访问同一 uid 的
 * {@link GameSession} 与 {@link cn.itcast.demo.mylunarcore.model.PlayerData}；
 * 本服务为每个 uid 分配一把 {@link ReentrantLock}，保证变更操作串行执行，避免脏写与撕裂读。
 */
@Component
public class PlayerSessionLockService {

    /**
     * uid → 可重入锁映射。
     * computeIfAbsent 懒创建锁对象；玩家离线后 release 或 cleanupIfIdle 回收，防止 Map 无限增长。
     */
    private final ConcurrentHashMap<Long, ReentrantLock> locks = new ConcurrentHashMap<>();

    /**
     * 在玩家锁内执行无返回值操作。
     * uid ≤ 0 时跳过加锁直接执行（系统/匿名调用场景）。
     *
     * @param uid    玩家 uid
     * @param action 需在锁内完成的副作用操作
     */
    public void withLock(long uid, Runnable action) {
        if (uid <= 0) {
            action.run();
            return;
        }
        ReentrantLock lock = locks.computeIfAbsent(uid, ignored -> new ReentrantLock());
        lock.lock();
        try {
            action.run();
        } finally {
            lock.unlock();
            cleanupIfIdle(uid, lock);
        }
    }

    /**
     * 在玩家锁内执行有返回值操作。
     *
     * @param uid    玩家 uid
     * @param action 需在锁内完成并返回结果的逻辑
     * @param <T>    返回值类型
     * @return action 的执行结果
     */
    public <T> T withLock(long uid, Supplier<T> action) {
        if (uid <= 0) {
            return action.get();
        }
        ReentrantLock lock = locks.computeIfAbsent(uid, ignored -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
            cleanupIfIdle(uid, lock);
        }
    }

    /**
     * 玩家离线时强制移除锁条目。
     * 由 {@link ConnectionLifecycleService} 在 finalizeDisconnect 时调用。
     *
     * @param uid 已离线玩家 uid
     */
    public void release(long uid) {
        if (uid <= 0) {
            return;
        }
        locks.remove(uid);
    }

    /**
     * 锁释放后若无等待线程且未被重入持有，则从 Map 中移除该锁对象以节省内存。
     * 使用 remove(uid, lock) 原子比较，避免误删已被其他线程新创建的锁。
     *
     * @param uid  玩家 uid
     * @param lock 本次使用的锁实例
     */
    private void cleanupIfIdle(long uid, ReentrantLock lock) {
        if (!lock.hasQueuedThreads() && !lock.isLocked()) {
            locks.remove(uid, lock);
        }
    }
}
