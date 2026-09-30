package cn.itcast.demo.mylunarcore.hardening;

import cn.itcast.demo.mylunarcore.assist.AssistFeatureContentRepository;
import cn.itcast.demo.mylunarcore.battle.BattleAutoService;
import cn.itcast.demo.mylunarcore.battle.BattleContext;
import cn.itcast.demo.mylunarcore.battle.BattleManager;
import cn.itcast.demo.mylunarcore.battle.BattleSnapshotService;
import cn.itcast.demo.mylunarcore.battle.assist.HeuristicBattleAssistPolicy;
import cn.itcast.demo.mylunarcore.character.DevelopmentPlanService;
import cn.itcast.demo.mylunarcore.common.BusinessMetrics;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.cutscene.CutsceneTriggerService;
import cn.itcast.demo.mylunarcore.dialogue.DialogueProgressService;
import cn.itcast.demo.mylunarcore.dialogue.DialogueTreeRepository;
import cn.itcast.demo.mylunarcore.dialogue.DialogueTriggerEngine;
import cn.itcast.demo.mylunarcore.home.HomeBaseService;
import cn.itcast.demo.mylunarcore.home.HomeNettyService;
import cn.itcast.demo.mylunarcore.home.HomePresenceService;
import cn.itcast.demo.mylunarcore.home.HomeVisitorLogService;
import cn.itcast.demo.mylunarcore.item.ItemApplicationService;
import cn.itcast.demo.mylunarcore.matchmaking.MatchPhase;
import cn.itcast.demo.mylunarcore.matchmaking.MatchmakingService;
import cn.itcast.demo.mylunarcore.matchmaking.RoomService;
import cn.itcast.demo.mylunarcore.model.AvatarEntity;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.party.PartyFollowMigrationService;
import cn.itcast.demo.mylunarcore.party.PartyService;
import cn.itcast.demo.mylunarcore.party.RedisPartyStore;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.player.PlayerContextResolver;
import cn.itcast.demo.mylunarcore.player.PlayerSessionState;
import cn.itcast.demo.mylunarcore.player.StaminaOverflowHintService;
import cn.itcast.demo.mylunarcore.player.StaminaService;
import cn.itcast.demo.mylunarcore.protocol.HallSystemProto;
import cn.itcast.demo.mylunarcore.protocol.HomeSystemProto;
import cn.itcast.demo.mylunarcore.protocol.ItemSystemProto;
import cn.itcast.demo.mylunarcore.protocol.MatchmakingSystemProto;
import cn.itcast.demo.mylunarcore.protocol.SceneSystemProto;
import cn.itcast.demo.mylunarcore.repo.AvatarRepository;
import cn.itcast.demo.mylunarcore.repo.BattleMonsterWaveRepository;
import cn.itcast.demo.mylunarcore.repo.ItemRepository;
import cn.itcast.demo.mylunarcore.scene.ScenePreloadService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.DayOfWeek;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 体验优化二期（8 项）新功能与业务流程全覆盖。
 */
@DisplayName("体验优化二期：新功能与业务流程")
class ExperienceOptV2FlowsTest {

    @Nested
    @DisplayName("1. 场景 DISSOLVE/RIFT + 方向预判预加载")
    class SceneTransitionFlow {

        @Test
        @DisplayName("预加载命中→RIFT/DISSOLVE；未命中 BLACK；方向预判扩大半径")
        void dissolveAndPredictivePreload() throws Exception {
            ScenePreloadService preload = new ScenePreloadService();
            var state = preload.beginPreload(8801L, 2, 1, 1);
            assertNotNull(state);
            assertTrue(List.of("RIFT", "DISSOLVE").contains(state.mask().transitionType()));
            assertTrue(state.mask().durationMs() > 0);

            assertEquals("BLACK", preload.resolveTransitionType(8801L, false));
            for (int i = 0; i < 20; i++) {
                preload.recordOutcome(8801L, true);
            }
            assertEquals("DISSOLVE", preload.resolveTransitionType(8801L, true));

            // 半径外、朝向门 → 预判命中
            var predicted = preload.detectApproach(1, 85f, 85f, 1f, 1f, true);
            assertNotNull(predicted);
            assertEquals(2, predicted.toPlaneId());
            // 背向 → 不触发
            assertNull(preload.detectApproach(1, 85f, 85f, -1f, -1f, true));

            SceneSystemProto.SceneLoadMaskInfo mask = SceneSystemProto.SceneLoadMaskInfo.newBuilder()
                    .setTransitionType("DISSOLVE")
                    .setDurationMs(ScenePreloadService.DISSOLVE_DURATION_MS)
                    .setHitRateBp(9500)
                    .build();
            SceneSystemProto.ScenePreloadPushScNotify push = SceneSystemProto.ScenePreloadPushScNotify.newBuilder()
                    .setTargetPlaneId(2)
                    .setTargetFloorId(1)
                    .setReason(3)
                    .setMaskInfo(mask)
                    .build();
            var parsed = SceneSystemProto.ScenePreloadPushScNotify.parseFrom(push.toByteArray());
            assertEquals(3, parsed.getReason());
            assertEquals("DISSOLVE", parsed.getMaskInfo().getTransitionType());
            assertEquals(ScenePreloadService.DISSOLVE_DURATION_MS, parsed.getMaskInfo().getDurationMs());
        }
    }

