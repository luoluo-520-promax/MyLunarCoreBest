package cn.itcast.demo.mylunarcore.hardening;

import cn.itcast.demo.mylunarcore.achievement.AchievementService;
import cn.itcast.demo.mylunarcore.analytics.AnalyticsEventPublisher;
import cn.itcast.demo.mylunarcore.assist.AssistFeatureContentRepository;
import cn.itcast.demo.mylunarcore.battle.BattleAutoService;
import cn.itcast.demo.mylunarcore.battle.BattleContext;
import cn.itcast.demo.mylunarcore.battle.BattleManager;
import cn.itcast.demo.mylunarcore.battle.BattleSnapshotService;
import cn.itcast.demo.mylunarcore.battle.assist.HeuristicBattleAssistPolicy;
import cn.itcast.demo.mylunarcore.center.MigrationTicketService;
import cn.itcast.demo.mylunarcore.common.BusinessMetrics;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.cutscene.CutsceneTriggerService;
import cn.itcast.demo.mylunarcore.dialogue.DialogueCutsceneNettyService;
import cn.itcast.demo.mylunarcore.dialogue.DialogueProgressService;
import cn.itcast.demo.mylunarcore.dialogue.DialogueTreeRepository;
import cn.itcast.demo.mylunarcore.dialogue.DialogueTriggerEngine;
import cn.itcast.demo.mylunarcore.economy.WalletApplicationService;
import cn.itcast.demo.mylunarcore.hall.FriendApplicationService;
import cn.itcast.demo.mylunarcore.home.FurnitureInteractHandler;
import cn.itcast.demo.mylunarcore.home.HomeBaseService;
import cn.itcast.demo.mylunarcore.home.HomeNettyService;
import cn.itcast.demo.mylunarcore.home.HomePresenceService;
import cn.itcast.demo.mylunarcore.home.HomeVisitorLogService;
import cn.itcast.demo.mylunarcore.item.ExpiredItemRecycleService;
import cn.itcast.demo.mylunarcore.item.ItemApplicationService;
import cn.itcast.demo.mylunarcore.item.ItemNettyService;
import cn.itcast.demo.mylunarcore.matchmaking.MatchNettyService;
import cn.itcast.demo.mylunarcore.matchmaking.MatchPhase;
import cn.itcast.demo.mylunarcore.matchmaking.MatchmakingService;
import cn.itcast.demo.mylunarcore.matchmaking.RoomService;
import cn.itcast.demo.mylunarcore.model.FriendEntity;
import cn.itcast.demo.mylunarcore.net.BattlePacketHandlers;
import cn.itcast.demo.mylunarcore.net.CharacterPacketHandlers;
import cn.itcast.demo.mylunarcore.net.ClientFeatureFlags;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.DailyLoopPacketHandlers;
import cn.itcast.demo.mylunarcore.net.DialogueCutscenePacketHandlers;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.net.HomePacketHandlers;
import cn.itcast.demo.mylunarcore.net.InputCapability;
import cn.itcast.demo.mylunarcore.net.ItemPacketHandlers;
import cn.itcast.demo.mylunarcore.net.MatchPacketHandlers;
import cn.itcast.demo.mylunarcore.net.PacketCmd;
import cn.itcast.demo.mylunarcore.net.ProtocolCompatService;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.player.PlaySessionCleanupService;
import cn.itcast.demo.mylunarcore.player.PlayerChannelAttributes;
import cn.itcast.demo.mylunarcore.player.PlayerContextResolver;
import cn.itcast.demo.mylunarcore.player.PlayerLoadingStateService;
import cn.itcast.demo.mylunarcore.player.PlayerSessionState;
import cn.itcast.demo.mylunarcore.player.StaminaNettyService;
import cn.itcast.demo.mylunarcore.player.StaminaOverflowService;
import cn.itcast.demo.mylunarcore.protocol.AchievementSystemProto;
import cn.itcast.demo.mylunarcore.protocol.BattleSystemProto;
import cn.itcast.demo.mylunarcore.protocol.DailyLoopSystemProto;
import cn.itcast.demo.mylunarcore.protocol.DialogueCutsceneProto;
import cn.itcast.demo.mylunarcore.protocol.HallSystemProto;
import cn.itcast.demo.mylunarcore.protocol.HomeSystemProto;
import cn.itcast.demo.mylunarcore.protocol.ItemSystemProto;
import cn.itcast.demo.mylunarcore.protocol.MatchmakingSystemProto;
import cn.itcast.demo.mylunarcore.protocol.PlayerSessionProto;
import cn.itcast.demo.mylunarcore.protocol.SceneSystemProto;
import cn.itcast.demo.mylunarcore.repo.BattleMonsterWaveRepository;
import cn.itcast.demo.mylunarcore.repo.ItemRepository;
import cn.itcast.demo.mylunarcore.rogue.RogueManager;
import cn.itcast.demo.mylunarcore.scene.SceneManager;
import cn.itcast.demo.mylunarcore.scene.ScenePreloadService;
import cn.itcast.demo.mylunarcore.scene.ZoneManager;
import cn.itcast.demo.mylunarcore.social.FriendOnlineStatusService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * wire v3 体验闭环 8 项：协议往返、成功路径、失败码与状态机。
 */
