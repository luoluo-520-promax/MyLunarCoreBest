package cn.itcast.demo.mylunarcore.center;

import cn.itcast.demo.mylunarcore.battle.BattleSnapshotService;
import cn.itcast.demo.mylunarcore.common.AppLogger;
import cn.itcast.demo.mylunarcore.common.LogCategory;
import cn.itcast.demo.mylunarcore.scene.SceneContext;
import cn.itcast.demo.mylunarcore.scene.SceneManager;
import cn.itcast.demo.mylunarcore.scene.SceneSnapshotService;
import cn.itcast.demo.mylunarcore.scene.ZoneManager;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 玩家跨场景迁移编排：限流、重试、场景/战斗快照闭环后签发票据并离开源 Zone。
 */
@Service
public class PlayerMigrationService {

    private static final Logger log = AppLogger.logger(LogCategory.SYSTEM, PlayerMigrationService.class);

    /**
     * @param retcode 0=本机可进入；3=跨节点但缺少地址；4=跨节点重定向（带 ticket）；
     *                7=目标 Zone 排水中；8=迁移限流；9=重试耗尽
     */
    public record MigrationResult(boolean success, int retcode, int zoneId,
                                  String redirectHost, int redirectPort, String sessionTicket) {
        public static MigrationResult localOk(int zoneId) {
            return new MigrationResult(true, 0, zoneId, "", 0, "");
        }

        public static MigrationResult redirect(int zoneId, String host, int port, String ticket) {
            return new MigrationResult(false, 4, zoneId, host == null ? "" : host, port, ticket == null ? "" : ticket);
        }

        public static MigrationResult fail(int retcode, int zoneId) {
            return new MigrationResult(false, retcode, zoneId, "", 0, "");
        }
    }

    private final CenterServer centerServer;
    private final ZoneManager zoneManager;
    private final SceneManager sceneManager;
    private final MigrationTicketService ticketService;
    private final SceneRegistry sceneRegistry;
    private final SceneSnapshotService sceneSnapshotService;
    private final BattleSnapshotService battleSnapshotService;
    private final MigrationWriteFreezeService writeFreezeService;
    private final int maxPerSecond;
    private final int maxRetries;
    private final AtomicInteger windowCount = new AtomicInteger();
    private volatile long windowStartMs = System.currentTimeMillis();
    private final Map<Long, AtomicInteger> retryByPlayer = new ConcurrentHashMap<>();

    public PlayerMigrationService(CenterServer centerServer,
                                  ZoneManager zoneManager,
                                  SceneManager sceneManager,
                                  MigrationTicketService ticketService,
                                  SceneRegistry sceneRegistry,
                                  SceneSnapshotService sceneSnapshotService,
                                  BattleSnapshotService battleSnapshotService,
                                  MigrationWriteFreezeService writeFreezeService,
                                  @Value("${lunarcore.migration.max-per-second:200}") int maxPerSecond,
                                  @Value("${lunarcore.migration.max-retries:3}") int maxRetries) {
        this.centerServer = centerServer;
        this.zoneManager = zoneManager;
        this.sceneManager = sceneManager;
        this.ticketService = ticketService;
        this.sceneRegistry = sceneRegistry;
        this.sceneSnapshotService = sceneSnapshotService;
        this.battleSnapshotService = battleSnapshotService;
        this.writeFreezeService = writeFreezeService;
        this.maxPerSecond = Math.max(1, maxPerSecond);
        this.maxRetries = Math.max(1, maxRetries);
    }

    public MigrationResult migrate(long playerUid, int targetPlaneId, int targetFloorId) {
        return migrate(playerUid, targetPlaneId, targetFloorId, 0, 0f, 0f, 0f);
    }

    public MigrationResult migrate(long playerUid, int targetPlaneId, int targetFloorId,
                                   int entryId, float posX, float posY, float posZ) {
        if (!tryAcquireRate()) {
            return MigrationResult.fail(8, 0);
        }
        int attempts = 0;
        MigrationResult last = MigrationResult.fail(9, 0);
        while (attempts < maxRetries) {
            attempts++;
            last = doMigrateOnce(playerUid, targetPlaneId, targetFloorId, entryId, posX, posY, posZ);
            if (last.retcode() == 0 || last.retcode() == 4) {
                retryByPlayer.remove(playerUid);
                return last;
            }
            // 缺地址/排水/限流：不可重试
            if (last.retcode() == 3 || last.retcode() == 7 || last.retcode() == 8) {
                return last;
            }
            sleepBackoff(attempts);
        }
        AtomicInteger c = retryByPlayer.computeIfAbsent(playerUid, k -> new AtomicInteger());
        c.addAndGet(attempts);
        log.warn("migration retries exhausted uid={} lastRet={}", playerUid, last.retcode());
        return MigrationResult.fail(9, last.zoneId());
    }

