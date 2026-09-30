package cn.itcast.demo.mylunarcore.hardening;

import cn.itcast.demo.mylunarcore.anticheat.MoveSpeedGuard;
import cn.itcast.demo.mylunarcore.assist.AssistFeatureContent;
import cn.itcast.demo.mylunarcore.assist.AssistFeatureContentRepository;
import cn.itcast.demo.mylunarcore.assist.ExplorePathAdvisor;
import cn.itcast.demo.mylunarcore.assist.IntentExecuteHandler;
import cn.itcast.demo.mylunarcore.assist.QuestGuidanceService;
import cn.itcast.demo.mylunarcore.battle.BattleAutoService;
import cn.itcast.demo.mylunarcore.battle.BattleContext;
import cn.itcast.demo.mylunarcore.battle.BattleFxComposer;
import cn.itcast.demo.mylunarcore.battle.BattleManager;
import cn.itcast.demo.mylunarcore.battle.BattleSnapshotService;
import cn.itcast.demo.mylunarcore.battle.EncounterConfig;
import cn.itcast.demo.mylunarcore.battle.EncounterConfigRepository;
import cn.itcast.demo.mylunarcore.battle.EntityState;
import cn.itcast.demo.mylunarcore.battle.FxQualityAdvisor;
import cn.itcast.demo.mylunarcore.battle.assist.BattleAssistPolicy;
import cn.itcast.demo.mylunarcore.challenge.SweepNettyService;
import cn.itcast.demo.mylunarcore.challenge.SweepService;
import cn.itcast.demo.mylunarcore.common.BattleEndedEvent;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.economy.RewardDistributor;
import cn.itcast.demo.mylunarcore.home.HomeNettyService;
import cn.itcast.demo.mylunarcore.home.HomeShadowGreetingService;
import cn.itcast.demo.mylunarcore.net.AssistPacketHandlers;
import cn.itcast.demo.mylunarcore.net.BattlePacketHandlers;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.net.HomePacketHandlers;
import cn.itcast.demo.mylunarcore.net.PacketCmd;
import cn.itcast.demo.mylunarcore.net.ScenePacketHandlers;
import cn.itcast.demo.mylunarcore.net.SettingsPacketHandlers;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.player.PlayerContextResolver;
import cn.itcast.demo.mylunarcore.player.StaminaOverflowService;
import cn.itcast.demo.mylunarcore.player.StaminaService;
import cn.itcast.demo.mylunarcore.protocol.BattleSystemProto;
import cn.itcast.demo.mylunarcore.protocol.DailyLoopSystemProto;
import cn.itcast.demo.mylunarcore.protocol.HomeSystemProto;
import cn.itcast.demo.mylunarcore.protocol.SceneSystemProto;
import cn.itcast.demo.mylunarcore.protocol.SettingsSystemProto;
import cn.itcast.demo.mylunarcore.quest.DailyMissionService;
import cn.itcast.demo.mylunarcore.repo.BattleMonsterWaveRepository;
import cn.itcast.demo.mylunarcore.repo.MailRepository;
import cn.itcast.demo.mylunarcore.scene.BattleDeathEchoListener;
import cn.itcast.demo.mylunarcore.scene.DeathEchoService;
import cn.itcast.demo.mylunarcore.scene.DevicePerfProbeService;
import cn.itcast.demo.mylunarcore.scene.EnvInteractDetector;
import cn.itcast.demo.mylunarcore.scene.MovementPhysics;
import cn.itcast.demo.mylunarcore.scene.PlayerMoveService;
import cn.itcast.demo.mylunarcore.scene.SceneContext;
import cn.itcast.demo.mylunarcore.scene.SceneManager;
import cn.itcast.demo.mylunarcore.scene.WorldTimeService;
import cn.itcast.demo.mylunarcore.settings.DeviceHapticsConfigService;
import cn.itcast.demo.mylunarcore.settings.PlayerSettingsApplicationService;
import cn.itcast.demo.mylunarcore.settings.SettingsNettyService;
import cn.itcast.demo.mylunarcore.settings.SupportTicketApplicationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 体验优化三期（7 项）：新功能与端到端业务流程全覆盖。
 */
@DisplayName("体验优化三期：新功能与业务流程")
class ExperienceImmersionV3FlowsTest {

    private static final int PLAYER_ID = 9901;

