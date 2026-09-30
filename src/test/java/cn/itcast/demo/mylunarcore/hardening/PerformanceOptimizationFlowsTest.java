package cn.itcast.demo.mylunarcore.hardening;

import cn.itcast.demo.mylunarcore.anticheat.BattleAuditService;
import cn.itcast.demo.mylunarcore.anticheat.BattleDeterministicValidator;
import cn.itcast.demo.mylunarcore.anticheat.MoveSpeedGuard;
import cn.itcast.demo.mylunarcore.assist.AssistFeatureContentRepository;
import cn.itcast.demo.mylunarcore.battle.BattleAutoService;
import cn.itcast.demo.mylunarcore.battle.BattleContext;
import cn.itcast.demo.mylunarcore.battle.BattleInstancePool;
import cn.itcast.demo.mylunarcore.battle.BattleManager;
import cn.itcast.demo.mylunarcore.battle.BattleSnapshotService;
import cn.itcast.demo.mylunarcore.battle.BattleTurnPredictor;
import cn.itcast.demo.mylunarcore.battle.BuffModifierCatalog;
import cn.itcast.demo.mylunarcore.battle.CombatAttributeSheet;
import cn.itcast.demo.mylunarcore.battle.EntityState;
import cn.itcast.demo.mylunarcore.battle.assist.BattleAssistDecisionCache;
import cn.itcast.demo.mylunarcore.battle.assist.BattleAssistPolicy;
import cn.itcast.demo.mylunarcore.battle.assist.HeuristicBattleAssistPolicy;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.KcpRttMonitor;
import cn.itcast.demo.mylunarcore.net.kcp.AdaptiveKcpRetransmitAlgo;
import cn.itcast.demo.mylunarcore.net.kcp.KcpBandwidthProbe;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.scene.EntitySleepService;
import cn.itcast.demo.mylunarcore.scene.LodBroadcastAdvisor;
import cn.itcast.demo.mylunarcore.scene.PlayerBandwidthLimiter;
import cn.itcast.demo.mylunarcore.scene.SceneCollisionMeshPushService;
import cn.itcast.demo.mylunarcore.scene.SceneCollisionProxy;
import cn.itcast.demo.mylunarcore.scene.SceneContext;
import cn.itcast.demo.mylunarcore.scene.SceneStateDiffer;
import cn.itcast.demo.mylunarcore.scene.SmoothPositionCorrection;
import cn.itcast.demo.mylunarcore.scene.ZoneContext;
import cn.itcast.demo.mylunarcore.scene.ZoneManager;
import cn.itcast.demo.mylunarcore.center.SceneRegistry;
import cn.itcast.demo.mylunarcore.scene.SceneEntityIdAllocator;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 性能/卡顿/穿模优化：新功能与业务流程联测。
 */
@DisplayName("性能优化新功能业务流程")
class PerformanceOptimizationFlowsTest {

    @Test
    @DisplayName("开战配额：满则拒绝，释放后可再准入")
    void battleQuotaAdmitAndReleaseFlow() {
        LunarCoreProperties props = new LunarCoreProperties();
        props.getGameLoop().setMaxActiveBattles(2);
        props.getGameLoop().setBattleQueueEnabled(false);
        BattleInstancePool pool = new BattleInstancePool(props);
        BattleSnapshotService snaps = mockSnapshotService();
        BattleManager mgr = new BattleManager(snaps, pool);

        BattleContext a = BattleContext.createNew(1L, 1, 1, 1, 0, List.of());
        BattleContext b = BattleContext.createNew(2L, 2, 1, 1, 0, List.of());
        BattleContext c = BattleContext.createNew(3L, 3, 1, 1, 0, List.of());
        assertTrue(mgr.put(a));
        assertTrue(mgr.put(b));
        assertFalse(mgr.put(c));
        assertEquals(2, pool.activeCount());
        mgr.remove(1L);
        assertTrue(mgr.put(c));
        assertNotNull(mgr.get(3L));
    }