@DisplayName("体验闭环新功能业务流程")
class ExperienceLoopBusinessFlowsTest {

    @Nested
    @DisplayName("1. 战斗 Auto + 倍速")
    class BattleAutoSpeedFlow {

        @Test
        @DisplayName("开启 Auto 后服务端启发式出手并压缩等待")
        void autoTurnDealsDamageAndCompressesWait() throws Exception {
            BattleManager battles = new BattleManager(mock(BattleSnapshotService.class));
            BattleContext ctx = newBattle(88011L, 77);
            battles.put(ctx);
            BattleAutoService auto = new BattleAutoService(battles,
                    new HeuristicBattleAssistPolicy(new LunarCoreProperties(), mock(AssistFeatureContentRepository.class)),
                    mock(GameSessionManager.class));

            assertTrue(auto.enableAuto(88011L, 77, true, "manual"));
            assertTrue(ctx.isAutoBattle());
            assertEquals("manual", ctx.getAutoReason());
            assertTrue(auto.setSpeed(88011L, 77, 3));
            assertEquals(3, ctx.getSpeedMultiplier());
            assertEquals(500L, ctx.compressedWaitMs(BattleAutoService.BASE_AUTO_WAIT_MS));
            assertFalse(auto.setSpeed(88011L, 77, 4));

            int hpBefore = firstMonsterHp(ctx);
            BattleAutoService.AutoAction action = auto.executeOneTurn(ctx);
            assertNotNull(action);
            assertTrue(action.hpDelta() < 0);
            assertEquals(hpBefore + action.hpDelta(), firstMonsterHp(ctx));
            assertEquals(500, action.waitMs());

            BattleSystemProto.BattleAutoScNotify notify = BattleSystemProto.BattleAutoScNotify.newBuilder()
                    .setBattleId(ctx.getBattleId())
                    .setSkillId(action.skillId())
                    .setCasterId(action.casterId())
                    .addAllTargetIds(action.targetIds())
                    .setSpeedMultiplier(3)
                    .setNextWaitMs(action.waitMs())
                    .build();
            BattleSystemProto.BattleAutoScNotify parsed =
                    BattleSystemProto.BattleAutoScNotify.parseFrom(notify.toByteArray());
            assertEquals(88011L, parsed.getBattleId());
            assertEquals(3, parsed.getSpeedMultiplier());
        }

        @Test
        @DisplayName("掉线托管不中断战局；回主界面才中止")
        void disconnectKeepsBattleLogoutAborts() {
            BattleManager battles = new BattleManager(mock(BattleSnapshotService.class));
            BattleContext ctx = newBattle(88012L, 88);
            battles.put(ctx);
            PlaySessionCleanupService cleanup = new PlaySessionCleanupService(
                    mock(SceneManager.class), mock(ZoneManager.class), battles, mock(RogueManager.class));

            cleanup.leaveCurrentPlay(88L, false);
            assertTrue(ctx.isAutoBattle());
            assertEquals("disconnect", ctx.getAutoReason());
            assertNotNull(battles.get(88012L));

            cleanup.leaveCurrentPlay(88L, true);
            assertNull(battles.get(88012L));
        }

        @Test
        @DisplayName("未登录 / 非参与者开关 Auto 失败")
        void autoRejectsInvalidPlayer() {
            BattleManager battles = new BattleManager(mock(BattleSnapshotService.class));
            battles.put(newBattle(1L, 11));
            BattleAutoService auto = new BattleAutoService(battles,
                    new HeuristicBattleAssistPolicy(new LunarCoreProperties(), mock(AssistFeatureContentRepository.class)),
                    mock(GameSessionManager.class));
            assertFalse(auto.enableAuto(1L, 99, true, "manual"));
            assertFalse(auto.enableAuto(404L, 11, true, "manual"));
        }
    }

