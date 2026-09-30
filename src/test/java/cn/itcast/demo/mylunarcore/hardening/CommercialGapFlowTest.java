package cn.itcast.demo.mylunarcore.hardening;

import cn.itcast.demo.mylunarcore.achievement.OfflineAchievementCompensator;
import cn.itcast.demo.mylunarcore.anticheat.BattleActionIntegrityGuard;
import cn.itcast.demo.mylunarcore.anticheat.BattleAuditService;
import cn.itcast.demo.mylunarcore.battle.BattleContext;
import cn.itcast.demo.mylunarcore.battle.BattleManager;
import cn.itcast.demo.mylunarcore.battle.BattleSnapshotService;
import cn.itcast.demo.mylunarcore.battle.assist.AiBattleStrategyProxy;
import cn.itcast.demo.mylunarcore.battle.assist.HeuristicBattleAssistPolicy;
import cn.itcast.demo.mylunarcore.common.ClientResourceManifestService;
import cn.itcast.demo.mylunarcore.common.ConfigDeltaPatchService;
import cn.itcast.demo.mylunarcore.common.HotfixData;
import cn.itcast.demo.mylunarcore.common.HotfixDataService;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.economy.WalletApplicationService;
import cn.itcast.demo.mylunarcore.economy.iap.IapOrderService;
import cn.itcast.demo.mylunarcore.economy.iap.IapRefundWebhookController;
import cn.itcast.demo.mylunarcore.economy.iap.NegativeBalanceFreezeService;
import cn.itcast.demo.mylunarcore.infra.db.DataSourceType;
import cn.itcast.demo.mylunarcore.infra.db.PtLoadRoutingFilter;
import cn.itcast.demo.mylunarcore.infra.db.ReadWriteRouter;
import cn.itcast.demo.mylunarcore.infra.db.RoutingDataSourceContext;
import cn.itcast.demo.mylunarcore.infra.db.UidShardRouter;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.net.PacketPriority;
import cn.itcast.demo.mylunarcore.net.PacketPriorityOutboundHandler;
import cn.itcast.demo.mylunarcore.ops.AlertWebhookNotifier;
import cn.itcast.demo.mylunarcore.ops.BattleLogSampler;
import cn.itcast.demo.mylunarcore.ops.MaintenanceModeService;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.scene.AoiAsyncProcessor;
import cn.itcast.demo.mylunarcore.scene.DynamicZoneLineAllocator;
import cn.itcast.demo.mylunarcore.scene.SceneContext;
import cn.itcast.demo.mylunarcore.scene.SceneEntityIdAllocator;
import cn.itcast.demo.mylunarcore.scene.ZoneActorMailbox;
import cn.itcast.demo.mylunarcore.scene.ZoneManager;
import cn.itcast.demo.mylunarcore.center.SceneRegistry;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 商业缺口新功能与业务流程全量回归：性能 / 玩法 / 运维三块。
 */
@DisplayName("商业缺口新功能与业务流程")
class CommercialGapFlowTest {

    @AfterEach
    void clearRouting() {
        RoutingDataSourceContext.clear();
    }

    @Nested
    @DisplayName("性能：Zone / AOI / 包优先级 / DB 路由 / 配置 Delta")
    class PerformanceFlows {

        @Test
        void dynamicLineOpensWhenPrimaryFull() {
            LunarCoreProperties props = zoneProps(1, 3);
            ZoneManager zones = new ZoneManager(new SceneRegistry(), new SceneEntityIdAllocator(), props);
            DynamicZoneLineAllocator allocator = new DynamicZoneLineAllocator(zones, props);

            assertEquals(0, allocator.pickJoinableLine(10, 1, 1L));
            zones.joinZone(10, 1, 0, 1L, new SceneContext.ScenePos(0, 0, 0));
            assertEquals(1, allocator.pickJoinableLine(10, 1, 2L));
            zones.joinZone(10, 1, 1, 2L, new SceneContext.ScenePos(1, 0, 1));
            assertEquals(2, allocator.pickJoinableLine(10, 1, 3L));
        }

        @Test
        void sceneContextKeepsLineEncodedZoneId() {
            int zoneId = DynamicZoneLineAllocator.encodeZoneId(2, 3, 1);
            SceneContext ctx = new SceneContext(99L, 2, 3, 0, new SceneContext.ScenePos(1, 2, 3), zoneId);
            assertEquals(zoneId, ctx.getZoneId());
            assertEquals(1, DynamicZoneLineAllocator.lineIdOf(ctx.getZoneId()));
        }

