// 在独立线程池中异步加载玩家数据并回填在线会话
package cn.itcast.demo.mylunarcore.player;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.model.PlayerData;
import cn.itcast.demo.mylunarcore.common.AppLogger;
import cn.itcast.demo.mylunarcore.common.LogCategory;
import cn.itcast.demo.mylunarcore.repo.PlayerDataRepository;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 玩家数据异步加载服务。
 * <p>
 * 区分三种加载策略：
 * <ul>
 *   <li>LOGIN / ALL — 全量 SQL 加载，登录首包后补全完整 PlayerData</li>
 *   <li>DATA_CHANGE + scope — 在内存副本上 merge 指定子表，减少 IO</li>
 *   <li>TIMER — 优先命中 {@link PlayerDataCacheService}，未命中再查库</li>
 * </ul>
 * 通过 {@link GameSession#dataLoadVersion} 版本号丢弃过期的异步结果；拒绝策略为 CallerRuns。
 */
@Service
public class PlayerDataAsyncLoadService {

    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_DATA, PlayerDataAsyncLoadService.class);

    private final PlayerDataRepository repository;
    private final PlayerSyncCoordinator playerSyncCoordinator;
    private final GameSessionManager sessionManager;
    private final PlayerSessionLockService sessionLockService;
    private final PlayerDataCacheService cacheService;
    private final PlayerDataMetrics metrics;
    private final ThreadPoolExecutor executor;
    private final int queueCapacity;

    public PlayerDataAsyncLoadService(PlayerDataRepository repository,
                                      PlayerSyncCoordinator playerSyncCoordinator,
                                      GameSessionManager sessionManager,
                                      PlayerSessionLockService sessionLockService,
                                      PlayerDataCacheService cacheService,
                                      LunarCoreProperties properties,
                                      PlayerDataMetrics metrics) {
        this.repository = repository;
        this.playerSyncCoordinator = playerSyncCoordinator;
        this.sessionManager = sessionManager;
        this.sessionLockService = sessionLockService;
        this.cacheService = cacheService;
        this.metrics = metrics;
        int cpu = Runtime.getRuntime().availableProcessors();
        int configuredThreads = properties.getSync().getAsyncLoadThreads();
        int threads = configuredThreads > 0 ? configuredThreads : Math.max(4, cpu);
        int configuredQueue = properties.getSync().getAsyncLoadQueueCapacity();
        queueCapacity = configuredQueue > 0 ? configuredQueue : 1024;
        this.executor = new ThreadPoolExecutor(
                threads,
                threads,
                30L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity),
                runnable -> {
                    Thread t = new Thread(runnable, "player-data-async-loader");
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    public void reloadFullAsync(long uid, SyncReason reason) {
        DataChangeScope scope = reason == SyncReason.LOGIN ? DataChangeScope.ALL : null;
        reloadAsync(uid, reason, scope);
    }

    public void reloadAsync(long uid, SyncReason reason, DataChangeScope scope) {
        GameSession session = sessionManager.getOrNull(uid);
        if (session == null) {
            return;
        }
        if (reason == SyncReason.DATA_CHANGE && scope == null) {
            log.warn("DATA_CHANGE without scope defaults to ALL, uid={}", uid);
            scope = DataChangeScope.ALL;
        }
        long loadVersion = session.nextDataLoadVersion();
        warnIfQueueHigh(uid, reason);
        try {
            DataChangeScope finalScope = scope;
            executor.execute(() -> doLoad(uid, session, loadVersion, reason, finalScope));
        } catch (RejectedExecutionException e) {
            metrics.recordReloadReject();
            log.warn("async load rejected, uid={}, reason={}", uid, reason, e);
        }
    }

    private void warnIfQueueHigh(long uid, SyncReason reason) {
        int queueSize = executor.getQueue().size();
        if (queueSize >= queueCapacity * 8 / 10) {
            log.warn("player-data-async-loader queue depth high: {}/{}, uid={}, reason={}",
                    queueSize, queueCapacity, uid, reason);
        }
    }

    private void doLoad(long uid, GameSession expectedSession, long loadVersion,
                        SyncReason reason, DataChangeScope scope) {
        try {
            if (reason == SyncReason.TIMER) {
                var cached = cacheService.get(uid);
                if (cached.isPresent() && applyIfSessionValid(uid, expectedSession, loadVersion, cached.get(), reason)) {
                    return;
                }
            }

            PlayerData data = loadFromDatabase(uid, reason, scope, expectedSession);
            if (data == null) {
                return;
            }

            applyIfSessionValid(uid, expectedSession, loadVersion, data, reason);
        } catch (Exception e) {
            metrics.recordReloadFail();
            log.warn("async load player data failed, uid={}, reason={}", uid, reason, e);
        }
    }

    private PlayerData loadFromDatabase(long uid, SyncReason reason, DataChangeScope scope, GameSession session) {
        if (reason == SyncReason.LOGIN || scope == null || scope == DataChangeScope.ALL) {
            return repository.loadAllData(uid);
        }
        PlayerData base = session.getPlayerData();
        if (base == null) {
            return repository.loadAllData(uid);
        }
        PlayerData working = base.deepCopy();
        repository.mergeScope(working, uid, scope);
        return working;
    }

    private boolean applyIfSessionValid(long uid, GameSession expectedSession, long loadVersion,
                                        PlayerData data, SyncReason reason) {
        GameSession latest = sessionManager.getOrNull(uid);
        if (latest == null || latest != expectedSession) {
            return false;
        }
        if (!latest.isCurrentDataLoadVersion(loadVersion)) {
            return false;
        }

        sessionLockService.withLock(uid, () -> {
            // 内存有未落盘脏数据时，拒绝用 DB 快照覆盖，避免丢档
            if (latest.isDirty() && reason != SyncReason.LOGIN) {
                log.warn("skip async load apply due to dirty session, uid={}, reason={}", uid, reason);
                return;
            }
            PlayerData existing = latest.getPlayerData();
            if (existing != null && existing.getPlayer() != null && reason != SyncReason.LOGIN) {
                mergeNonCoreSlices(existing, data);
                latest.setPlayerData(existing);
                if (existing.getPlayer() != null) {
                    latest.bindDataVersion(existing.getPlayer().getDataVersion());
                }
                cacheService.put(uid, existing.deepCopy());
                playerSyncCoordinator.pushToSession(latest, existing, reason);
            } else {
                latest.setPlayerData(data);
                if (data.getPlayer() != null) {
                    latest.bindDataVersion(data.getPlayer().getDataVersion());
                }
                latest.clearDirty();
                cacheService.put(uid, data.deepCopy());
                playerSyncCoordinator.pushToSession(latest, data, reason);
            }
        });
        return true;
    }

    private static void mergeNonCoreSlices(PlayerData target, PlayerData loaded) {
        if (loaded.getAvatars() != null) {
            target.setAvatars(loaded.getAvatars());
        }
        if (loaded.getLineups() != null) {
            target.setLineups(loaded.getLineups());
        }
        if (loaded.getFriends() != null) {
            target.setFriends(loaded.getFriends());
        }
        if (loaded.getChallenges() != null) {
            target.setChallenges(loaded.getChallenges());
        }
        if (loaded.getRogues() != null) {
            target.setRogues(loaded.getRogues());
        }
        if (loaded.getItems() != null) {
            target.setItems(loaded.getItems());
        }
        if (loaded.getPlayer() != null) {
            target.setPlayer(loaded.getPlayer());
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
}