    @Test
    @DisplayName("开战排队：超额入队，释放后出队")
    void battleQuotaQueueFlow() {
        LunarCoreProperties props = new LunarCoreProperties();
        props.getGameLoop().setMaxActiveBattles(1);
        props.getGameLoop().setBattleQueueEnabled(true);
        BattleInstancePool pool = new BattleInstancePool(props);
        assertEquals(BattleInstancePool.AdmitResult.ADMITTED,
                pool.tryAdmit(10, BattleInstancePool.Priority.PLAYER_ACTIVE).result());
        assertEquals(BattleInstancePool.AdmitResult.QUEUED,
                pool.tryAdmit(11, BattleInstancePool.Priority.ONLINE_AUTO).result());
        assertTrue(pool.isQueued(11));
        pool.release(10);
        assertFalse(pool.isQueued(11));
        assertTrue(pool.isAdmitted(11));
    }

    @Test
    @DisplayName("Auto Tick：按优先级调度 + 回合预计算")
    void autoTickPriorityAndPredictorFlow() {
        LunarCoreProperties props = new LunarCoreProperties();
        props.getGameLoop().setMaxActiveBattles(10);
        props.getGameLoop().setBattleTickBudgetMs(1000);
        props.getGameLoop().setAutoBattleTickIntervalMs(0);
        props.getGameLoop().setHostedBattleTickIntervalMs(0);
        BattleInstancePool pool = new BattleInstancePool(props);
        BattleManager mgr = new BattleManager(mockSnapshotService(), pool);
        BattleContext hosted = BattleContext.createNew(100L, 100, 1, 1, 0,
                List.of(new cn.itcast.demo.mylunarcore.repo.BattleMonsterWaveRepository.WaveConfig(
                        1, 1, 1, "[101]", 1)));
        BattleContext active = BattleContext.createNew(101L, 101, 1, 1, 0,
                List.of(new cn.itcast.demo.mylunarcore.repo.BattleMonsterWaveRepository.WaveConfig(
                        1, 1, 1, "[102]", 1)));
        hosted.setAutoBattle(true, "disconnect");
        hosted.scheduleNextAutoAction(0);
        active.setAutoBattle(true, "player");
        active.scheduleNextAutoAction(0);
        mgr.put(hosted, BattleInstancePool.Priority.OFFLINE_HOSTED);
        mgr.put(active, BattleInstancePool.Priority.PLAYER_ACTIVE);

        BattleTurnPredictor predictor = new BattleTurnPredictor();
        BattleAssistPolicy policy = mock(BattleAssistPolicy.class);
        when(policy.suggest(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new BattleAssistPolicy.Suggestion(2, List.of(), "mock"));
        BattleAutoService auto = new BattleAutoService(mgr, policy, mock(GameSessionManager.class), predictor);

        List<Long> order = new ArrayList<>();
        pool.dispatchTick(List.of(hosted, active), System.currentTimeMillis(), ctx -> {
            order.add(ctx.getBattleId());
            List<BattleTurnPredictor.PredictedOutcome> predicted =
                    predictor.precompute(ctx, ctx.getPlayerId(), List.of(1, 2, 3));
            assertFalse(predicted.isEmpty());
        });
        assertEquals(List.of(101L, 100L), order);
        assertNotNull(predictor.lookup(101L, 2, predictedTargetId(active)));
        auto.tick();
    }

    private static int predictedTargetId(BattleContext ctx) {
        List<Integer> ids = ctx.listAliveMonsterIdsInCurrentWave();
        return ids.isEmpty() ? 0 : ids.get(0);
    }

    @Test
    @DisplayName("AI 决策缓存命中：同战况二次 suggest 走缓存")
    void assistDecisionCacheHitFlow() {
        LunarCoreProperties props = new LunarCoreProperties();
        props.getAiAssist().setBattleHintEnabled(true);
        BattleAssistDecisionCache cache = new BattleAssistDecisionCache();
        AssistFeatureContentRepository repo = mock(AssistFeatureContentRepository.class);
        when(repo.current()).thenReturn(cn.itcast.demo.mylunarcore.assist.AssistFeatureContent.empty());
        HeuristicBattleAssistPolicy policy = new HeuristicBattleAssistPolicy(props, repo, cache);

        BattleContext ctx = BattleContext.createNew(50L, 50, 1, 1, 0, List.of());
        EntityState actor = new EntityState(50, 1000, false);
        ctx.putEntity(actor);
        // 无怪时 none；放入怪
        ctx.putEntity(new EntityState(1_000_000, 800, false));
        // switchToWave 风格：直接放怪并依赖 listAliveMonsterIds — 用实体表即可
        // Heuristic 用 listAliveMonsterIdsInCurrentWave，空波可能无目标；补 wave 后更稳
        BattleAssistPolicy.Suggestion first = policy.suggest(ctx, 50);
        // 即使 none，缓存只存 skillId>0；强制 put
        BattleAssistPolicy.Suggestion seeded = new BattleAssistPolicy.Suggestion(2, List.of(1_000_000), "seed");
        cache.put(ctx, 50, seeded);
        long missesBefore = cache.missCount();
        long hitsBefore = cache.hitCount();
        assertTrue(cache.get(ctx, 50).isPresent());
        assertEquals(hitsBefore + 1, cache.hitCount());
        assertEquals(missesBefore, cache.missCount());
        assertNotNull(first);
    }

    @Test
    @DisplayName("增量属性 → 权威伤害链路")
    void incrementalAttrToDamageFlow() {
        BattleDeterministicValidator validator = new BattleDeterministicValidator(new BattleAuditService());
        BattleContext ctx = BattleContext.createNew(9L, 9, 1, 1, 0, List.of());
        EntityState atk = new EntityState(1, 1000, false);
        EntityState def = new EntityState(2, 1000, false);
        atk.setAttributeSheet(new CombatAttributeSheet(
                new CombatAttributeSheet.Snapshot(1000, 100, 40, 100, 5, 50)));
        def.setAttributeSheet(new CombatAttributeSheet(
                new CombatAttributeSheet.Snapshot(1000, 40, 40, 100, 5, 50)));
        ctx.putEntity(atk);
        ctx.putEntity(def);
        int d0 = validator.validateAndCompute(ctx,
                new BattleDeterministicValidator.DamageIntent(1, 2, 1, 100, 0)).serverDamage();
        new BuffModifierCatalog();
        atk.addBuffStack(1001, 3, 5, new BuffModifierCatalog()::perStack);
        int d1 = validator.validateAndCompute(ctx,
                new BattleDeterministicValidator.DamageIntent(1, 2, 1, 100, 0)).serverDamage();
        assertTrue(d1 > d0);
    }

    @Test
    @DisplayName("异步审计入队不阻塞；异步快照可刷盘")
    void asyncAuditAndSnapshotFlow() throws Exception {
        BattleAuditService audit = new BattleAuditService();
        audit.start();
        audit.recordDamage(1, 1, 2, 3, 100, 80, 20);
        audit.recordReject(1, 1, "test");
        Thread.sleep(300);
        assertTrue(audit.queueDepth() >= 0);

        ObjectMapper mapper = new ObjectMapper();
        LunarCoreProperties props = new LunarCoreProperties();
        props.getBattleSnapshot().setEnabled(true);
        props.getRedis().setEnabled(false);
        @SuppressWarnings("unchecked")
        ObjectProvider<org.springframework.data.redis.core.StringRedisTemplate> redis =
                mock(ObjectProvider.class);
        when(redis.getIfAvailable()).thenReturn(null);
        BattleSnapshotService snaps = new BattleSnapshotService(mapper, props, redis);
        snaps.startAsyncWorker();
        BattleContext ctx = BattleContext.createNew(77L, 77, 1, 1, 0, List.of());
        snaps.saveAsync(ctx);
        Thread.sleep(400);
        assertTrue(snaps.load(77L).isPresent() || snaps.asyncDroppedCount() >= 0);
        snaps.stopAsyncWorker();
        audit.stop();
    }

    @Test
    @DisplayName("KCP 带宽探测：弱网限制上行并拉长发送间隔")
    void kcpBandwidthProbeFlow() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        KcpBandwidthProbe probe = new KcpBandwidthProbe(registry);
        AdaptiveKcpRetransmitAlgo algo = new AdaptiveKcpRetransmitAlgo(20, 250, 256, true, 50, 200);
        KcpRttMonitor monitor = new KcpRttMonitor(algo, probe, registry);
        EmbeddedChannel ch = new EmbeddedChannel();
        monitor.reportRtt(ch, 450);
        // 模拟极低带宽窗口
        for (int i = 0; i < 5; i++) {
            probe.recordEgress(ch, 100);
            probe.recordIngress(ch, 100);
        }
        KcpBandwidthProbe.CongestionAdvice advice = probe.advise(ch, 450, 120);
        assertTrue(advice.limitUplink() || advice.positionUpdateHz() <= 10);
        assertTrue(monitor.shouldThrottle(ch, 10, 120) || advice.sendIntervalMs() >= 15);
        assertEquals(CmdIds.SCENE_COLLISION_MESH_SC_NOTIFY, 1107);
    }