    @Nested
    @DisplayName("2. 体力溢出 + 过期道具转化")
    class StaminaAndRecycleFlow {

        @Test
        @DisplayName("满体恢复 30% 进储备，提取后当前体力增加")
        void overflowAbsorbThenWithdraw() {
            Map<Integer, Integer> store = new ConcurrentHashMap<>();
            StaminaOverflowService overflow = new StaminaOverflowService(reserveJdbc(store));
            assertEquals(3, overflow.absorbOverflowRegen(501, 10));
            assertEquals(3, overflow.readReserve(501));
            assertEquals(0, overflow.absorbOverflowRegen(501, 0));
            assertEquals(2, overflow.withdraw(501, 2));
            assertEquals(1, overflow.readReserve(501));
            overflow.addReserve(501, 300);
            assertEquals(StaminaOverflowService.DEFAULT_RESERVE_CAP, overflow.readReserve(501));
        }

        @Test
        @DisplayName("过期道具按汇率转信用点并推送 ItemRecycleNotify")
        void expiredItemConvertsToCreditsAndNotifies() throws Exception {
            WalletApplicationService wallet = mock(WalletApplicationService.class);
            when(wallet.add(anyInt(), anyInt(), anyInt(), anyString()))
                    .thenReturn(new WalletApplicationService.WalletChangeResult(true, Map.of(101, 30), "ok"));
            EmbeddedChannel ch = new EmbeddedChannel();
            GameSession session = new GameSession(9L, ch, null);
            GameSessionManager sessions = mock(GameSessionManager.class);
            when(sessions.getOrNull(9L)).thenReturn(session);

            ExpiredItemRecycleService recycle = new ExpiredItemRecycleService(
                    null, provider(wallet), provider(sessions));
            ExpiredItemRecycleService.RecycleResult r = recycle.recycleOne(9, 501, 3, 10);
            assertEquals(30, r.credits());
            verify(wallet).add(9, ExpiredItemRecycleService.CREDIT_CURRENCY_ID, 30, "item_recycle_expired");

            GamePacket pkt = ch.readOutbound();
            assertNotNull(pkt);
            assertEquals(CmdIds.ITEM_RECYCLE_SC_NOTIFY, pkt.getCmdId());
            ItemSystemProto.ItemRecycleNotify n = ItemSystemProto.ItemRecycleNotify.parseFrom(pkt.getPayload());
            assertEquals(501, n.getItemId());
            assertEquals(3, n.getRecycledCount());
            assertEquals(101, n.getCreditCurrencyId());
            assertEquals(30, n.getCreditAmount());
            assertEquals("expired", n.getReason());
        }

        @Test
        @DisplayName("未登录提取储备体力 retcode=1")
        void claimReserveRequiresLogin() {
            StaminaNettyService netty = new StaminaNettyService(
                    mock(cn.itcast.demo.mylunarcore.player.StaminaService.class),
                    new PlayerContextResolver(mock(GameSessionManager.class)));
            DailyLoopSystemProto.ClaimReserveStaminaScRsp rsp =
                    netty.handleClaimReserve(10, new EmbeddedChannel());
            assertEquals(1, rsp.getRetcode());
        }
    }

    @Nested
    @DisplayName("3. 强制 Preload + Loading 分级")
    class PreloadLoadingFlow {