        @Test
        void aoiAsyncExecutesSubmittedTask() throws Exception {
            LunarCoreProperties props = new LunarCoreProperties();
            props.getZone().setAoiAsyncEnabled(true);
            props.getZone().setAoiAsyncWorkers(1);
            props.getZone().setAoiAsyncQueueCapacity(64);
            AoiAsyncProcessor processor = new AoiAsyncProcessor(props);
            CountDownLatch latch = new CountDownLatch(1);
            processor.submit(latch::countDown);
            assertTrue(latch.await(2, TimeUnit.SECONDS));
            assertTrue(processor.submittedCount() >= 1);
            processor.shutdown();
        }

        @Test
        void aoiSyncFallbackWhenDisabled() {
            LunarCoreProperties props = new LunarCoreProperties();
            props.getZone().setAoiAsyncEnabled(false);
            AoiAsyncProcessor processor = new AoiAsyncProcessor(props);
            AtomicBoolean ran = new AtomicBoolean(false);
            processor.submit(() -> ran.set(true));
            assertTrue(ran.get());
            processor.shutdown();
        }

        @Test
        void zoneActorMailboxSerializesUpdates() throws Exception {
            ZoneActorMailbox mailbox = new ZoneActorMailbox(true);
            AtomicInteger counter = new AtomicInteger();
            CountDownLatch latch = new CountDownLatch(50);
            for (int i = 0; i < 50; i++) {
                mailbox.execute(42, () -> {
                    counter.incrementAndGet();
                    latch.countDown();
                });
            }
            assertTrue(latch.await(3, TimeUnit.SECONDS));
            assertEquals(50, counter.get());
            mailbox.close();
        }

        @Test
        void packetPriorityOutboundFlushesHighBeforeLow() {
            EmbeddedChannel ch = new EmbeddedChannel(new PacketPriorityOutboundHandler(8, 8, 8));
            ch.writeOneOutbound(new GamePacket(CmdIds.SCENE_ENTITY_SYNC_SC_NOTIFY, new byte[]{1}));
            ch.writeOneOutbound(new GamePacket(CmdIds.FIGHT_ACTION_CS_REQ, new byte[]{2}));
            ch.flushOutbound();
            GamePacket first = ch.readOutbound();
            GamePacket second = ch.readOutbound();
            assertNotNull(first);
            assertNotNull(second);
            assertEquals(CmdIds.FIGHT_ACTION_CS_REQ, first.getCmdId());
            assertEquals(CmdIds.SCENE_ENTITY_SYNC_SC_NOTIFY, second.getCmdId());
            ch.finishAndReleaseAll();
        }

        @Test
        void uidShardAndReadWriteRouter() {
            assertEquals(UidShardRouter.shardIndex(16), 0);
            assertEquals(UidShardRouter.shardIndex(17), 1);
            assertEquals("wallet_03", UidShardRouter.logicalDbName("wallet", 19, 16));

            ReadWriteRouter router = new ReadWriteRouter();
            String readKey = router.read(() -> {
                assertEquals(DataSourceType.REPLICA, RoutingDataSourceContext.get());
                return "ok";
            });
            assertEquals("ok", readKey);
            router.write(() -> assertEquals(DataSourceType.PRIMARY, RoutingDataSourceContext.get()));
        }

        @Test
        void ptLoadHeaderRoutesToShadow() throws Exception {
            PtLoadRoutingFilter filter = new PtLoadRoutingFilter();
            MockHttpServletRequest req = new MockHttpServletRequest();
            req.addHeader(PtLoadRoutingFilter.HEADER, "true");
            AtomicBoolean sawShadow = new AtomicBoolean(false);
            filter.doFilter(req, new MockHttpServletResponse(), (request, response) -> {
                sawShadow.set(RoutingDataSourceContext.get() == DataSourceType.SHADOW);
                assertTrue(RoutingDataSourceContext.isPtLoad());
            });
            assertTrue(sawShadow.get());
            assertEquals(DataSourceType.PRIMARY, RoutingDataSourceContext.get());
        }