    private MigrationResult doMigrateOnce(long playerUid, int targetPlaneId, int targetFloorId,
                                          int entryId, float posX, float posY, float posZ) {
        CenterServer.MigrationPlan plan = centerServer.planMigration(targetPlaneId, targetFloorId);
        SceneRegistry.ZoneInfo zoneInfo = sceneRegistry.find(plan.zoneId());
        if (zoneInfo != null && zoneInfo.draining()) {
            return MigrationResult.fail(7, plan.zoneId());
        }

        SceneContext ctx = sceneManager.getByPlayerUid(playerUid);

        if (centerServer.isLocalNode(plan.nodeId())) {
            return MigrationResult.localOk(plan.zoneId());
        }

        String host = plan.nodeHost();
        int port = plan.nodePort();
        if ((host == null || host.isBlank()) || port <= 0) {
            return MigrationResult.fail(3, plan.zoneId());
        }
        // 切服事务：先冻结写，再打迁移包，票据签发后目标节点 ack 解冻
        writeFreezeService.freeze(playerUid);
        float x = posX;
        float y = posY;
        float z = posZ;
        int entry = entryId;
        String battleSnap = "";
        String sceneSnap = "";
        try {
            if (ctx != null) {
                if (entry <= 0) {
                    entry = ctx.getEntryId();
                }
                if (x == 0f && y == 0f && z == 0f) {
                    x = ctx.getPlayerPos().getX();
                    y = ctx.getPlayerPos().getY();
                    z = ctx.getPlayerPos().getZ();
                }
                // 热数据优先：位置/状态先入迁移包；完整快照异步补全
                sceneSnap = "zone=" + ctx.getZoneId() + ";entry=" + entry
                        + ";x=" + x + ";y=" + y + ";z=" + z
                        + ";plane=" + ctx.getPlaneId() + ";floor=" + ctx.getFloorId();
                final SceneContext snapCtx = ctx;
                Thread t = new Thread(() -> {
                    try {
                        sceneSnapshotService.save(snapCtx);
                    } catch (Exception e) {
                        log.warn("async scene snapshot on migrate failed uid={}: {}", playerUid, e.toString());
                    }
                }, "migrate-scene-snap-" + playerUid);
                t.setDaemon(true);
                t.start();
                var battleOpt = battleSnapshotService.loadByPlayer((int) playerUid);
                if (battleOpt != null && battleOpt.isPresent()) {
                    battleSnap = String.valueOf(battleOpt.get().battleId());
                    log.debug("battle_snapshot present for migrate uid={} battleId={}",
                            playerUid, battleOpt.get().battleId());
                }
                zoneManager.leaveZone(ctx.getZoneId(), playerUid);
                sceneManager.remove(playerUid);
            }
            writeFreezeService.putPackage(new MigrationWriteFreezeService.MigrationPackage(
                    playerUid, System.currentTimeMillis(), "", battleSnap, sceneSnap, Map.of(
                    "targetHost", host,
                    "targetPort", String.valueOf(port),
                    "hotPayload", sceneSnap)));
            String ticket = ticketService.issue(playerUid, targetPlaneId, targetFloorId, entry, x, y, z);
            return MigrationResult.redirect(plan.zoneId(), host, port, ticket);
        } catch (RuntimeException e) {
            writeFreezeService.unfreeze(playerUid);
            throw e;
        }
    }

    private boolean tryAcquireRate() {
        long now = System.currentTimeMillis();
        if (now - windowStartMs >= 1000L) {
            windowStartMs = now;
            windowCount.set(0);
        }
        return windowCount.incrementAndGet() <= maxPerSecond;
    }

    private static void sleepBackoff(int attempt) {
        try {
            Thread.sleep(Math.min(200L, 40L * attempt));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    public MigrationTicketService.TicketPayload consumeTicket(String ticket) {
        MigrationTicketService.TicketPayload payload = ticketService.consume(ticket);
        if (payload != null) {
            // 目标节点确认接收迁移包后解冻写
            writeFreezeService.ackReceived(payload.playerUid());
        }
        return payload;
    }
}