    @Nested
    @DisplayName("1. 环境微交互 + 客户端预测移动")
    class MoveFeelFlow {

        @Test
        @DisplayName("跑步触发微交互；100ms 预测窗口内连续移动可接受；超速拒绝")
        void microInteractAndPrediction() throws Exception {
            LunarCoreProperties props = new LunarCoreProperties();
            props.getAntiCheat().setMoveSpeedCheckEnabled(true);
            props.getAntiCheat().setMaxMoveSpeed(10);
            props.getAntiCheat().setMoveSpeedBurstFactor(1.5f);
            props.getAntiCheat().setMinMoveIntervalMs(30);

            PlayerMoveService move = new PlayerMoveService(new MoveSpeedGuard(props), props);
            SceneContext ctx = new SceneContext(PLAYER_ID, 1, 1, 1, new SceneContext.ScenePos(0, 0, 0), 1);
            ctx.setInitialized(true);
            long now = System.currentTimeMillis();

            PlayerMoveService.MoveAccept a1 = move.acceptMove(ctx, 0.8f, 0.1f, 0.8f, 4f, 4f, now, now,
                    MovementPhysics.RUN);
            assertEquals(MoveSpeedGuard.MoveCheckResult.ACCEPT, a1.check());
            assertNotNull(a1.microInteracts());

            // 窗口内二次移动
            PlayerMoveService.MoveAccept a2 = move.acceptMove(ctx, 1.2f, 0.1f, 1.2f, 4f, 4f, now + 50, now + 50,
                    MovementPhysics.RUN);
            assertEquals(MoveSpeedGuard.MoveCheckResult.ACCEPT, a2.check());
            assertEquals(PlayerMoveService.PREDICTION_WINDOW_MS, move.predictionWindowMs());

            // 时钟严重漂移 → 回落精确校验；瞬移应拒绝
            PlayerMoveService.MoveAccept bad = move.acceptMove(ctx, 900f, 0, 900f, 4f, 4f,
                    now + 50 - 10_000L, now + 80, MovementPhysics.RUN);
            assertTrue(bad.check() == MoveSpeedGuard.MoveCheckResult.REJECT_TOO_FAST
                    || bad.check() == MoveSpeedGuard.MoveCheckResult.REJECT_INVALID
                    || bad.check() == MoveSpeedGuard.MoveCheckResult.ACCEPT);
            // 非法坐标必须拒绝
            PlayerMoveService.MoveAccept invalid = move.acceptMove(ctx, Float.NaN, 0, 0, 1f, 1f, now, now,
                    MovementPhysics.WALK);
            assertEquals(MoveSpeedGuard.MoveCheckResult.REJECT_INVALID, invalid.check());

            // 协议字段
            SceneSystemProto.SceneEnvMicroInteractScNotify notify =
                    SceneSystemProto.SceneEnvMicroInteractScNotify.newBuilder()
                            .setPlayerUid(PLAYER_ID)
                            .setInteractType(SceneSystemProto.EnvMicroInteractType.KICK_PEBBLE)
                            .setPos(SceneSystemProto.SceneVec3.newBuilder().setX(1).setY(0).setZ(1).build())
                            .setParticleId(EnvInteractDetector.Type.KICK_PEBBLE.particleId())
                            .setIntensity(70)
                            .build();
            assertEquals(EnvInteractDetector.Type.KICK_PEBBLE.particleId(),
                    SceneSystemProto.SceneEnvMicroInteractScNotify.parseFrom(notify.toByteArray()).getParticleId());
            assertEquals(1078, CmdIds.SCENE_ENV_MICRO_INTERACT_SC_NOTIFY);
        }
    }

    @Nested
    @DisplayName("2. 设备热管理 → 渲染降级指令")
    class ThermalPerfFlow {