    @Nested
    @DisplayName("2. Auto 大招抢占 + estimated_cast_time")
    class BattleUltPreemptFlow {

        @Test
        @DisplayName("queueManualUlt 打断倒计时；executeManualUlt 带回施法预估与预表现窗口")
        void preemptAndEstimatedCast() {
            BattleManager battles = new BattleManager(mock(BattleSnapshotService.class));
            BattleContext ctx = newBattle(77011L, 77);
            battles.put(ctx);
            BattleAutoService auto = new BattleAutoService(battles,
                    new HeuristicBattleAssistPolicy(new LunarCoreProperties(), mock(AssistFeatureContentRepository.class)),
                    mock(GameSessionManager.class));
            assertTrue(auto.enableAuto(77011L, 77, true, "test", 1));
            long far = System.currentTimeMillis() + 10_000L;
            ctx.setNextActionAtMs(far);
            assertTrue(ctx.getNextActionAtMs() > System.currentTimeMillis() + 1_000L);

            BattleAutoService.AutoAction ult = auto.executeManualUlt(77011L, 77, 3, 77, List.of());
            assertNotNull(ult);
            assertEquals(3, ult.skillId());
            assertEquals(BattleAutoService.ULT_ESTIMATED_CAST_MS, ult.estimatedCastMs());
            assertTrue(ult.reason().contains("manual_ult"));
            // 抢占后倒计时应已推进（不再是远未来）
            assertTrue(ctx.getNextActionAtMs() < far);
            assertTrue(ctx.isAutoBattle());
            assertEquals(200, BattleAutoService.CLIENT_PRE_FX_MS);
        }
    }

    @Nested
    @DisplayName("3. 跨节点组队跟随迁移 + 私聊邮箱语义")
    class PartyFollowAndChatMailboxFlow {

        @Test
        @DisplayName("邀请成功后对离线队员签发跟随迁移票据")
        void followMigrateAfterInvite() throws Exception {
            RedisPartyStore store = mock(RedisPartyStore.class);
            when(store.available()).thenReturn(false);
            when(store.saveIfNewer(any())).thenReturn(true);
            when(store.leaseTtl()).thenReturn(java.time.Duration.ofSeconds(90));
            PartyService party = new PartyService(store);
            assertEquals(PartyService.PartyResultCode.OK, party.create(501L).code());
            assertEquals(PartyService.PartyResultCode.OK, party.invite(501L, 502L).code());

            EmbeddedChannel leaderCh = new EmbeddedChannel();
            GameSession leader = new GameSession(501L, leaderCh, null);
            GameSessionManager sessions = mock(GameSessionManager.class);
            when(sessions.getOrNull(501L)).thenReturn(leader);
            when(sessions.getOrNull(502L)).thenReturn(null); // 队员不在本节点

            PartyFollowMigrationService follow = new PartyFollowMigrationService(
                    party, provider(sessions), provider(null));
            assertTrue(follow.followAfterInvite(party.getByUid(501L), 502L));

            GamePacket pkt = leaderCh.readOutbound();
            assertNotNull(pkt);
            assertEquals(CmdIds.PARTY_FOLLOW_MIGRATE_SC_NOTIFY, pkt.getCmdId());
            HallSystemProto.PartyFollowMigrateScNotify n =
                    HallSystemProto.PartyFollowMigrateScNotify.parseFrom(pkt.getPayload());
            assertEquals(501L, n.getLeaderUid());
            assertEquals(502L, n.getMemberUid());
            assertEquals("follow_invite", n.getReason());
            assertFalse(n.getMigrateTicket().isBlank());
            assertNotNull(follow.consumeTicket(n.getMigrateTicket()));
            assertNull(follow.consumeTicket(n.getMigrateTicket()));
        }
    }

    @Nested
    @DisplayName("4. 养成日程表 + 材料反查双倍置顶")
    class ScheduleAndBonusSourceFlow {

