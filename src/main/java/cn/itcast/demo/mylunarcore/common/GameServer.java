package cn.itcast.demo.mylunarcore.common;

import cn.itcast.demo.mylunarcore.battle.BattleManager;
import cn.itcast.demo.mylunarcore.center.OnlinePresenceService;
import cn.itcast.demo.mylunarcore.center.SceneRegistry;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.scene.ZoneContext;
import cn.itcast.demo.mylunarcore.scene.ZoneManager;
import cn.itcast.demo.mylunarcore.scene.ZoneTickService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 游戏时钟：拆分 GlobalTick / ZoneTick / BattleTick。
 * <p>
 * 生产默认启用独立线程池（Scene-IO / Battle-CPU），避免 Zone AOI 挤占战斗出手。
 * Battle 积压超过 {@link BattleSceneThrottleService#BACKLOG_THRESHOLD_MS} 时下发 ThrottleScNotify。
 */
@Component
@Scope(ConfigurableBeanFactory.SCOPE_SINGLETON)
public class GameServer {

    private static final Logger log = AppLogger.logger(LogCategory.SYSTEM, GameServer.class);

    private final LunarCoreProperties properties;
    private final PlayerTickRegistry playerTickRegistry;
    private final ActivityScheduleService activityScheduleService;
    private final BattleManager battleManager;
    private final ZoneTickService zoneTickService;
    private final SceneRegistry sceneRegistry;
    private final ZoneManager zoneManager;
    private final OnlinePresenceService onlinePresenceService;
    private final BusinessMetrics businessMetrics;
    private final BattleSceneThrottleService throttleService;
    private final ScheduledExecutorService sceneTickExecutor;
    private final ScheduledExecutorService battleTickExecutor;

    private ScheduledExecutorService gameLoopExecutor;
    private volatile long lastGlobalTickMillis;
    private volatile long lastZoneTickMillis;
    private volatile long lastBattleTickMillis;
    private volatile long lastHeartbeatMillis;
    private final AtomicLong zonePeriodOverrideMs = new AtomicLong(0);

    public GameServer(LunarCoreProperties properties,
                      PlayerTickRegistry playerTickRegistry,
                      ActivityScheduleService activityScheduleService,
                      BattleManager battleManager,
                      ZoneTickService zoneTickService,
                      SceneRegistry sceneRegistry,
                      ZoneManager zoneManager,
                      OnlinePresenceService onlinePresenceService,
                      BusinessMetrics businessMetrics) {
        this(properties, playerTickRegistry, activityScheduleService, battleManager, zoneTickService,
                sceneRegistry, zoneManager, onlinePresenceService, businessMetrics,
                new BattleSceneThrottleService(null),
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "scene-tick-test");
                    t.setDaemon(true);
                    return t;
                }),
                java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread t = new Thread(r, "battle-tick-test");
                    t.setDaemon(true);
                    return t;
                }));
        properties.getGameLoop().setIsolatedTickPools(false);
    }

    public GameServer(LunarCoreProperties properties,
                      PlayerTickRegistry playerTickRegistry,
                      ActivityScheduleService activityScheduleService,
                      BattleManager battleManager,
                      ZoneTickService zoneTickService,
                      SceneRegistry sceneRegistry,
                      ZoneManager zoneManager,
                      OnlinePresenceService onlinePresenceService,
                      BusinessMetrics businessMetrics,
                      BattleSceneThrottleService throttleService,
                      @Qualifier("sceneTickExecutor") ScheduledExecutorService sceneTickExecutor,
                      @Qualifier("battleTickExecutor") ScheduledExecutorService battleTickExecutor) {
        this.properties = properties;
        this.playerTickRegistry = playerTickRegistry;
        this.activityScheduleService = activityScheduleService;
        this.battleManager = battleManager;
        this.zoneTickService = zoneTickService;
        this.sceneRegistry = sceneRegistry;
        this.zoneManager = zoneManager;
        this.onlinePresenceService = onlinePresenceService;
        this.businessMetrics = businessMetrics;
        this.throttleService = throttleService;
        this.sceneTickExecutor = sceneTickExecutor;
        this.battleTickExecutor = battleTickExecutor;
    }

    @PostConstruct
    public void start() {
        if (!properties.getGameLoop().isEnabled()) {
            log.info("Game loop disabled (lunarcore.game-loop.enabled=false)");
            return;
        }
        long globalPeriod = Math.max(50L, properties.getGameLoop().getPeriodMs());
        long zonePeriod = properties.getGameLoop().getZonePeriodMs();
        long battlePeriod = properties.getGameLoop().getBattlePeriodMs();
        long now = System.currentTimeMillis();
        lastGlobalTickMillis = now;
        lastZoneTickMillis = now;
        lastBattleTickMillis = now;
        lastHeartbeatMillis = now;

        boolean isolated = properties.getGameLoop().isIsolatedTickPools();
        if (isolated) {
            gameLoopExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "game-loop-global");
                t.setDaemon(true);
                return t;
            });
            gameLoopExecutor.scheduleWithFixedDelay(() -> {
                try {
                    long t0 = System.currentTimeMillis();
                    long delta = t0 - lastGlobalTickMillis;
                    lastGlobalTickMillis = t0;
                    onGlobalTick(t0, delta);
                } catch (Exception e) {
                    log.error("Global tick failed", e);
                }
            }, globalPeriod, globalPeriod, TimeUnit.MILLISECONDS);

            long zp = zonePeriod > 0 ? zonePeriod : 100L;
            sceneTickExecutor.scheduleWithFixedDelay(() -> {
                try {
                    long t0 = System.currentTimeMillis();
                    int hz = throttleService.resolveSceneHz(t0);
                    long effectivePeriod = Math.max(zp, 1000L / Math.max(1, hz));
                    zonePeriodOverrideMs.set(effectivePeriod);
                    if (t0 - lastZoneTickMillis >= effectivePeriod) {
                        long delta = t0 - lastZoneTickMillis;
                        lastZoneTickMillis = t0;
                        onZoneTick(t0, delta);
                    }
                } catch (Exception e) {
                    log.error("Zone tick failed", e);
                }
            }, zp, Math.max(20L, zp / 2), TimeUnit.MILLISECONDS);

            long bp = battlePeriod > 0 ? battlePeriod : 200L;
            battleTickExecutor.scheduleWithFixedDelay(() -> {
                try {
                    long enq = System.currentTimeMillis();
                    throttleService.markBattleEnqueued(enq);
                    long t0 = System.currentTimeMillis();
                    throttleService.markBattleStarted(t0);
                    if (t0 - lastBattleTickMillis >= bp) {
                        lastBattleTickMillis = t0;
                        onBattleTick(t0);
                    }
                } catch (Exception e) {
                    log.error("Battle tick failed", e);
                }
            }, bp, bp, TimeUnit.MILLISECONDS);

            log.info("GameServer isolated clocks: globalMs={}, zoneMs={}, battleMs={}",
                    globalPeriod, zonePeriod, battlePeriod);
        } else {
            long tickPeriod = Math.min(globalPeriod,
                    Math.min(zonePeriod > 0 ? zonePeriod : globalPeriod,
                            battlePeriod > 0 ? battlePeriod : globalPeriod));
            tickPeriod = Math.max(20L, tickPeriod);
            gameLoopExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "game-loop");
                t.setDaemon(true);
                return t;
            });
            gameLoopExecutor.scheduleWithFixedDelay(() -> {
                try {
                    onSchedulerPulse();
                } catch (Exception e) {
                    log.error("Game loop tick failed", e);
                }
            }, tickPeriod, tickPeriod, TimeUnit.MILLISECONDS);
            log.info("GameServer clocks started (shared): globalMs={}, zoneMs={}, battleMs={}, pulseMs={}",
                    globalPeriod, zonePeriod, battlePeriod, tickPeriod);
        }
    }

    public void shutdownGracefully() {
        if (gameLoopExecutor != null) {
            gameLoopExecutor.shutdown();
            try {
                if (!gameLoopExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    gameLoopExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                gameLoopExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            gameLoopExecutor = null;
        }
    }

    void onSchedulerPulse() {
        long now = System.currentTimeMillis();
        long globalPeriod = Math.max(50L, properties.getGameLoop().getPeriodMs());
        long zonePeriod = properties.getGameLoop().getZonePeriodMs();
        long battlePeriod = properties.getGameLoop().getBattlePeriodMs();

        if (now - lastGlobalTickMillis >= globalPeriod) {
            long delta = now - lastGlobalTickMillis;
            lastGlobalTickMillis = now;
            onGlobalTick(now, delta);
        }
        if (zonePeriod <= 0) {
            // 合并到 Global
        } else if (now - lastZoneTickMillis >= zonePeriod) {
            long delta = now - lastZoneTickMillis;
            lastZoneTickMillis = now;
            onZoneTick(now, delta);
        }
        if (battlePeriod <= 0) {
            // 合并到 Global
        } else if (now - lastBattleTickMillis >= battlePeriod) {
            lastBattleTickMillis = now;
            onBattleTick(now);
        }
    }

    void onGlobalTick(long now, long delta) {
        for (OnlinePlayer player : playerTickRegistry.snapshotOnlinePlayers()) {
            try {
                player.onTick(now, delta);
            } catch (Exception e) {
                log.warn("Player tick failed, uid={}, isolating error", player.getUid(), e);
            }
        }

        try {
            activityScheduleService.onTick(now, delta);
        } catch (Exception e) {
            log.warn("Activity schedule tick failed", e);
        }

        try {
            renewLocalZoneLeases(now);
            int evicted = sceneRegistry.evictExpired(now);
            if (evicted > 0) {
                log.info("SceneRegistry evicted {} expired zone lease(s)", evicted);
            }
        } catch (Exception e) {
            log.warn("SceneRegistry heartbeat/evict failed", e);
        }

        if (!properties.getGameLoop().isIsolatedTickPools()) {
            if (properties.getGameLoop().getZonePeriodMs() <= 0) {
                onZoneTick(now, delta);
            }
            if (properties.getGameLoop().getBattlePeriodMs() <= 0) {
                onBattleTick(now);
            }
        }
    }

    void onZoneTick(long now, long delta) {
        try {
            zoneTickService.onZoneTick(now, delta);
        } catch (Exception e) {
            log.warn("Zone tick failed", e);
        }
    }

    void onBattleTick(long now) {
        try {
            long ttl = properties.getGameLoop().getBattleTtlSeconds();
            if (ttl > 0) {
                int evicted = battleManager.evictExpired(now / 1000L, ttl);
                for (int i = 0; i < evicted; i++) {
                    businessMetrics.recordBattleTimeout();
                }
            }
            businessMetrics.setActiveBattles(battleManager.activeUnendedCount());
            businessMetrics.setZoneStats(sceneRegistry.size(), sceneRegistry.totalPlayerHint());
            int maxPlayers = Math.max(1, zoneManager.effectiveMaxPlayers() > 0
                    ? zoneManager.effectiveMaxPlayers()
                    : properties.getZone().getMaxPlayers());
            int zones = Math.max(1, sceneRegistry.size());
            double load = (double) sceneRegistry.totalPlayerHint() / (double) (zones * maxPlayers);
            businessMetrics.setZoneLoadRatio(load);
        } catch (Exception e) {
            log.warn("Battle TTL eviction failed", e);
        }
    }

    private void renewLocalZoneLeases(long now) {
        long heartbeatMs = properties.getCenter().getHeartbeatMs();
        if (heartbeatMs <= 0) {
            heartbeatMs = 3_000L;
        }
        if (now - lastHeartbeatMillis < heartbeatMs) {
            return;
        }
        lastHeartbeatMillis = now;
        String nodeId = properties.getCenter().getLocalNodeId();
        if (nodeId == null || nodeId.isBlank()) {
            nodeId = "local";
        }
        for (ZoneContext zone : zoneManager.snapshotZones()) {
            sceneRegistry.heartbeat(zone.getZoneId(), nodeId, zone.getPlayerUids().size());
        }
        if (log.isDebugEnabled()) {
            log.debug("onlinePresence count={}", onlinePresenceService.onlineCount());
        }
    }
}