        @Test
        void configDeltaAndBinarySnapshot() {
            ConfigDeltaPatchService svc = new ConfigDeltaPatchService();
            svc.putSnapshot("activity", "{\"npc\":{\"count\":10}}");
            String json = svc.applyDelta("activity", Map.of("npc.count", 1000));
            assertTrue(json.contains("1000"));
            assertTrue(svc.toBinarySnapshot("activity").length > 20);
            Map<String, Object> diff = svc.diff("{\"a\":1}", "{\"a\":2}");
            assertEquals(2, diff.get("a"));
        }
    }

    @Nested
    @DisplayName("玩法：战斗校验 / AI 策略 / 成就 / IAP / Manifest")
    class FeatureFlows {

        @Test
        void battleIntegrityPassAndFailKick() {
            GameSessionManager sessions = mock(GameSessionManager.class);
            when(sessions.getOrNull(anyLong())).thenReturn(null);
            BattleActionIntegrityGuard guard = new BattleActionIntegrityGuard(new BattleAuditService(), sessions);
            guard.setKickOnFail(false);

            BattleContext ctx = BattleContext.createNew(1001L, 77, 1, 1, Instant.now().getEpochSecond(), List.of());
            var challenge = guard.issue(1001L, 1);
            String hash = sha256("1001|1|2|3|" + challenge.salt());
            assertTrue(guard.verifyAndConsume(ctx, 1, 2, 3, hash).ok());

            guard.issue(1001L, 2);
            var fail = guard.verifyAndConsume(ctx, 2, 2, 3, "deadbeef");
            assertFalse(fail.ok());
            assertTrue(ctx.isEnded());
        }

        @Test
        void aiStrategyProxyRequiresConfirm() {
            LunarCoreProperties props = new LunarCoreProperties();
            props.getAiAssist().setBattleHintEnabled(true);
            props.getAiAssist().setBattleStrategyProxyEnabled(true);
            HeuristicBattleAssistPolicy heuristic = mock(HeuristicBattleAssistPolicy.class);
            when(heuristic.isEnabledFor(any())).thenReturn(true);
            when(heuristic.suggest(any(), anyInt())).thenReturn(
                    new cn.itcast.demo.mylunarcore.battle.assist.BattleAssistPolicy.Suggestion(
                            2, List.of(101), "heuristic:break", "", "破盾"));
            AiBattleStrategyProxy proxy = new AiBattleStrategyProxy(heuristic, props);
            BattleContext ctx = BattleContext.createNew(1L, 1, 1, 1, 0L, List.of());
            Map<String, Object> cmd = proxy.buildActionCommand(ctx, 1);
            assertEquals(true, cmd.get("enabled"));
            assertEquals(true, cmd.get("confirmRequired"));
            assertEquals(2, cmd.get("skillId"));
            assertEquals("break_shield", cmd.get("priority"));
        }

