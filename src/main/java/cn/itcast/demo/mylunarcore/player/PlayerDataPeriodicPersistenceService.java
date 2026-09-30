// 定时或断连时将会话内玩家核心快照异步写回数据库
package cn.itcast.demo.mylunarcore.player;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.model.PlayerData;
import cn.itcast.demo.mylunarcore.model.PlayerEntity;
import cn.itcast.demo.mylunarcore.common.AppLogger;
import cn.itcast.demo.mylunarcore.common.LogCategory;
import cn.itcast.demo.mylunarcore.repo.PlayerDataRepository;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 玩家数据周期持久化服务。
 * <p>
 * OnlinePlayer Tick 或断连时触发：在 {@link PlayerSessionLockService} 内 deepCopy 玩家主实体快照，
 * 再提交到 player-data-persist 线程池异步 UPDATE（乐观锁），避免业务线程与 IO 线程直接写库造成竞态。
 * 拒绝策略为 CallerRuns，禁止静默 Abort。
 */
@Service
public class PlayerDataPeriodicPersistenceService {

    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_DATA, PlayerDataPeriodicPersistenceService.class);

    private final PlayerDataRepository repository;
    private final GameSessionManager sessionManager;
    private final PlayerSessionLockService sessionLockService;
    private final LunarCoreProperties properties;
    private final PlayerDataMetrics metrics;
    private final ThreadPoolExecutor executor;

    /** 正在持久化中的 uid 集合，add 失败表示已有任务在途，跳过重复提交 */
    private final Set<Long> persisting = ConcurrentHashMap.newKeySet();

    public PlayerDataPeriodicPersistenceService(PlayerDataRepository repository,
                                                GameSessionManager sessionManager,
                                                PlayerSessionLockService sessionLockService,
                                                LunarCoreProperties properties,
                                                PlayerDataMetrics metrics) {
        this.repository = repository;
        this.sessionManager = sessionManager;
        this.sessionLockService = sessionLockService;
        this.properties = properties;
        this.metrics = metrics;
        int cpu = Runtime.getRuntime().availableProcessors();
        int configuredThreads = properties.getSync().getAsyncLoadThreads();
        int threads = configuredThreads > 0 ? configuredThreads : Math.max(2, cpu / 2);
        int configuredQueue = properties.getSync().getAsyncLoadQueueCapacity();
        int queueCapacity = configuredQueue > 0 ? configuredQueue : 1024;
        this.executor = new ThreadPoolExecutor(
                threads,
                threads,
                30L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                r -> {
                    Thread t = new Thread(r, "player-data-persist");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    public long intervalMs() {
        if (!properties.getSync().isPeriodicPersistenceEnabled()) {
            return 0L;
        }
        return Math.max(0L, properties.getSync().getPeriodicPersistenceIntervalMs());
    }

    public void persistAsync(long uid) {
        persistAsync(uid, SyncReason.TIMER);
    }

    public void persistAsync(long uid, SyncReason reason) {
        if (uid <= 0 || !persisting.add(uid)) {
            return;
        }
        boolean requireDirty = reason == SyncReason.TIMER;
        PersistSnapshot snapshot = sessionLockService.withLock(uid, () -> {
            GameSession session = sessionManager.getOrNull(uid);
            if (session == null) {
                return null;
            }
            if (requireDirty && !session.isDirty()) {
                return null;
            }
            PlayerData data = session.getPlayerData();
            if (data == null || data.getPlayer() == null) {
                return null;
            }
            // LOGOUT 等强制落盘：若尚未标记 dirty，补一次世代以便成功后清脏
            long dirtyGen = session.isDirty() ? session.currentDirtyGeneration() : session.markDirty();
            PlayerEntity player = data.deepCopy().getPlayer();
            player.setDataVersion(session.getDataVersion());
            return new PersistSnapshot(player, dirtyGen);
        });
        if (snapshot == null) {
            persisting.remove(uid);
            return;
        }
        try {
            executor.execute(() -> doPersist(uid, reason, snapshot));
        } catch (RejectedExecutionException e) {
            metrics.recordPersistReject();
            log.warn("persist rejected, uid={}, reason={}", uid, reason, e);
            persisting.remove(uid);
        }
    }

    private void doPersist(long uid, SyncReason reason, PersistSnapshot snapshot) {
        try {
            int updated = repository.persistPlayerSnapshot(snapshot.player());
            if (updated <= 0) {
                metrics.recordVersionConflict();
                log.warn("persist version conflict, uid={}, reason={}, expectedVersion={}",
                        uid, reason, snapshot.player().getDataVersion());
                return;
            }
            sessionLockService.withLock(uid, () -> {
                GameSession session = sessionManager.getOrNull(uid);
                if (session == null) {
                    return;
                }
                long expected = snapshot.player().getDataVersion() - 1;
                session.onPersistVersionAdvanced(expected, snapshot.player().getDataVersion());
                session.markPersisted(snapshot.dirtyGeneration());
            });
        } catch (Exception e) {
            metrics.recordPersistFail();
            log.error("persist failed, uid={}, reason={}", uid, reason, e);
        } finally {
            persisting.remove(uid);
        }
    }

    /**
     * 优雅关闭时同步落盘所有在线脏玩家，带总超时；返回失败/超时 uid 列表。
     */
    public ShutdownPersistResult persistAllOnlineSync(long timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(1L, timeoutMs));
        List<GameSession> sessions = new ArrayList<>(sessionManager.snapshotSessions());
        List<Long> failed = new ArrayList<>();
        List<Long> timedOut = new ArrayList<>();
        int succeeded = 0;
        for (GameSession session : sessions) {
            if (System.nanoTime() >= deadline) {
                timedOut.add(session.getUid());
                continue;
            }
            boolean ok = persistSync(session.getUid());
            if (ok) {
                succeeded++;
            } else if (sessionManager.getOrNull(session.getUid()) != null
                    && sessionManager.getOrNull(session.getUid()).isDirty()) {
                failed.add(session.getUid());
            } else {
                succeeded++;
            }
        }
        if (!failed.isEmpty() || !timedOut.isEmpty()) {
            log.error("shutdown persist summary: online={}, succeeded={}, failedUids={}, timedOutUids={}",
                    sessions.size(), succeeded, failed, timedOut);
        } else {
            log.info("shutdown persist summary: online={}, succeeded={}", sessions.size(), succeeded);
        }
        return new ShutdownPersistResult(sessions.size(), succeeded, List.copyOf(failed), List.copyOf(timedOut));
    }

    /**
     * @return true 表示无需落盘或落盘成功；false 表示失败
     */
    private boolean persistSync(long uid) {
        PersistSnapshot snapshot = sessionLockService.withLock(uid, () -> {
            GameSession session = sessionManager.getOrNull(uid);
            if (session == null || !session.isDirty()) {
                return null;
            }
            PlayerData data = session.getPlayerData();
            if (data == null || data.getPlayer() == null) {
                return null;
            }
            PlayerEntity player = data.deepCopy().getPlayer();
            player.setDataVersion(session.getDataVersion());
            return new PersistSnapshot(player, session.currentDirtyGeneration());
        });
        if (snapshot == null) {
            return true;
        }
        try {
            int updated = repository.persistPlayerSnapshot(snapshot.player());
            if (updated <= 0) {
                metrics.recordVersionConflict();
                metrics.recordPersistFail();
                log.error("shutdown persist version conflict, uid={}, expectedVersion={}",
                        uid, snapshot.player().getDataVersion());
                return false;
            }
            sessionLockService.withLock(uid, () -> {
                GameSession session = sessionManager.getOrNull(uid);
                if (session == null) {
                    return;
                }
                long expected = snapshot.player().getDataVersion() - 1;
                session.onPersistVersionAdvanced(expected, snapshot.player().getDataVersion());
                session.markPersisted(snapshot.dirtyGeneration());
            });
            return true;
        } catch (Exception e) {
            metrics.recordPersistFail();
            log.error("shutdown persist failed, uid={}", uid, e);
            return false;
        }
    }

    public void shutdownGracefully(long timeoutSeconds) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(timeoutSeconds, TimeUnit.SECONDS)) {
                executor.shutdownNow();
                executor.awaitTermination(timeoutSeconds, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private record PersistSnapshot(PlayerEntity player, long dirtyGeneration) {
    }
}