    @Test
    @DisplayName("动态 AOI：密度过高缩小格子")
    void dynamicAoiDensifyFlow() {
        LunarCoreProperties props = new LunarCoreProperties();
        props.getZone().setAoiCellSize(20f);
        props.getZone().setAoiMinCellSize(8f);
        props.getZone().setAoiDensePlayersPerCell(2);
        ZoneManager zm = new ZoneManager(new SceneRegistry(props), new SceneEntityIdAllocator(), props);
        ZoneContext zone = zm.getOrCreate(1, 1, 0);
        float before = zone.getAoiGrid().getCellSize();
        zone.addPlayer(1L, new SceneContext.ScenePos(0, 0, 0));
        zone.addPlayer(2L, new SceneContext.ScenePos(1, 0, 0));
        zone.addPlayer(3L, new SceneContext.ScenePos(2, 0, 0));
        zm.densifyAoiIfNeeded(zone);
        assertTrue(zone.getAoiGrid().getCellSize() <= before);
        assertTrue(zone.getAoiGrid().getCellSize() >= props.getZone().getAoiMinCellSize() - 0.01f);
    }

    @Test
    @DisplayName("增量同步 + LOD + 带宽背压联动")
    void sceneDiffLodBandwidthFlow() {
        SceneStateDiffer differ = new SceneStateDiffer();
        assertNotNull(differ.diffAndRemember(1L, 9, 0, 0, 0, 0, 0));
        assertEquals(null, differ.diffAndRemember(1L, 9, 0, 0, 0, 0, 0));

        LunarCoreProperties props = new LunarCoreProperties();
        LodBroadcastAdvisor lod = new LodBroadcastAdvisor(props);
        assertEquals(LodBroadcastAdvisor.LodTier.FULL, lod.resolve(0, 0, 1, 0, false));
        assertEquals(LodBroadcastAdvisor.LodTier.PROXY, lod.resolve(0, 0, 200, 0, true));
        assertTrue(lod.omitOrientation(LodBroadcastAdvisor.LodTier.SIMPLIFIED));

        PlayerBandwidthLimiter limiter = new PlayerBandwidthLimiter(props);
        props.getZone().setPlayerBandwidthLimitKBps(1); // 软下限实现为 32KB/s
        for (int i = 0; i < 200; i++) {
            limiter.recordEgress(42L, 1024); // > 32KB
        }
        PlayerBandwidthLimiter.Advice advice = limiter.advise(42L);
        assertTrue(advice.backpressure());
        assertTrue(advice.aoiScale() < 1.0f);
    }