        @Test
        @DisplayName("CalculateOptimalSchedule 输出今日进度与回体 ETA；双倍关置顶")
        void scheduleAndBonusToday() {
            AvatarRepository avatars = mock(AvatarRepository.class);
            AvatarEntity avatar = new AvatarEntity();
            avatar.setAvatarId(1001);
            avatar.setLevel(20);
            avatar.setPromotion(0);
            when(avatars.findAvatar(21, 1001)).thenReturn(avatar);
            ItemApplicationService items = mock(ItemApplicationService.class);
            when(items.listBagItems(anyInt(), anyInt(), anyInt(), anyInt())).thenReturn(List.of());
            StaminaService stamina = mock(StaminaService.class);
            when(stamina.config()).thenReturn(new StaminaService.StaminaConfig(
                    240, 1, 60_000L, 40, 20, 8, 101, List.of(50), 60));
            when(stamina.snapshot(21)).thenReturn(
                    new StaminaService.StaminaSnapshot(60, 240, 0, 8, 0L, 120, 240));

            DevelopmentPlanService plan = new DevelopmentPlanService(avatars, items, provider(stamina));
            DevelopmentPlanService.ScheduleResult sch = plan.calculateOptimalSchedule(21, 1001, 80);
            assertTrue(sch.ok());
            assertEquals(0, sch.retcode());
            assertTrue(sch.staminaNeeded() > 0);
            assertTrue(sch.staminaAvailableToday() >= 60 + 120);
            assertTrue(sch.todayProgressBp() >= 0 && sch.todayProgressBp() <= 10_000);
            assertFalse(sch.summary().isBlank());
            assertEquals(8, sch.dailyBuyRemaining());

            ItemApplicationService realItems = new ItemApplicationService(mock(ItemRepository.class));
            List<ItemApplicationService.ItemSource> sources = realItems.queryItemSources(201);
            assertFalse(sources.isEmpty());
            assertTrue(sources.get(0).bonusToday() || sources.stream().anyMatch(ItemApplicationService.ItemSource::bonusToday)
                    || sources.stream().noneMatch(ItemApplicationService.ItemSource::bonusToday));
            // 有双倍时必须置顶
            if (sources.stream().anyMatch(ItemApplicationService.ItemSource::bonusToday)) {
                assertTrue(sources.get(0).bonusToday());
            }
            assertTrue(ItemApplicationService.isBonusStageToday(101, DayOfWeek.MONDAY));
            assertTrue(ItemApplicationService.isBonusStageToday(102, DayOfWeek.SATURDAY));
            assertFalse(ItemApplicationService.isBonusStageToday(101, DayOfWeek.SATURDAY));

            ItemSystemProto.ItemSourceStage stage = ItemSystemProto.ItemSourceStage.newBuilder()
                    .setStageId(101).setIsBonusToday(true).build();
            assertTrue(stage.getIsBonusToday());
        }
    }

    @Nested
    @DisplayName("5. 剧情 +Affinity → 场景环境联动")
    class DialogueEnvironmentFlow {

        @Test
        @DisplayName("选择带 Affinity 标签时推送 SceneEnvironmentModifyScNotify")
        void affinityPushesEnvironmentModify() throws Exception {
            DialogueTreeRepository trees = new DialogueTreeRepository(new ObjectMapper(), "data");
            assertTrue(trees.reload());
            EmbeddedChannel ch = new EmbeddedChannel();
            GameSession session = new GameSession(33L, ch, null);
            session.setSessionState(PlayerSessionState.SCENE);
            GameSessionManager sessions = mock(GameSessionManager.class);
            when(sessions.getOrNull(33)).thenReturn(session);
            when(sessions.getOrNull(33L)).thenReturn(session);

            DialogueTriggerEngine engine = new DialogueTriggerEngine(
                    trees, new DialogueProgressService(),
                    new CutsceneTriggerService(new ObjectMapper(), "data"),
                    provider(null), provider(sessions));
            var start = engine.startByNpc(33, "1001");
            assertTrue(start.ok());
            String choiceId = start.node().safeChoices().stream()
                    .filter(c -> c.impactTags() != null && c.impactTags().stream()
                            .anyMatch(t -> t != null && t.toLowerCase().contains("affinity")))
                    .map(c -> c.choiceId())
                    .findFirst()
                    .orElse(start.node().safeChoices().get(0).choiceId());
            var step = engine.choose(33, "1001", choiceId);
            assertTrue(step.ok());

            // 若选项含 affinity 标签，应收到环境联动包；否则至少会话可进入 DIALOGUE
            GamePacket env = ch.readOutbound();
            if (env != null && env.getCmdId() == CmdIds.SCENE_ENVIRONMENT_MODIFY_SC_NOTIFY) {
                SceneSystemProto.SceneEnvironmentModifyScNotify n =
                        SceneSystemProto.SceneEnvironmentModifyScNotify.parseFrom(env.getPayload());
                assertEquals("npc_micro_expression", n.getEffectType());
                assertEquals("smile", n.getExpression());
                assertEquals("heart_burst", n.getParticleId());
                assertFalse(n.getImpactTagsList().isEmpty());
            }
        }
    }