        @Test
        @DisplayName("靠近传送门检测、命中 RIFT/DISSOLVE、未命中 BLACK")
        void portalForcePreloadThenFadeOrBlack() throws Exception {
            ScenePreloadService preload = new ScenePreloadService();
            assertNotNull(preload.detectApproach(1, 100f, 100f));
            assertEquals(null, preload.detectApproach(1, 0f, 0f));

            preload.beginPreload(7001L, 2, 1, 1);
            assertEquals("RIFT", preload.resolveTransitionType(7001L, true));
            assertEquals("BLACK", preload.resolveTransitionType(7001L, false));
            for (int i = 0; i < 20; i++) {
                preload.recordOutcome(7001L, true);
            }
            assertEquals("DISSOLVE", preload.resolveTransitionType(7001L, true));

            PlayerLoadingStateService loading = new PlayerLoadingStateService(new MigrationTicketService());
            var fadeTicket = loading.beginLoading(7001L, 2, 1, 1, 0, 0, 0, true, "DISSOLVE");
            assertEquals("DISSOLVE", fadeTicket.transitionType());
            assertTrue(fadeTicket.handshakeOnly());
            PlayerLoadingStateService.CompleteResult done = loading.completeLoadingResult(7001L, fadeTicket.ticket());
            assertTrue(done.ok());
            assertEquals("DISSOLVE", done.transitionType());

            var blackTicket = loading.beginLoading(7002L, 9, 1, 1, 0, 0, 0, false, "BLACK");
            assertEquals("BLACK", blackTicket.transitionType());

            SceneSystemProto.ScenePreloadPushScNotify push = SceneSystemProto.ScenePreloadPushScNotify.newBuilder()
                    .setTargetPlaneId(2).setTargetFloorId(1).setTargetEntryId(1)
                    .addAllAssetKeys(preload.lodPlaceholderKeys(2, 1))
                    .setMaskInfo(SceneSystemProto.SceneLoadMaskInfo.newBuilder()
                            .setTransitionType("DISSOLVE").setRecommendedLayoutId(InputCapability.LAYOUT_PC_KM)
                            .setHitRateBp(9500).setDurationMs(ScenePreloadService.DISSOLVE_DURATION_MS).build())
                    .setReason(3)
                    .build();
            SceneSystemProto.ScenePreloadPushScNotify parsed =
                    SceneSystemProto.ScenePreloadPushScNotify.parseFrom(push.toByteArray());
            assertEquals("DISSOLVE", parsed.getMaskInfo().getTransitionType());
            assertEquals(3, parsed.getReason());
            assertEquals(CmdIds.SCENE_PRELOAD_PUSH_SC_NOTIFY, 1028);
        }
    }

    @Nested
    @DisplayName("4. 家园拜访日志 + 跨节点好友在线")
    class HomeAndFriendOnlineFlow {

        @Test
        @DisplayName("拜访写日志、留言点赞、查询未读")
        void visitLikeMessageAndList() {
            HomeVisitorLogService logs = new HomeVisitorLogService(null);
            HomeBaseService homes = new HomeBaseService(new ObjectMapper(), "data");
            homes.load();
            PlayerContextResolver resolver = mock(PlayerContextResolver.class);
            when(resolver.resolvePlayerId(any())).thenReturn(20);
            HomePresenceService presence = new HomePresenceService();
            HomeNettyService netty = new HomeNettyService(homes, resolver, presence,
                    new FurnitureInteractHandler(homes, presence),
                    provider((GameSessionManager) null), provider(logs));

            Channel ch = loggedIn(20);
            HomeSystemProto.HomeVisitScRsp visit = netty.handleVisit(
                    HomeSystemProto.HomeVisitCsReq.newBuilder().setHostPlayerId(10).build(), ch);
            assertEquals(0, visit.getRetcode());
            assertEquals(1, logs.unreadCount(10));

            HomeSystemProto.HomeLeaveMessageScRsp msg = netty.handleLeaveMessage(
                    HomeSystemProto.HomeLeaveMessageCsReq.newBuilder()
                            .setHostPlayerId(10).setMessage("你好").setLike(true).build(), ch);
            assertEquals(0, msg.getRetcode());

            when(resolver.resolvePlayerId(any())).thenReturn(10);
            HomeSystemProto.GetHomeVisitorLogScRsp list = netty.handleGetVisitorLog(20, loggedIn(10));
            assertEquals(0, list.getRetcode());
            assertTrue(list.getEntriesCount() >= 2);

            HomeSystemProto.HomeVisitorLogNotify notify = netty.buildVisitorLogNotify(10);
            assertTrue(notify.getUnreadCount() >= 1);
            assertEquals(0, logs.unreadCount(10));
        }