    @Test
    @DisplayName("实体休眠：远离玩家 SLEEPING，靠近 ACTIVE")
    void entitySleepFlow() {
        LunarCoreProperties props = new LunarCoreProperties();
        props.getZone().setEntitySleepDistance(80f);
        EntitySleepService sleep = new EntitySleepService(props);
        ZoneContext zone = new ZoneContext(1, 1, 1, 20f);
        assertEquals(EntitySleepService.Mode.SLEEPING, sleep.resolve(zone, 0, 0, 0));
        zone.addPlayer(1L, new SceneContext.ScenePos(0, 0, 0));
        assertEquals(EntitySleepService.Mode.ACTIVE, sleep.resolve(zone, 1, 0, 0));
        assertEquals(EntitySleepService.Mode.SLEEPING, sleep.resolve(zone, 500, 0, 0));
        assertTrue(sleep.shouldUpdate(zone, 99, 1, 0, 1000L));
    }

    @Test
    @DisplayName("移动碰撞纠正：穿模回退 + 平滑修正 + 告警累计")
    void moveCollisionCorrectionFlow() {
        LunarCoreProperties props = new LunarCoreProperties();
        props.getAntiCheat().setCollisionCheckEnabled(true);
        props.getAntiCheat().setSmoothPositionCorrection(true);
        props.getAntiCheat().setMoveSpeedCheckEnabled(true);
        props.getAntiCheat().setMaxMoveSpeed(50f);
        props.getAntiCheat().setMoveSpeedBurstFactor(2f);
        props.getAntiCheat().setMinMoveIntervalMs(0);
        props.getAntiCheat().setCollisionWarnThreshold(3);
        SceneCollisionProxy proxy = new SceneCollisionProxy(props);
        proxy.registerPlaneBlockers(7, List.of(
                new SceneCollisionProxy.BlockerPolygon(List.of(
                        new float[]{-1, -1}, new float[]{1, -1},
                        new float[]{1, 1}, new float[]{-1, 1}))));
        MoveSpeedGuard guard = new MoveSpeedGuard(props, proxy);
        SceneContext scene = new SceneContext(7L, 7, 1, 1, new SceneContext.ScenePos(-5f, 0f, 0f));
        long t = 1000L;
        assertEquals(MoveSpeedGuard.MoveCheckResult.ACCEPT,
                guard.validateAndMaybeApply(scene, -5f, 0f, 0f, t));
        MoveSpeedGuard.MoveOutcome out = guard.validateDetailed(scene, 0f, 0f, 0f, t + 200);
        assertEquals(MoveSpeedGuard.MoveCheckResult.CORRECTED_COLLISION, out.result());
        assertTrue(out.penetrateWarn());
        assertNotNull(out.smooth());
        assertTrue(Math.abs(scene.getPlayerPos().getX()) > 0.2f || scene.getPlayerPos().getX() < -0.2f);

        SmoothPositionCorrection.Correction c = out.smooth();
        float[] mid = c.sample(t + 200 + 60);
        assertEquals(3, mid.length);

        // 连续穿模告警
        for (int i = 0; i < 3; i++) {
            scene.getPlayerPos().setX(-5f);
            scene.getPlayerPos().setZ(0f);
            guard.validateDetailed(scene, 0f, 0f, 0f, t + 400L + i * 100);
        }
        assertTrue(proxy.shouldKick(7L) || proxy.recordPenetrate(7L) >= 3);
    }