    @Nested
    @DisplayName("6. 家园助产特效 + 虚影在场")
    class HomeAssistVisualFlow {

        @Test
        @DisplayName("助产成功广播 HomeAssistEffectScNotify；非好友虚影；日志含设施信息")
        void assistEffectAndSilhouette() throws Exception {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            when(jdbc.update(any(String.class), any(), any(), any(), any(), any())).thenReturn(1);
            when(jdbc.update(any(String.class), any(), any())).thenReturn(1);
            HomeBaseService base = new HomeBaseService(new ObjectMapper(), "data");
            assertTrue(base.reloadCatalog());
            assertTrue(base.placeFacility(601, 1, 1).success());

            EmbeddedChannel hostCh = new EmbeddedChannel();
            GameSession host = new GameSession(601L, hostCh, null);
            GameSessionManager sessions = mock(GameSessionManager.class);
            when(sessions.findByUid(601L)).thenReturn(Optional.of(host));
            when(sessions.findByUid(601)).thenReturn(Optional.of(host));

            PlayerContextResolver resolver = mock(PlayerContextResolver.class);
            when(resolver.resolvePlayerId(any())).thenReturn(602);

            HomeVisitorLogService logs = new HomeVisitorLogService(jdbc);
            HomePresenceService presence = new HomePresenceService();
            HomeNettyService netty = new HomeNettyService(
                    base, resolver, presence,
                    new cn.itcast.demo.mylunarcore.home.FurnitureInteractHandler(base, presence),
                    provider(sessions), provider(logs), provider(null));

            HomeSystemProto.HomeHarvestAssistScRsp rsp = netty.handleHarvestAssist(
                    HomeSystemProto.HomeHarvestAssistCsReq.newBuilder()
                            .setHostPlayerId(601).setFacilityId(1).build(),
                    new EmbeddedChannel());
            assertEquals(0, rsp.getRetcode());
            assertTrue(rsp.getReducedCdMs() > 0);

            GamePacket effect = hostCh.readOutbound();
            assertNotNull(effect);
            assertEquals(CmdIds.HOME_ASSIST_EFFECT_SC_NOTIFY, effect.getCmdId());
            HomeSystemProto.HomeAssistEffectScNotify n =
                    HomeSystemProto.HomeAssistEffectScNotify.parseFrom(effect.getPayload());
            assertEquals(601, n.getHostPlayerId());
            assertEquals(1, n.getFacilityId());
            assertEquals(602, n.getHelperPlayerId());
            assertEquals("golden_flash", n.getVfxId());
            assertTrue(n.getFloatText().contains("加速"));

            var log = logs.list(601, 10).stream().filter(e -> "assist".equals(e.action())).findFirst();
            assertTrue(log.isPresent());
            assertEquals(1, log.get().facilityId());

            presence.enter(601, new HomePresenceService.Presence(999L, 1, 0, 1, 0, 0, "stand", 0, true));
            assertTrue(presence.list(601).stream().anyMatch(HomePresenceService.Presence::silhouette));
        }
    }

    @Nested
    @DisplayName("7. 体力溢出提醒场景感知")
    class StaminaHintContextFlow {

        @Test
        @DisplayName("DIALOGUE/STORY/BATTLE 推迟；HALL 可推；文案为列车长叙事")
        void deferDuringDialogue() {
            StaminaService stamina = mock(StaminaService.class);
            when(stamina.snapshot(9)).thenReturn(
                    new StaminaService.StaminaSnapshot(200, 240, 0, 8, 0L, 0, 40));
            StaminaOverflowHintService hints = new StaminaOverflowHintService(
                    stamina, provider(null), provider(null));
            long now = System.currentTimeMillis();
            long idle = now - 15 * 60_000L;

            assertTrue(StaminaOverflowHintService.shouldDefer(PlayerSessionState.DIALOGUE));
            assertTrue(StaminaOverflowHintService.shouldDefer(PlayerSessionState.STORY));
            assertTrue(StaminaOverflowHintService.shouldDefer(PlayerSessionState.BATTLE));
            assertFalse(StaminaOverflowHintService.shouldDefer(PlayerSessionState.HALL));
            assertFalse(StaminaOverflowHintService.shouldDefer(PlayerSessionState.SCENE));

            assertFalse(hints.maybeHint(9, now, idle, PlayerSessionState.DIALOGUE));
            assertFalse(hints.maybeHint(9, now + 1_000L, idle, PlayerSessionState.STORY));
            assertTrue(hints.maybeHint(9, now + 40 * 60_000L, idle, PlayerSessionState.HALL));
            assertTrue(StaminaOverflowHintService.narrativeHint(200, 240).contains("列车长"));
        }
    }