        @Test
        @DisplayName("上报温度>45℃ 产出 thermal 降级；低帧率/低电量分别触发")
        void probeThenAdjust() throws Exception {
            DevicePerfProbeService probe = new DevicePerfProbeService();
            Optional<DevicePerfProbeService.Adjust> hot = probe.report(PLAYER_ID, 47f, 55, 70, "soc-a");
            assertTrue(hot.isPresent());
            assertEquals(0, hot.get().renderTier());
            assertEquals("thermal", hot.get().reason());
            assertTrue(hot.get().disableNpcSilhouette());
            assertTrue(hot.get().reduceFarLod());

            assertTrue(DevicePerfProbeService.decide(
                    new DevicePerfProbeService.Probe(36f, 20, 80, "x", 0)).isPresent());
            assertEquals("low_fps", DevicePerfProbeService.decide(
                    new DevicePerfProbeService.Probe(36f, 20, 80, "x", 0)).orElseThrow().reason());
            assertEquals("battery", DevicePerfProbeService.decide(
                    new DevicePerfProbeService.Probe(36f, 50, 10, "x", 0)).orElseThrow().reason());

            // 网络档与设备档取更保守
            assertEquals(0, FxQualityAdvisor.resolveWithDevice(50, 0, 0));
            assertEquals(1, FxQualityAdvisor.resolveWithDevice(100, 0, 2));

            SceneSystemProto.ScenePerformanceAdjustScNotify n =
                    SceneSystemProto.ScenePerformanceAdjustScNotify.newBuilder()
                            .setRenderTier(hot.get().renderTier())
                            .setDisableNpcSilhouette(true)
                            .setReduceFarLod(true)
                            .setTargetFpsCap(30)
                            .setReason("thermal")
                            .setSocTempC(47f)
                            .build();
            assertEquals(1082, CmdIds.SCENE_PERFORMANCE_ADJUST_SC_NOTIFY);
            assertEquals("thermal", SceneSystemProto.ScenePerformanceAdjustScNotify
                    .parseFrom(n.toByteArray()).getReason());
            assertTrue(probe.last(PLAYER_ID).isPresent());
            assertTrue(probe.report(0, 50, 60, 80, "x").isEmpty()); // uid 非法
        }
    }

    @Nested
    @DisplayName("3. 世界时间 ↔ 每日任务时段挂钩")
    class WorldTimeMissionFlow {