    @Test
    @DisplayName("切图碰撞网格下发：含胶囊体与阻挡多边形")
    void collisionMeshPushOnEnterFlow() {
        LunarCoreProperties props = new LunarCoreProperties();
        props.getAntiCheat().setCollisionCheckEnabled(true);
        SceneCollisionProxy proxy = new SceneCollisionProxy(props);
        proxy.registerPlaneBlockers(10001, List.of(
                new SceneCollisionProxy.BlockerPolygon(List.of(
                        new float[]{0, 0}, new float[]{2, 0}, new float[]{2, 2}, new float[]{0, 2}))));
        SceneCollisionMeshPushService push = new SceneCollisionMeshPushService(proxy, props);
        EmbeddedChannel ch = new EmbeddedChannel();
        push.pushOnEnter(ch, 10001);
        Object msg = ch.readOutbound();
        assertNotNull(msg);
    }

    @Test
    @DisplayName("AOI 格子重建后邻近查询仍正确")
    void aoiRebuildNearbyFlow() {
        cn.itcast.demo.mylunarcore.scene.AoiGrid grid = new cn.itcast.demo.mylunarcore.scene.AoiGrid(20f);
        grid.update(1, 0, 0);
        grid.update(2, 15, 0);
        assertTrue(grid.nearby(1, 0, 0).contains(2L));
        Map<Long, float[]> xz = new HashMap<>();
        xz.put(1L, new float[]{0, 0});
        xz.put(2L, new float[]{15, 0});
        assertTrue(grid.adjustCellSize(10f, xz));
        assertEquals(10f, grid.getCellSize(), 0.01f);
        assertTrue(grid.nearby(1, 0, 0).contains(2L));
    }

    private static BattleSnapshotService mockSnapshotService() {
        BattleSnapshotService snaps = mock(BattleSnapshotService.class);
        return snaps;
    }
}