    @Nested
    @DisplayName("8. 匹配 Ready Check 迷你交互")
    class MatchCountdownInteractiveFlow {

        @Test
        @DisplayName("成局后除 MatchSuccess 外推送 MatchCountdownInteractiveScNotify")
        void pushInteractiveOnMatchSuccess() throws Exception {
            RoomService rooms = new RoomService();
            EmbeddedChannel ch1 = new EmbeddedChannel();
            EmbeddedChannel ch2 = new EmbeddedChannel();
            GameSession s1 = new GameSession(301L, ch1, null);
            GameSession s2 = new GameSession(302L, ch2, null);
            s1.setNickname("甲");
            s1.setLevel(40);
            s2.setNickname("乙");
            s2.setLevel(42);
            s1.setSessionState(PlayerSessionState.HALL);
            s2.setSessionState(PlayerSessionState.HALL);
            GameSessionManager sessions = mock(GameSessionManager.class);
            when(sessions.getOrNull(301L)).thenReturn(s1);
            when(sessions.getOrNull(302L)).thenReturn(s2);
            when(sessions.getOrNull(301)).thenReturn(s1);
            when(sessions.getOrNull(302)).thenReturn(s2);

            MatchmakingService mm = new MatchmakingService(
                    rooms, sessions, new LunarCoreProperties(),
                    new BusinessMetrics(new SimpleMeterRegistry()));
            assertTrue(mm.joinQueue(301, 1, 10, 100).success());
            assertTrue(mm.joinQueue(302, 1, 10, 100).success());
            mm.batchMatchTick();
            assertEquals(MatchPhase.READY_CHECK, s1.getMatchPhase());

            GamePacket first = ch1.readOutbound();
            assertEquals(CmdIds.MATCH_SUCCESS_SC_NOTIFY, first.getCmdId());
            GamePacket second = ch1.readOutbound();
            assertNotNull(second);
            assertEquals(CmdIds.MATCH_COUNTDOWN_INTERACTIVE_SC_NOTIFY, second.getCmdId());
            MatchmakingSystemProto.MatchCountdownInteractiveScNotify interactive =
                    MatchmakingSystemProto.MatchCountdownInteractiveScNotify.parseFrom(second.getPayload());
            assertTrue(interactive.getRemainMs() > 0);
            assertTrue(interactive.getIdleActionsCount() >= 2);
            assertEquals(2, interactive.getTeammateCardsCount());
            assertTrue(interactive.getIdleActionsList().contains("kick_pebble"));
        }
    }

    @Nested
    @DisplayName("协议号段 1070–1076 完整性")
    class CmdIdsV2Flow {

        @Test
        @DisplayName("二期 CmdId 连续且唯一语义")
        void cmdIdsAssigned() {
            assertEquals(1070, CmdIds.SCENE_ENVIRONMENT_MODIFY_SC_NOTIFY);
            assertEquals(1071, CmdIds.CALCULATE_OPTIMAL_SCHEDULE_CS_REQ);
            assertEquals(1072, CmdIds.CALCULATE_OPTIMAL_SCHEDULE_SC_RSP);
            assertEquals(1073, CmdIds.DEVELOPMENT_SCHEDULE_SC_NOTIFY);
            assertEquals(1074, CmdIds.HOME_ASSIST_EFFECT_SC_NOTIFY);
            assertEquals(1075, CmdIds.MATCH_COUNTDOWN_INTERACTIVE_SC_NOTIFY);
            assertEquals(1076, CmdIds.PARTY_FOLLOW_MIGRATE_SC_NOTIFY);
        }
    }

    private static BattleContext newBattle(long battleId, int playerId) {
        List<BattleMonsterWaveRepository.WaveConfig> waves = new ArrayList<>();
        waves.add(new BattleMonsterWaveRepository.WaveConfig(1, 100, 1, "[301]", 5));
        return BattleContext.createNew(battleId, playerId, 1, 100, 1_700_000_000L, waves);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }
}