        @Test
        @DisplayName("上线推送好友 FriendOnlineNotify（含 Plane）")
        void publishOnlineNotifiesLocalFriends() throws Exception {
            FriendEntity rel = new FriendEntity();
            rel.setPlayerId1(1);
            rel.setPlayerId2(2);
            rel.setStatus(1);
            FriendApplicationService friends = mock(FriendApplicationService.class);
            when(friends.listFriends(1)).thenReturn(List.of(rel));

            EmbeddedChannel friendCh = new EmbeddedChannel();
            GameSessionManager sessions = mock(GameSessionManager.class);
            when(sessions.getOrNull(2L)).thenReturn(new GameSession(2L, friendCh, null));

            FriendOnlineStatusService online = new FriendOnlineStatusService(
                    friends, sessions, provider((SceneManager) null),
                    provider((org.springframework.data.redis.core.StringRedisTemplate) null));
            online.publishOnline(1);

            GamePacket pkt = friendCh.readOutbound();
            assertNotNull(pkt);
            assertEquals(CmdIds.FRIEND_ONLINE_SC_NOTIFY, pkt.getCmdId());
            HallSystemProto.FriendOnlineNotify n =
                    HallSystemProto.FriendOnlineNotify.parseFrom(pkt.getPayload());
            assertEquals(1, n.getFriendPlayerId());
            assertTrue(n.getOnline());

            online.publishOffline(1);
            GamePacket off = friendCh.readOutbound();
            HallSystemProto.FriendOnlineNotify offN =
                    HallSystemProto.FriendOnlineNotify.parseFrom(off.getPayload());
            assertFalse(offN.getOnline());
        }
    }

    @Nested
    @DisplayName("5. 成就实时推送 + 材料反查")
    class AchievementAndItemSourceFlow {

        @Test
        @DisplayName("进度达标立即推送 AchievementUnlockPush")
        void achievementUnlockPushesToast() throws Exception {
            AtomicInteger progress = new AtomicInteger(0);
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            when(jdbc.update(anyString(), any(), any(), any(), any())).thenAnswer(inv -> {
                progress.addAndGet(((Number) inv.getArgument(1)).intValue());
                return 1;
            });
            when(jdbc.queryForObject(anyString(), eq(Integer.class), any(), any()))
                    .thenAnswer(inv -> progress.get());

            EmbeddedChannel ch = new EmbeddedChannel();
            GameSessionManager sessions = mock(GameSessionManager.class);
            when(sessions.getOrNull(33L)).thenReturn(new GameSession(33L, ch, null));
            AnalyticsEventPublisher analytics = mock(AnalyticsEventPublisher.class);

            AchievementService svc = new AchievementService(jdbc, provider(analytics), provider(sessions));
            svc.addProgress(33, "gacha_10", 10);

            verify(analytics).track(eq("achievement_unlock"), eq(33), any());
            GamePacket pkt = ch.readOutbound();
            assertEquals(CmdIds.ACHIEVEMENT_UNLOCK_PUSH_SC_NOTIFY, pkt.getCmdId());
            AchievementSystemProto.AchievementUnlockPushScNotify n =
                    AchievementSystemProto.AchievementUnlockPushScNotify.parseFrom(pkt.getPayload());
            assertEquals("gacha_10", n.getAchievementId());
            assertTrue(n.getCanClaim());
            assertEquals(10, n.getTarget());
        }

        @Test
        @DisplayName("材料反查返回 stage_ids 与开战/扫荡入口")
        void queryItemSourceReturnsShortcuts() {
            PlayerContextResolver resolver = mock(PlayerContextResolver.class);
            when(resolver.resolvePlayerId(any())).thenReturn(1);
            ItemNettyService netty = new ItemNettyService(
                    new ItemApplicationService(mock(ItemRepository.class)),
                    mock(cn.itcast.demo.mylunarcore.net.mapper.ItemProtoMapper.class),
                    resolver, mock(cn.itcast.demo.mylunarcore.player.PlayerDataSyncService.class));
            ItemSystemProto.QueryItemSourceScRsp rsp = netty.handleQueryItemSource(
                    ItemSystemProto.QueryItemSourceCsReq.newBuilder().setItemId(201).build(), loggedIn(1));
            assertEquals(0, rsp.getRetcode());
            assertFalse(rsp.getStageIdsList().isEmpty());
            assertEquals("FightStartCsReq", rsp.getEnterCmd());
            assertEquals("SweepStageCsReq", rsp.getSweepCmd());
            assertTrue(rsp.getStages(0).getChallengeUnlocked());

            when(resolver.resolvePlayerId(any())).thenReturn(0);
            assertEquals(1, netty.handleQueryItemSource(
                    ItemSystemProto.QueryItemSourceCsReq.newBuilder().setItemId(201).build(),
                    new EmbeddedChannel()).getRetcode());
        }
    }

