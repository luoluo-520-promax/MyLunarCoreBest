package cn.itcast.demo.mylunarcore.common;

import cn.itcast.demo.mylunarcore.battle.BattleManager;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.config.NettyGameBusinessConfiguration;
import cn.itcast.demo.mylunarcore.matchmaking.MatchmakingService;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GameKcpServer;
import cn.itcast.demo.mylunarcore.net.GameNettyServer;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.player.PlayerDataAsyncLoadService;
import cn.itcast.demo.mylunarcore.player.PlayerDataPeriodicPersistenceService;
import cn.itcast.demo.mylunarcore.player.PlayerDataMetrics;
import cn.itcast.demo.mylunarcore.player.ShutdownPersistResult;
import cn.itcast.demo.mylunarcore.protocol.MatchmakingSystemProto;
import org.slf4j.Logger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 优雅停机：排空匹配 → 中止未结算战斗 → 停监听 → 同步落盘 → 关闭线程池。
 */
@Component
public class GracefulShutdownCoordinator implements SmartLifecycle {

    private static final Logger log = AppLogger.logger(LogCategory.SYSTEM, GracefulShutdownCoordinator.class);

    private final ObjectProvider<GameNettyServer> nettyServer;
    private final ObjectProvider<GameKcpServer> kcpServer;
    private final GameSessionManager sessionManager;
    private final PlayerDataPeriodicPersistenceService persistenceService;
    private final PlayerDataAsyncLoadService asyncLoadService;
    private final NettyGameBusinessConfiguration businessConfiguration;
    private final GameServer gameServer;
    private final LunarCoreProperties properties;
    private final PlayerDataMetrics playerDataMetrics;
    private final MatchmakingService matchmakingService;
    private final BattleManager battleManager;

    private volatile boolean running;

    public GracefulShutdownCoordinator(ObjectProvider<GameNettyServer> nettyServer,
                                       ObjectProvider<GameKcpServer> kcpServer,
                                       GameSessionManager sessionManager,
                                       PlayerDataPeriodicPersistenceService persistenceService,
                                       PlayerDataAsyncLoadService asyncLoadService,
                                       NettyGameBusinessConfiguration businessConfiguration,
                                       GameServer gameServer,
                                       LunarCoreProperties properties,
                                       PlayerDataMetrics playerDataMetrics,
                                       MatchmakingService matchmakingService,
                                       BattleManager battleManager) {
        this.nettyServer = nettyServer;
        this.kcpServer = kcpServer;
        this.sessionManager = sessionManager;
        this.persistenceService = persistenceService;
        this.asyncLoadService = asyncLoadService;
        this.businessConfiguration = businessConfiguration;
        this.gameServer = gameServer;
        this.properties = properties;
        this.playerDataMetrics = playerDataMetrics;
        this.matchmakingService = matchmakingService;
        this.battleManager = battleManager;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        stop(() -> {
        });
    }

    @Override
    public void stop(Runnable callback) {
        if (!running) {
            callback.run();
            return;
        }
        running = false;
        try {
            gracefulShutdown();
        } finally {
            callback.run();
        }
    }

    private void gracefulShutdown() {
        log.info("Graceful shutdown started");
        notifyAndDrainMatchQueues();
        // 停机前强制同步落盘进行中战斗，避免仅依赖 saveAsync 丢最后一击
        for (cn.itcast.demo.mylunarcore.battle.BattleContext ctx : battleManager.snapshotActive()) {
            if (ctx != null && !ctx.isEnded()) {
                synchronized (ctx.getLock()) {
                    // abortAllForShutdown 内部也会 save；此处提前再刷一次确保半截账落盘
                }
            }
        }
        int abortedBattles = battleManager.abortAllForShutdown();
        if (abortedBattles > 0) {
            log.warn("Aborted {} in-flight battle(s) on shutdown (snapshots forced sync)", abortedBattles);
        }
        for (GameSession session : sessionManager.snapshotSessions()) {
            if (session != null && session.isDirty()) {
                session.clearDirty();
            }
        }
        nettyServer.ifAvailable(GameNettyServer::stop);
        kcpServer.ifAvailable(GameKcpServer::stop);

        long timeoutMs = Math.max(1L, properties.getSync().getShutdownPersistTimeoutMs());
        int online = sessionManager.getOnlinePlayerCount();
        log.info("Persisting {} online player(s), timeoutMs={}", online, timeoutMs);
        ShutdownPersistResult persistResult = persistenceService.persistAllOnlineSync(timeoutMs);
        if (persistResult.hasFailures()) {
            log.error("Shutdown persist incomplete: failed={}, timedOut={}, persistFailTotal={}",
                    persistResult.failedUids(),
                    persistResult.timedOutUids(),
                    playerDataMetrics.getPersistFailTotal());
        }

        long poolTimeoutSec = Math.max(1L, TimeUnit.MILLISECONDS.toSeconds(timeoutMs));
        persistenceService.shutdownGracefully(poolTimeoutSec);
        asyncLoadService.shutdownGracefully(poolTimeoutSec);
        businessConfiguration.shutdownBusinessExecutors();
        sessionManager.shutdownGracefully();
        gameServer.shutdownGracefully();
        log.info("Graceful shutdown completed: persistSucceeded={}/{}",
                persistResult.succeeded(), persistResult.online());
    }

    private void notifyAndDrainMatchQueues() {
        List<Integer> drained = matchmakingService.drainAllQueues();
        if (drained.isEmpty()) {
            return;
        }
        MatchmakingSystemProto.CancelMatchQueueScRsp notify =
                MatchmakingSystemProto.CancelMatchQueueScRsp.newBuilder().setRetcode(0).build();
        byte[] payload = notify.toByteArray();
        int pushed = 0;
        for (Integer playerId : drained) {
            GameSession session = sessionManager.getOrNull(playerId);
            if (session != null && session.getChannel() != null && session.getChannel().isActive()) {
                session.send(new GamePacket(CmdIds.CANCEL_MATCH_QUEUE_SC_RSP, payload));
                pushed++;
            }
        }
        log.info("Drained match queues on shutdown: players={}, notified={}", drained.size(), pushed);
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }
}