        @Test
        void offlineAchievementCompensatesFromBattleAndGacha() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), anyString()))
                    .thenReturn(5, 3);
            when(jdbc.queryForList(anyString(), any(), anyString())).thenReturn(List.of());
            var achievement = mock(cn.itcast.demo.mylunarcore.achievement.AchievementService.class);
            OfflineAchievementCompensator compensator = new OfflineAchievementCompensator(jdbc, achievement);
            Map<String, Integer> applied = compensator.compensateOnLogin(42, Instant.EPOCH);
            assertEquals(5, applied.get("battle_win_20"));
            assertEquals(3, applied.get("gacha"));
            verify(achievement, times(1)).addProgress(42, "battle_win_20", 5);
            verify(achievement, times(1)).addProgress(42, "gacha_10", 3);
            verify(achievement, times(1)).addProgress(42, "gacha_100", 3);
        }

        @Test
        void iapRefundFreezesAndBlocksSpend() {
            WalletApplicationService wallet = mock(WalletApplicationService.class);
            when(wallet.forceDeductAllowNegative(anyInt(), anyInt(), anyInt(), anyString()))
                    .thenReturn(new WalletApplicationService.WalletChangeResult(true, Map.of(101, -160), "iap_refund"));
            when(wallet.getBalance(anyInt())).thenReturn(Map.of(101, -160));

            NegativeBalanceFreezeService freeze = new NegativeBalanceFreezeService(wallet);
            IapOrderService orders = mock(IapOrderService.class);
            when(orders.estimateClawbackAmount(anyString())).thenReturn(160);
            IapRefundWebhookController controller = new IapRefundWebhookController(freeze, orders);

            ResponseEntity<Map<String, Object>> rsp = controller.googleRtdn(Map.of(
                    "notificationType", "REFUND",
                    "playerId", 88,
                    "channelTxId", "tx-1",
                    "currencyId", 101,
                    "amount", 160));
            assertTrue(Boolean.TRUE.equals(rsp.getBody().get("ok")));
            assertTrue(freeze.isFrozen(88));
            assertFalse(freeze.assertCanSpend(88));
            verify(wallet).forceDeductAllowNegative(eq(88), eq(101), eq(160), anyString());
        }

        @Test
        void clientResourceManifestDiff() {
            HotfixDataService hotfix = mock(HotfixDataService.class);
            HotfixData data = new HotfixData();
            data.setHotfixVersion("1.2.3");
            data.setClientResourceBaseUrl("https://cdn.example/");
            HotfixData.ManifestFileEntry file = new HotfixData.ManifestFileEntry();
            file.setPath("chars/skin_a.ab");
            file.setHash("abc");
            file.setSize(10);
            HotfixData.VersionManifestInfo info = new HotfixData.VersionManifestInfo();
            info.setFiles(List.of(file));
            data.setVersionInfo(info);
            data.setDeletedFiles(List.of("old.pak"));
            when(hotfix.current()).thenReturn(data);

            ClientResourceManifestService svc = new ClientResourceManifestService(new LunarCoreProperties(), hotfix);
            var diff = svc.diffForClient("1.0.0", Map.of());
            assertEquals("1.2.3", diff.serverVersion());
            assertEquals(1, diff.download().size());
            assertEquals("chars/skin_a.ab", diff.download().get(0).path());
            assertTrue(diff.delete().contains("old.pak"));
        }
    }

    @Nested
    @DisplayName("运维：维护模式 / 日志采样 / 告警载荷")
    class OpsFlows {

        @Test
        void maintenanceEnableAbortsBattlesAndRejectsLogin() {
            BattleManager battles = mock(BattleManager.class);
            when(battles.abortAllForShutdown()).thenReturn(2);
            BattleSnapshotService snapshots = mock(BattleSnapshotService.class);
            AlertWebhookNotifier webhook = new AlertWebhookNotifier(new LunarCoreProperties());
            MaintenanceModeService maintenance = new MaintenanceModeService(battles, snapshots, webhook);

            assertFalse(maintenance.rejectLogin());
            Map<String, Object> on = maintenance.enable("patch");
            assertEquals(true, on.get("enabled"));
            assertTrue(maintenance.rejectLogin());
            verify(battles).abortAllForShutdown();
            maintenance.disable();
            assertFalse(maintenance.rejectLogin());
        }

        @Test
        void battleLogSamplerAllowlistOnly() {
            LunarCoreProperties props = new LunarCoreProperties();
            props.getLogSampling().setEnabled(true);
            props.getLogSampling().setBattleDebugSampleRate(0.0);
            props.getLogSampling().setBattleDebugUidAllowlist("1001,1002");
            BattleLogSampler sampler = new BattleLogSampler(props);
            assertTrue(sampler.allowBattleDebug(1001));
            assertFalse(sampler.allowBattleDebug(2002));
            assertTrue(sampler.droppedCount() >= 1);
        }

        @Test
        void alertWebhookPayloadFormats() {
            String feishu = AlertWebhookNotifier.buildPayload("ERROR", "db", "pool full", "feishu");
            assertTrue(feishu.contains("msg_type"));
            assertTrue(feishu.contains("pool full"));
            String ding = AlertWebhookNotifier.buildPayload("WARN", "zone", "full", "dingtalk");
            assertTrue(ding.contains("msgtype"));
            assertTrue(ding.contains("full"));
        }
    }

    private static LunarCoreProperties zoneProps(int maxPlayers, int maxLines) {
        LunarCoreProperties props = new LunarCoreProperties();
        props.getZone().setMaxPlayers(maxPlayers);
        props.getZone().setDynamicLineEnabled(true);
        props.getZone().setMaxLines(maxLines);
        props.getZone().setAdaptiveCapacityEnabled(false);
        props.getZone().setActorMailboxEnabled(false);
        return props;
    }

    private static String sha256(String raw) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