    @Nested
    @DisplayName("6. 对话 SAVEPOINT + 过场回放")
    class DialogueSavepointReplayFlow {

        @Test
        @DisplayName("选项节点落存档点，Resume 回到该节点")
        void choiceMarksSavepointThenResume() {
            ObjectMapper mapper = new ObjectMapper();
            DialogueTreeRepository trees = new DialogueTreeRepository(mapper, "data");
            assertTrue(trees.reload());
            DialogueProgressService progress = new DialogueProgressService();
            CutsceneTriggerService cutscenes = new CutsceneTriggerService(mapper, "data");
            assertTrue(cutscenes.reload());
            DialogueTriggerEngine engine = new DialogueTriggerEngine(trees, progress, cutscenes);

            assertTrue(engine.startByNpc(4001, "1001").ok());
            assertTrue(engine.choose(4001, "1001", "1").ok());
            DialogueProgressService.Progress p = progress.find(4001, "1001");
            assertNotNull(p);
            assertFalse(p.savepointNodeId().isBlank());

            DialogueTriggerEngine.DialogueStepResult resume = engine.resumeFromBranch(4001, "1001");
            assertTrue(resume.ok());
            assertEquals(p.savepointNodeId(), resume.node().nodeId());
        }

        @Test
        @DisplayName("已播过场才发 replay ticket；未完成拒绝")
        void replayRequiresCompletedCutscene() {
            ObjectMapper mapper = new ObjectMapper();
            CutsceneTriggerService cutscenes = new CutsceneTriggerService(mapper, "data");
            assertTrue(cutscenes.reload());
            DialogueCutsceneNettyService netty = new DialogueCutsceneNettyService(
                    mock(DialogueTriggerEngine.class), cutscenes, mockResolver(5001));

            DialogueCutsceneProto.ReplayCutsceneScRsp denied = netty.handleReplayCutscene(
                    DialogueCutsceneProto.ReplayCutsceneCsReq.newBuilder().setCutsceneId("cs_intro_01").build(),
                    loggedIn(5001));
            assertEquals(2, denied.getRetcode());

            assertTrue(cutscenes.complete(5001, "cs_intro_01").ok());
            DialogueCutsceneProto.ReplayCutsceneScRsp ok = netty.handleReplayCutscene(
                    DialogueCutsceneProto.ReplayCutsceneCsReq.newBuilder().setCutsceneId("cs_intro_01").build(),
                    loggedIn(5001));
            assertEquals(0, ok.getRetcode());
            assertFalse(ok.getReplayTicket().isBlank());
            assertTrue(ok.getTimelineAsset().contains("intro"));
        }
    }

    @Nested
    @DisplayName("7. 匹配排队不锁界面 + Ready Check")
    class MatchReadyCheckFlow {

        @Test
        @DisplayName("排队保持 HALL，成局 READY_CHECK，确认后 LOCKED")
        void queueThenReadyCheckLocks() throws Exception {
            RoomService rooms = new RoomService();
            GameSession s1 = new GameSession(101L, new EmbeddedChannel(), null);
            GameSession s2 = new GameSession(102L, new EmbeddedChannel(), null);
            s1.setSessionState(PlayerSessionState.HALL);
            s2.setSessionState(PlayerSessionState.HALL);
            GameSessionManager sessions = mock(GameSessionManager.class);
            when(sessions.getOrNull(101L)).thenReturn(s1);
            when(sessions.getOrNull(102L)).thenReturn(s2);

            MatchmakingService mm = new MatchmakingService(
                    rooms, sessions, new LunarCoreProperties(), new BusinessMetrics(new SimpleMeterRegistry()));
            assertTrue(mm.joinQueue(101, 1, 10, 100).success());
            assertEquals(PlayerSessionState.HALL, s1.getSessionState());
            assertEquals(MatchPhase.QUEUED, s1.getMatchPhase());

            assertTrue(mm.joinQueue(102, 1, 10, 100).success());
            mm.batchMatchTick();
            assertEquals(MatchPhase.READY_CHECK, s1.getMatchPhase());
            GamePacket successPkt = ((EmbeddedChannel) s1.getChannel()).readOutbound();
            assertEquals(CmdIds.MATCH_SUCCESS_SC_NOTIFY, successPkt.getCmdId());
            MatchmakingSystemProto.MatchSuccessScNotify n =
                    MatchmakingSystemProto.MatchSuccessScNotify.parseFrom(successPkt.getPayload());
            assertEquals(10_000, n.getReadyTimeoutMs());

            MatchNettyService netty = new MatchNettyService(mm, rooms, mockResolver(101));
            MatchmakingSystemProto.MatchReadyCheckScRsp rsp = netty.handleReadyCheck(
                    MatchmakingSystemProto.MatchReadyCheckCsReq.newBuilder()
                            .setRoomId(n.getRoomId()).setReady(true).build(),
                    loggedIn(101));
            assertEquals(0, rsp.getRetcode());
            assertTrue(rsp.getLocked());
            assertEquals(MatchPhase.LOCKED, s1.getMatchPhase());
            assertEquals(PlayerSessionState.MATCHING, s1.getSessionState());
        }