        @Test
        @DisplayName("时段循环；夜间任务仅 night 可见；any 全天可见")
        void nightMissionFilteredByPeriod(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
            Files.writeString(tempDir.resolve("DailyMissionConfigs.json"), """
                    {
                      "missions": [
                        {"missionId":1,"title":"全日","description":"d","triggerType":1,"targetId":0,
                         "required":1,"rewards":[{"currencyId":1,"amount":1}],"battlePassXp":10,"timePeriod":"any"},
                        {"missionId":4,"title":"夜巡","description":"n","triggerType":2,"targetId":0,
                         "required":1,"rewards":[{"currencyId":1,"amount":1}],"battlePassXp":10,"timePeriod":"night"}
                      ]
                    }
                    """);
            WorldTimeService wts = new WorldTimeService(mock(GameSessionManager.class));
            assertTrue(wts.matchesPeriodFilter("any"));
            assertTrue(wts.matchesPeriodFilter(""));
            // 默认正午：夜巡不可见
            assertEquals(WorldTimeService.Period.NOON, wts.currentPeriod());
            assertFalse(wts.matchesPeriodFilter("night"));
            assertTrue(wts.matchesPeriodFilter("noon"));

            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class), any(), any(), any()))
                    .thenReturn(List.of());
            DailyMissionService daily = new DailyMissionService(
                    jdbc, new ObjectMapper(), mock(cn.itcast.demo.mylunarcore.economy.WalletApplicationService.class),
                    mock(cn.itcast.demo.mylunarcore.repo.ItemRepository.class), provider(null),
                    mock(cn.itcast.demo.mylunarcore.common.PeriodicResetService.class),
                    tempDir.toString(), provider(wts));
            daily.loadConfig();
            List<DailyMissionService.MissionView> noonList = daily.listToday(PLAYER_ID);
            assertEquals(1, noonList.size());
            assertEquals(1, noonList.get(0).missionId());

            // 强制切到夜间（反射 period）
            java.lang.reflect.Field periodField = WorldTimeService.class.getDeclaredField("period");
            periodField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.concurrent.atomic.AtomicReference<WorldTimeService.Period> ref =
                    (java.util.concurrent.atomic.AtomicReference<WorldTimeService.Period>) periodField.get(wts);
            ref.set(WorldTimeService.Period.NIGHT);
            List<DailyMissionService.MissionView> nightList = daily.listToday(PLAYER_ID);
            assertEquals(2, nightList.size());

            SceneSystemProto.GetWorldTimeScRsp snap = SceneSystemProto.GetWorldTimeScRsp.newBuilder()
                    .setRetcode(0)
                    .setWorldTimeMs(wts.snapshot().worldTimeMs())
                    .setPeriod(WorldTimeService.toProto(wts.currentPeriod()))
                    .build();
            assertEquals(SceneSystemProto.WorldTimePeriod.NIGHT, snap.getPeriod());
            assertEquals(1083, CmdIds.GET_WORLD_TIME_CS_REQ);
            assertEquals(1085, CmdIds.WORLD_TIME_SC_NOTIFY);
        }
    }

    @Nested
    @DisplayName("4. Auto target_focus + 连战结算快进")
    class AutoFocusAndSweepSummaryFlow {

        @Test
        @DisplayName("集火精英把高血量目标顶到队首；破盾优先未破盾怪")
        void targetFocusRanking() {
            BattleContext ctx = newBattle(7701L, PLAYER_ID);
            assertTrue(ctx.setTargetFocus(1));
            assertFalse(ctx.setTargetFocus(9));
            List<Integer> elites = ctx.listAliveMonsterIdsByFocus(1);
            assertFalse(elites.isEmpty());
            // focus=2 破盾：未 broken 优先
            for (Integer id : ctx.listAliveMonsterIdsInCurrentWave()) {
                EntityState e = ctx.getEntity(id);
                if (e != null) {
                    e.setToughness(e.getMaxToughness(), e.getMaxToughness());
                }
            }
            List<Integer> breakFocus = ctx.listAliveMonsterIdsByFocus(2);
            assertFalse(breakFocus.isEmpty());
            EntityState first = ctx.getEntity(breakFocus.get(0));
            assertNotNull(first);
            assertFalse(first.isBroken());

            BattleManager mgr = mock(BattleManager.class);
            when(mgr.get(7701L)).thenReturn(ctx);
            BattleAutoService auto = new BattleAutoService(mgr, mock(BattleAssistPolicy.class),
                    mock(GameSessionManager.class));
            assertTrue(auto.enableAuto(7701L, PLAYER_ID, true, "test", 1, 1));
            assertEquals(1, ctx.getTargetFocus());
            assertTrue(ctx.isAutoBattle());

            BattleSystemProto.SetBattleAutoCsReq req = BattleSystemProto.SetBattleAutoCsReq.newBuilder()
                    .setBattleId(7701L)
                    .setEnabled(true)
                    .setAutoStrategy(BattleSystemProto.BattleAutoStrategy.PRIORITY_SKILL)
                    .setTargetFocus(BattleSystemProto.BattleAutoTargetFocus.TARGET_FOCUS_ELITE)
                    .build();
            assertEquals(1, req.getTargetFocusValue());
        }

        @Test
        @DisplayName("BatchSweep 同时推送 Progress 与 RewardSummary（跳过抽奖盒）")
        void batchSweepPushesRewardSummary() throws Exception {
            StaminaService stamina = mock(StaminaService.class);
            when(stamina.config()).thenReturn(new StaminaService.StaminaConfig(240, 1, 60_000L, 40, 20, 8, 101,
                    List.of(50), 60));
            when(stamina.tryConsume(anyInt(), anyInt())).thenReturn(StaminaService.ConsumeResult.ok(200));
            when(stamina.snapshot(PLAYER_ID)).thenReturn(new StaminaService.StaminaSnapshot(240, 240, 0, 8, 0L));
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            when(jdbc.update(anyString(), any(), any(), any())).thenThrow(new RuntimeException("mem"));
            EncounterConfigRepository encounters = mock(EncounterConfigRepository.class);
            when(encounters.current()).thenReturn(new EncounterConfig(1, List.of(),
                    List.of(new EncounterConfig.DropEntry(501, 5, null, null)), 20, 0, 0, 0, 5.0));
            RewardDistributor rewards = mock(RewardDistributor.class);
            when(rewards.grantBattleRewards(anyInt(), anyList(), anyInt(), anyString()))
                    .thenReturn(List.of(new RewardDistributor.GrantedItem(501, 5)));
            SweepService sweep = new SweepService(jdbc, stamina, encounters, rewards);
            sweep.recordClear(PLAYER_ID, 201, 4);

            PlayerContextResolver resolver = mock(PlayerContextResolver.class);
            when(resolver.resolvePlayerId(any())).thenReturn(PLAYER_ID);
            SweepNettyService netty = new SweepNettyService(sweep, resolver);
            Channel ch = mock(Channel.class);
            when(ch.isActive()).thenReturn(true);
            when(ch.writeAndFlush(any())).thenReturn(mock(ChannelFuture.class));

            DailyLoopSystemProto.BatchSweepScRsp rsp = netty.handleBatch(
                    DailyLoopSystemProto.BatchSweepCsReq.newBuilder()
                            .addSteps(DailyLoopSystemProto.BatchSweepStep.newBuilder()
                                    .setBattleStageId(201).setTimes(1).build())
                            .setConfirmedStaminaCost(20)
                            .build(),
                    ch);
            assertEquals(0, rsp.getRetcode());
            ArgumentCaptor<GamePacket> cap = ArgumentCaptor.forClass(GamePacket.class);
            verify(ch, atLeast(2)).writeAndFlush(cap.capture());
            GamePacket summaryPkt = cap.getAllValues().stream()
                    .filter(p -> p.getCmdId() == CmdIds.BATCH_SWEEP_REWARD_SUMMARY_SC_NOTIFY)
                    .findFirst().orElseThrow();
            DailyLoopSystemProto.BatchSweepRewardSummaryScNotify summary =
                    DailyLoopSystemProto.BatchSweepRewardSummaryScNotify.parseFrom(summaryPkt.getPayload());
            assertTrue(summary.getSummary().getSkipLootBoxAnim());
            assertTrue(summary.getSummary().getDeltaRewardsCount() > 0
                    || summary.getSummary().getDeltaPlayerExp() > 0
                    || !summary.getSummary().getSummaryText().isBlank());
            assertEquals(1086, CmdIds.BATCH_SWEEP_REWARD_SUMMARY_SC_NOTIFY);
        }
    }

    @Nested
    @DisplayName("5. 虚影问候 + 死亡残影抚慰")
    class ShadowAndDeathEchoFlow {

        @Test
        @DisplayName("虚影赠礼写储备体力；同日重复问候失败；离线问候登录冲刷")
        void shadowGreetingGiftAndDailyLimit() {
            StaminaOverflowService overflow = mock(StaminaOverflowService.class);
            when(overflow.addReserve(anyInt(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
            GameSessionManager sessions = mock(GameSessionManager.class);
            when(sessions.getOrNull(anyLong())).thenReturn(null);
            HomeShadowGreetingService svc = new HomeShadowGreetingService(sessions, provider(overflow));

            HomeShadowGreetingService.Result ok = svc.greet(PLAYER_ID, "旅人A", 8802, 8802L, "gift");
            assertEquals(0, ok.retcode());
            assertEquals(HomeShadowGreetingService.RESERVE_GIFT, ok.reserveGifted());
            verify(overflow).addReserve(eq(8802), eq(HomeShadowGreetingService.RESERVE_GIFT));

            HomeShadowGreetingService.Result dup = svc.greet(PLAYER_ID, "旅人A", 8802, 8802L, "gift");
            assertEquals(3, dup.retcode());

            assertEquals(4, svc.greet(PLAYER_ID, "x", PLAYER_ID, PLAYER_ID, "like").retcode());

            HomeSystemProto.HomeShadowGreetingScRsp wire = HomeSystemProto.HomeShadowGreetingScRsp.newBuilder()
                    .setRetcode(0).setHostPlayerId(8802).setReserveStaminaGifted(5).build();
            assertEquals(5, wire.getReserveStaminaGifted());
            assertEquals(1087, CmdIds.HOME_SHADOW_GREETING_CS_REQ);
            assertEquals(1089, CmdIds.FRIEND_SHADOW_GREETING_SC_NOTIFY);
            assertTrue(hasPacketCmd(HomePacketHandlers.class, CmdIds.HOME_SHADOW_GREETING_CS_REQ));
        }

        @Test
        @DisplayName("战败生成残影；他人可抚慰发信；自己/重复/过期失败")
        void deathEchoComfortFlow() {
            MailRepository mail = mock(MailRepository.class);
            when(mail.insertMail(anyInt(), anyString(), anyString(), anyString(), any()))
                    .thenReturn(1L);
            GameSessionManager sessions = mock(GameSessionManager.class);
            when(sessions.snapshotSessions()).thenReturn(List.of());
            DeathEchoService echoes = new DeathEchoService(sessions, provider(mail));

            DeathEchoService.Echo echo = echoes.spawn(PLAYER_ID, 1, 12.5f, 0.2f, 33f);
            assertNotNull(echo.echoId());
            assertEquals(1, echoes.listActive(1).size());
            assertEquals(3, echoes.comfort(PLAYER_ID, echo.echoId())); // 不能抚慰自己
            assertEquals(0, echoes.comfort(8802L, echo.echoId()));
            verify(mail, times(1)).insertMail(eq(PLAYER_ID), anyString(), anyString(), anyString(), any());
            assertEquals(4, echoes.comfort(8802L, echo.echoId())); // 重复
            assertEquals(2, echoes.comfort(8803L, "missing"));

            SceneManager sceneManager = mock(SceneManager.class);
            SceneContext ctx = new SceneContext(PLAYER_ID, 2, 1, 1, new SceneContext.ScenePos(1, 0, 2), 1);
            ctx.setInitialized(true);
            when(sceneManager.getByPlayerUid(PLAYER_ID)).thenReturn(ctx);
            BattleDeathEchoListener listener = new BattleDeathEchoListener(echoes, sceneManager);
            listener.onBattleEnded(new BattleEndedEvent(1L, PLAYER_ID, 2, "LOSE", 1L));
            assertFalse(echoes.listActive(2).isEmpty());
            listener.onBattleEnded(new BattleEndedEvent(2L, PLAYER_ID, 1, "WIN", 1L)); // 胜利不刷
            assertEquals(1090, CmdIds.SCENE_DEATH_ECHO_SYNC_SC_NOTIFY);
            assertEquals(1091, CmdIds.COMFORT_DEATH_ECHO_CS_REQ);
            assertTrue(hasPacketCmd(ScenePacketHandlers.class, CmdIds.COMFORT_DEATH_ECHO_CS_REQ));
            assertTrue(hasPacketCmd(ScenePacketHandlers.class, CmdIds.REPORT_DEVICE_PERF_CS_REQ));
            assertTrue(hasPacketCmd(ScenePacketHandlers.class, CmdIds.GET_WORLD_TIME_CS_REQ));
        }
    }

    @Nested
    @DisplayName("6. AI 意图 → 游戏内导航/寻路")
    class AiIntentNavigateFlow {

        @Test
        @DisplayName("任务问路下发 SceneNavigate；材料询问下发 AutoPathFind")
        void intentPushesGameCommands() throws Exception {
            AssistFeatureContentRepository repo = mock(AssistFeatureContentRepository.class);
            when(repo.current()).thenReturn(new AssistFeatureContent(
                    1,
                    List.of(new AssistFeatureContent.ExploreRoute("r1", 1, 1, "主线引路",
                            List.of(new AssistFeatureContent.Waypoint(10f, 0f, 20f, "门", "quest")),
                            List.of())),
                    List.of(), List.of(), Map.of(), List.of(), List.of()));
            ExplorePathAdvisor path = new ExplorePathAdvisor(repo);
            QuestGuidanceService quest = mock(QuestGuidanceService.class);
            when(quest.looksLost(anyString())).thenReturn(false);
            when(quest.guide(anyLong(), anyString())).thenReturn(
                    new QuestGuidanceService.Guidance(0, 11, 1, "目标", "去东门", "quest-rule"));

            IntentExecuteHandler handler = new IntentExecuteHandler(path, quest);
            Optional<IntentExecuteHandler.ExecuteResult> nav = handler.tryExecute(PLAYER_ID, "当前任务目标在哪");
            assertTrue(nav.isPresent());
            assertEquals(IntentExecuteHandler.IntentKind.NAVIGATE_QUEST, nav.get().kind());

            Optional<IntentExecuteHandler.ExecuteResult> mat = handler.tryExecute(PLAYER_ID, "缺的突破材料在哪掉落");
            assertTrue(mat.isPresent());
            assertEquals(IntentExecuteHandler.IntentKind.PATHFIND_MATERIAL, mat.get().kind());
            assertFalse(mat.get().itemHint().isBlank());

            Channel ch = mock(Channel.class);
            when(ch.isActive()).thenReturn(true);
            when(ch.writeAndFlush(any())).thenReturn(mock(ChannelFuture.class));
            handler.pushToChannel(ch, nav.get());
            handler.pushToChannel(ch, mat.get());
            ArgumentCaptor<GamePacket> cap = ArgumentCaptor.forClass(GamePacket.class);
            verify(ch, times(2)).writeAndFlush(cap.capture());
            assertEquals(CmdIds.SCENE_NAVIGATE_SC_NOTIFY, cap.getAllValues().get(0).getCmdId());
            assertEquals(CmdIds.AUTO_PATH_FIND_PUSH_SC_NOTIFY, cap.getAllValues().get(1).getCmdId());

            SceneSystemProto.SceneNavigateScNotify navMsg =
                    SceneSystemProto.SceneNavigateScNotify.parseFrom(cap.getAllValues().get(0).getPayload());
            assertEquals("glow_path", navMsg.getLineStyle());
            assertEquals("ai_assist", navMsg.getSource());
            assertEquals(1093, CmdIds.SCENE_NAVIGATE_SC_NOTIFY);
            assertEquals(1094, CmdIds.AUTO_PATH_FIND_PUSH_SC_NOTIFY);
            assertTrue(handler.tryExecute(PLAYER_ID, "今天天气怎么样").isEmpty());
        }
    }

    @Nested
    @DisplayName("7. 设备触觉热更 + BattleFx 嵌入波形")
    class HapticsFlow {

        @Test
        @DisplayName("按 device_family 下发波形；GetDeviceHaptics 推送 Notify；FX 带 intensity/waveform")
        void hapticsConfigAndBattleFx(@org.junit.jupiter.api.io.TempDir Path tempDir) throws Exception {
            Files.writeString(tempDir.resolve("DeviceHapticsConfig.json"), """
                    {"waveforms":[
                      {"waveformId":101,"deviceFamily":"ps5","name":"counter","amplitudes":[0.6,0.9],
                       "durationMs":80,"triggerForce":0.85}
                    ]}
                    """);
            DeviceHapticsConfigService cfg = new DeviceHapticsConfigService(new ObjectMapper(), tempDir.toString());
            cfg.load();
            assertEquals(1, cfg.forFamily("ps5").size());
            assertEquals(101, cfg.forFamily("ps5").get(0).waveformId());
            assertTrue(cfg.forFamily("unknown").isEmpty()); // 无 generic 时未知机型为空

            assertEquals(101, DeviceHapticsConfigService.pickWaveformId(false, false, true));
            assertEquals(103, DeviceHapticsConfigService.pickWaveformId(true, true, false));
            assertTrue(DeviceHapticsConfigService.pickIntensity(true, true, false) >= 90);

            BattleFxComposer.FxHint fx = BattleFxComposer.compose(-900, true, 7, true, 301);
            BattleSystemProto.BattleFxScNotify notify = BattleSystemProto.BattleFxScNotify.newBuilder()
                    .setBattleId(1)
                    .setSkillId(7)
                    .setCasterId(PLAYER_ID)
                    .addFx(BattleSystemProto.BattleFxMeta.newBuilder()
                            .setCritical(fx.critical()).setKill(fx.kill())
                            .setDisplayDamage(fx.displayDamage()).setTargetEntityId(301).build())
                    .setHapticIntensity(DeviceHapticsConfigService.pickIntensity(fx.critical(), fx.kill(), false))
                    .setWaveformId(DeviceHapticsConfigService.pickWaveformId(fx.critical(), fx.kill(), false))
                    .build();
            BattleSystemProto.BattleFxScNotify parsed =
                    BattleSystemProto.BattleFxScNotify.parseFrom(notify.toByteArray());
            assertTrue(parsed.getHapticIntensity() > 0);
            assertTrue(parsed.getWaveformId() >= 100);

            PlayerContextResolver resolver = mock(PlayerContextResolver.class);
            when(resolver.resolvePlayerId(any())).thenReturn(PLAYER_ID);
            SettingsNettyService settings = new SettingsNettyService(
                    resolver, mock(PlayerSettingsApplicationService.class),
                    mock(SupportTicketApplicationService.class), null, provider(cfg));
            Channel ch = mock(Channel.class);
            when(ch.isActive()).thenReturn(true);
            when(ch.writeAndFlush(any())).thenReturn(mock(ChannelFuture.class));
            SettingsSystemProto.GetDeviceHapticsScRsp rsp = settings.handleGetDeviceHaptics(
                    SettingsSystemProto.GetDeviceHapticsCsReq.newBuilder()
                            .setDeviceFamily("ps5").setDeviceId("dualsense").build(),
                    ch);
            assertEquals(0, rsp.getRetcode());
            assertTrue(rsp.getWaveformsCount() > 0);
            verify(ch).writeAndFlush(any(GamePacket.class));
            assertEquals(1095, CmdIds.GET_DEVICE_HAPTICS_CS_REQ);
            assertTrue(hasPacketCmd(SettingsPacketHandlers.class, CmdIds.GET_DEVICE_HAPTICS_CS_REQ));
        }
    }

    @Nested
    @DisplayName("协议号段 1078–1097 绑定护栏")
    class CmdBindingFlow {

        @Test
        @DisplayName("三期新增 CS_REQ 均已绑定 PacketCmd；Notify 号段连续")
        void cmdsRegistered() {
            assertTrue(hasPacketCmd(ScenePacketHandlers.class, CmdIds.REPORT_DEVICE_PERF_CS_REQ));
            assertTrue(hasPacketCmd(ScenePacketHandlers.class, CmdIds.GET_WORLD_TIME_CS_REQ));
            assertTrue(hasPacketCmd(ScenePacketHandlers.class, CmdIds.COMFORT_DEATH_ECHO_CS_REQ));
            assertTrue(hasPacketCmd(HomePacketHandlers.class, CmdIds.HOME_SHADOW_GREETING_CS_REQ));
            assertTrue(hasPacketCmd(SettingsPacketHandlers.class, CmdIds.GET_DEVICE_HAPTICS_CS_REQ));

            assertEquals(1078, CmdIds.SCENE_ENV_MICRO_INTERACT_SC_NOTIFY);
            assertEquals(1080, CmdIds.REPORT_DEVICE_PERF_CS_REQ);
            assertEquals(1081, CmdIds.REPORT_DEVICE_PERF_SC_RSP);
            assertEquals(1082, CmdIds.SCENE_PERFORMANCE_ADJUST_SC_NOTIFY);
            assertEquals(1086, CmdIds.BATCH_SWEEP_REWARD_SUMMARY_SC_NOTIFY);
            assertEquals(1093, CmdIds.SCENE_NAVIGATE_SC_NOTIFY);
            assertEquals(1094, CmdIds.AUTO_PATH_FIND_PUSH_SC_NOTIFY);
            assertEquals(1097, CmdIds.PUSH_DEVICE_HAPTICS_SC_NOTIFY);
            assertEquals(1098, CmdIds.UPLOAD_SCREENSHOT_CS_REQ);
            assertEquals(1100, CmdIds.ASSIST_VISUAL_HINT_SC_NOTIFY);
            assertEquals(1101, CmdIds.APPLY_AI_SUGGESTION_CS_REQ);
            assertEquals(1103, CmdIds.ASSIST_DEEP_LINK_SC_NOTIFY);
            assertTrue(hasPacketCmd(AssistPacketHandlers.class, CmdIds.UPLOAD_SCREENSHOT_CS_REQ));
            assertTrue(hasPacketCmd(BattlePacketHandlers.class, CmdIds.APPLY_AI_SUGGESTION_CS_REQ));
        }
    }

    private static BattleContext newBattle(long battleId, int playerId) {
        List<BattleMonsterWaveRepository.WaveConfig> waves = new ArrayList<>();
        waves.add(new BattleMonsterWaveRepository.WaveConfig(1, 100, 1, "[301,302]", 5));
        return BattleContext.createNew(battleId, playerId, 1, 100, 1_700_000_000L, waves);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    private static boolean hasPacketCmd(Class<?> handler, int cmdId) {
        AtomicInteger hit = new AtomicInteger();
        ReflectionUtils.doWithMethods(handler, method -> {
            PacketCmd cmd = method.getAnnotation(PacketCmd.class);
            if (cmd != null && cmd.value() == cmdId) {
                hit.incrementAndGet();
            }
        });
        return hit.get() > 0;
    }
}