        @Test
        @DisplayName("取消排队恢复 IDLE，未登录 Ready Check 失败")
        void cancelAndUnauthReadyCheck() {
            RoomService rooms = new RoomService();
            GameSession s = new GameSession(201L, new EmbeddedChannel(), null);
            s.setSessionState(PlayerSessionState.HALL);
            GameSessionManager sessions = mock(GameSessionManager.class);
            when(sessions.getOrNull(201L)).thenReturn(s);
            MatchmakingService mm = new MatchmakingService(
                    rooms, sessions, new LunarCoreProperties(), new BusinessMetrics(new SimpleMeterRegistry()));
            mm.joinQueue(201, 1, 1, 1);
            assertTrue(mm.cancelQueue(201, 1));
            assertEquals(MatchPhase.IDLE, s.getMatchPhase());

            MatchNettyService netty = new MatchNettyService(mm, rooms,
                    new PlayerContextResolver(mock(GameSessionManager.class)));
            assertEquals(1, netty.handleReadyCheck(
                    MatchmakingSystemProto.MatchReadyCheckCsReq.newBuilder().setRoomId(1).setReady(true).build(),
                    new EmbeddedChannel()).getRetcode());
        }
    }

    @Nested
    @DisplayName("8. 多端输入协商")
    class InputCapabilityFlow {

        @Test
        @DisplayName("LoginCsReq/ScRsp 往返 input_methods 与推荐布局")
        void loginProtoRoundTripInputAndLayout() throws Exception {
            PlayerSessionProto.PlayerLoginCsReq req = PlayerSessionProto.PlayerLoginCsReq.newBuilder()
                    .setUsername("u").setPassword("p").setWireVersion(3)
                    .setInputMethods(InputCapability.TOUCH | InputCapability.GAMEPAD)
                    .setDeviceId("iphone")
                    .setSupportedFeatures(ClientFeatureFlags.SERVER_ALL)
                    .build();
            PlayerSessionProto.PlayerLoginCsReq parsedReq =
                    PlayerSessionProto.PlayerLoginCsReq.parseFrom(req.toByteArray());
            assertEquals(InputCapability.TOUCH | InputCapability.GAMEPAD, parsedReq.getInputMethods());

            String layout = InputCapability.recommendedLayoutId(parsedReq.getInputMethods(), parsedReq.getDeviceId());
            assertEquals(InputCapability.LAYOUT_MOBILE_TOUCH, layout);

            PlayerSessionProto.PlayerLoginScRsp rsp = PlayerSessionProto.PlayerLoginScRsp.newBuilder()
                    .setRetcode(0)
                    .setWireVersion(CmdIds.PROTOCOL_WIRE_VERSION)
                    .setInputMethods(InputCapability.normalize(parsedReq.getInputMethods()))
                    .setRecommendedLayoutId(layout)
                    .setEnabledFeatures(ClientFeatureFlags.SERVER_ALL
                            & (ClientFeatureFlags.INPUT_TOUCH | ClientFeatureFlags.LAYOUT_HINT))
                    .build();
            PlayerSessionProto.PlayerLoginScRsp parsedRsp =
                    PlayerSessionProto.PlayerLoginScRsp.parseFrom(rsp.toByteArray());
            assertEquals(3, parsedRsp.getWireVersion());
            assertEquals(layout, parsedRsp.getRecommendedLayoutId());
            ProtocolCompatService compat = new ProtocolCompatService();
            assertTrue(compat.isCompatible(2));
            assertTrue(compat.isCompatible(3));
            assertFalse(compat.isCompatible(1));
        }
    }

    @Nested
    @DisplayName("协议 Handler 号段 1020–1045")
    class HandlerBindingFlow {

        @Test
        @DisplayName("新 CmdId 均已绑定 PacketCmd")
        void newExperienceCmdsAreRegistered() {
            assertTrue(hasPacketCmd(BattlePacketHandlers.class, CmdIds.SET_BATTLE_AUTO_CS_REQ));
            assertTrue(hasPacketCmd(BattlePacketHandlers.class, CmdIds.SET_BATTLE_SPEED_CS_REQ));
            assertTrue(hasPacketCmd(DailyLoopPacketHandlers.class, CmdIds.CLAIM_RESERVE_STAMINA_CS_REQ));
            assertTrue(hasPacketCmd(HomePacketHandlers.class, CmdIds.GET_HOME_VISITOR_LOG_CS_REQ));
            assertTrue(hasPacketCmd(HomePacketHandlers.class, CmdIds.HOME_LEAVE_MESSAGE_CS_REQ));
            assertTrue(hasPacketCmd(ItemPacketHandlers.class, CmdIds.QUERY_ITEM_SOURCE_CS_REQ));
            assertTrue(hasPacketCmd(DialogueCutscenePacketHandlers.class, CmdIds.RESUME_FROM_BRANCH_CS_REQ));
            assertTrue(hasPacketCmd(DialogueCutscenePacketHandlers.class, CmdIds.REPLAY_CUTSCENE_CS_REQ));
            assertTrue(hasPacketCmd(MatchPacketHandlers.class, CmdIds.MATCH_READY_CHECK_CS_REQ));
            assertTrue(hasPacketCmd(CharacterPacketHandlers.class, CmdIds.CALCULATE_UPGRADE_MATERIALS_CS_REQ));
            assertTrue(hasPacketCmd(DailyLoopPacketHandlers.class, CmdIds.BATCH_SWEEP_CS_REQ));
            assertEquals(1020, CmdIds.SET_BATTLE_AUTO_CS_REQ);
            assertEquals(1045, CmdIds.MATCH_READY_CHECK_SC_RSP);
            assertEquals(1069, CmdIds.HOME_OVERFLOW_SC_NOTIFY);
        }
    }

    private static BattleContext newBattle(long battleId, int playerId) {
        List<BattleMonsterWaveRepository.WaveConfig> waves = new ArrayList<>();
        waves.add(new BattleMonsterWaveRepository.WaveConfig(1, 100, 1, "[301]", 5));
        return BattleContext.createNew(battleId, playerId, 1, 100, 1_700_000_000L, waves);
    }

    private static int firstMonsterHp(BattleContext ctx) {
        int id = ctx.listAliveMonsterIdsInCurrentWave().get(0);
        return ctx.getEntity(id).getHp();
    }

    private static Channel loggedIn(long uid) {
        EmbeddedChannel ch = new EmbeddedChannel();
        ch.attr(PlayerChannelAttributes.PLAYER_UID).set(uid);
        return ch;
    }

    private static PlayerContextResolver mockResolver(int playerId) {
        PlayerContextResolver resolver = mock(PlayerContextResolver.class);
        when(resolver.resolvePlayerId(any())).thenReturn(playerId);
        return resolver;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    private static JdbcTemplate reserveJdbc(Map<Integer, Integer> store) {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenAnswer(inv ->
                store.getOrDefault(((Number) inv.getArgument(2)).intValue(), 0));
        when(jdbc.update(anyString(), any(), any(), any())).thenAnswer(inv -> {
            store.put(((Number) inv.getArgument(1)).intValue(), ((Number) inv.getArgument(3)).intValue());
            return 1;
        });
        when(jdbc.update(anyString(), any(), any())).thenAnswer(inv -> {
            store.put(((Number) inv.getArgument(2)).intValue(), ((Number) inv.getArgument(1)).intValue());
            return 1;
        });
        return jdbc;
    }

    private static boolean hasPacketCmd(Class<?> handler, int cmdId) {
        for (Method m : handler.getDeclaredMethods()) {
            PacketCmd cmd = m.getAnnotation(PacketCmd.class);
            if (cmd != null && cmd.value() == cmdId) {
                return true;
            }
        }
        ReflectionUtils.doWithMethods(handler, method -> {
        });
        return false;
    }
}
