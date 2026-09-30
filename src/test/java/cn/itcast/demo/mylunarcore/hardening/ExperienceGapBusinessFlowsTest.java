package cn.itcast.demo.mylunarcore.hardening;

import cn.itcast.demo.mylunarcore.assist.AssistAnswer;
import cn.itcast.demo.mylunarcore.assist.AssistInlineSummaryService;
import cn.itcast.demo.mylunarcore.assist.AssistMediaLink;
import cn.itcast.demo.mylunarcore.assist.AssistNettyService;
import cn.itcast.demo.mylunarcore.battle.BattleFxComposer;
import cn.itcast.demo.mylunarcore.battle.EncounterConfig;
import cn.itcast.demo.mylunarcore.battle.EncounterConfigRepository;
import cn.itcast.demo.mylunarcore.center.MigrationTicketService;
import cn.itcast.demo.mylunarcore.challenge.SweepNettyService;
import cn.itcast.demo.mylunarcore.challenge.SweepService;
import cn.itcast.demo.mylunarcore.economy.RewardDistributor;
import cn.itcast.demo.mylunarcore.home.FurnitureInteractHandler;
import cn.itcast.demo.mylunarcore.home.HomeBaseService;
import cn.itcast.demo.mylunarcore.home.HomeNettyService;
import cn.itcast.demo.mylunarcore.home.HomePresenceService;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.DailyLoopPacketHandlers;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.net.HomePacketHandlers;
import cn.itcast.demo.mylunarcore.net.TutorialPacketHandlers;
import cn.itcast.demo.mylunarcore.player.PlayerContextResolver;
import cn.itcast.demo.mylunarcore.player.PlayerLoadingStateService;
import cn.itcast.demo.mylunarcore.player.StaminaService;
import cn.itcast.demo.mylunarcore.protocol.AssistSystemProto;
import cn.itcast.demo.mylunarcore.protocol.BattleSystemProto;
import cn.itcast.demo.mylunarcore.protocol.DailyLoopSystemProto;
import cn.itcast.demo.mylunarcore.protocol.HomeSystemProto;
import cn.itcast.demo.mylunarcore.protocol.NewbieGuideSystemProto;
import cn.itcast.demo.mylunarcore.scene.ScenePreloadService;
import cn.itcast.demo.mylunarcore.tutorial.NewbieGuideNettyService;
import cn.itcast.demo.mylunarcore.tutorial.NewbieGuideService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 体验短板六项新功能的业务流程回归：预加载握手、站内攻略、扫荡、家园互动、打击感、引导检查点。
 */
@DisplayName("体验短板新功能业务流程")
class ExperienceGapBusinessFlowsTest {

    @Nested
    @DisplayName("1. 切图预加载 → LoadingTicket 握手 → 遮罩元数据")
    class ScenePreloadHandshakeFlow {

        @Test
        @DisplayName("接近传送门预加载后切图应为握手态，完成即恢复")
        void preloadThenHandshakeComplete() {
            ScenePreloadService preload = new ScenePreloadService();
            PlayerLoadingStateService loading = new PlayerLoadingStateService(new MigrationTicketService());

            ScenePreloadService.PreloadState state = preload.beginPreload(7001L, 201, 2, 1);
            assertTrue(preload.isReady(7001L, 201, 2));
            assertTrue(state.assetKeys().stream().anyMatch(k -> k.contains("lod0")));
            assertEquals("hybrid", state.mask().maskStyle());
            assertTrue(state.mask().illustrationId().contains("201"));

            boolean hit = preload.isReady(7001L, 201, 2);
            preload.consumeIfMatch(7001L, 201, 2);
            var ticket = loading.beginLoading(7001L, 201, 2, 1, 1f, 0f, 1f, hit);
            assertTrue(ticket.handshakeOnly());
            assertTrue(loading.isLoading(7001L));
            assertTrue(loading.isHandshakeOnly(7001L));

            var done = loading.completeLoadingResult(7001L, ticket.ticket());
            assertTrue(done.ok());
            assertTrue(done.handshakeOnly());
            assertFalse(loading.isLoading(7001L));
            assertFalse(preload.isReady(7001L, 201, 2));
        }

        @Test
        @DisplayName("未预加载切图不是握手；错误票据不能解除 LOADING")
        void missPreloadIsFullLoading() {
            PlayerLoadingStateService loading = new PlayerLoadingStateService(new MigrationTicketService());
            var ticket = loading.beginLoading(7002L, 9, 1, 0, 0f, 0f, 0f, false);
            assertFalse(ticket.handshakeOnly());
            assertFalse(loading.completeLoading(7002L, "bad"));
            assertTrue(loading.isLoading(7002L));
            assertTrue(loading.completeLoading(7002L, ticket.ticket()));
        }
    }

    @Nested
    @DisplayName("2. AI 助手：摘要先行 + 禁止外跳浏览器")
    class AssistInAppFlow {

        @Test
        @DisplayName("toAskRsp 应下发 inline_summary、forbid_external_browser、站内 action")
        void protocolForbidsExternalBrowser() {
            AssistAnswer answer = AssistAnswer.of(0, "先破弱点再集火精英。", "llm", List.of(), List.of("ext-guide:eg1"),
                    "免责", "baseline", "zh-CN",
                    List.of(new AssistMediaLink("eg1", "B站攻略", "bilibili", "video",
                                    "https://search.bilibili.com/all?keyword=x", "OPEN_VIDEO", true),
                            new AssistMediaLink("eg2", "官网", "official", "article",
                                    "https://sr.mihoyo.com/", "OPEN_EXTERNAL_LINK", true)),
                    "先破弱点再集火精英。",
                    true);

            AssistSystemProto.AskAiAssistScRsp rsp = AssistNettyService.toAskRsp(answer);
            assertTrue(rsp.getForbidExternalBrowser());
            assertEquals("先破弱点再集火精英。", rsp.getInlineSummary().getText());
            assertEquals(2, rsp.getMediaLinksCount());
            assertEquals("OPEN_VIDEO_INLINE", rsp.getMediaLinks(0).getAction());
            assertEquals("OPEN_IN_APP_WEBVIEW", rsp.getMediaLinks(1).getAction());
            assertEquals("INLINE_WEBVIEW", rsp.getMediaLinks(0).getRenderMode());
        }

        @Test
        @DisplayName("超长回答应裁剪到 200 字且去掉 URL")
        void summaryClipsAndStripsUrl() {
            String body = "打法要点。" + "重复说明。".repeat(80) + "\nhttps://www.bilibili.com/video/x";
            AssistInlineSummaryService.InlineSummary s =
                    new AssistInlineSummaryService().summarize(body, "深渊怎么打", List.of());
            assertTrue(s.text().length() <= AssistInlineSummaryService.MAX_CHARS);
            assertFalse(s.text().contains("https://"));
        }
    }

    @Nested
    @DisplayName("3. 扫荡：历史通关解锁 → 多倍体力 → 协议回包")
    class SweepBusinessFlow {

        private SweepService domain;
        private SweepNettyService netty;
        private StaminaService stamina;

        @org.junit.jupiter.api.BeforeEach
        void setUp() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            stamina = mock(StaminaService.class);
            when(stamina.config()).thenReturn(new StaminaService.StaminaConfig(240, 1, 60_000L, 40, 20, 8, 101,
                    List.of(50), 60));
            when(stamina.tryConsume(anyInt(), anyInt())).thenReturn(StaminaService.ConsumeResult.ok(180));
            EncounterConfigRepository encounters = mock(EncounterConfigRepository.class);
            when(encounters.current()).thenReturn(new EncounterConfig(1, List.of(),
                    List.of(new EncounterConfig.DropEntry(2001, 2, null, null)), 10, 0, 0, 0, 5.0));
            RewardDistributor rewards = mock(RewardDistributor.class);
            when(rewards.grantBattleRewards(anyInt(), anyList(), anyInt(), anyString()))
                    .thenReturn(List.of(new RewardDistributor.GrantedItem(2001, 6)));
            domain = new SweepService(jdbc, stamina, encounters, rewards);
            PlayerContextResolver resolver = mock(PlayerContextResolver.class);
            when(resolver.resolvePlayerId(any())).thenReturn(501);
            netty = new SweepNettyService(domain, resolver);
        }

        @Test
        @DisplayName("未登录 retcode=1；未通关=3；体力不足=2；3 倍扫荡发奖")
        void fullSweepPipeline() {
            PlayerContextResolver anon = mock(PlayerContextResolver.class);
            when(anon.resolvePlayerId(any())).thenReturn(0);
            SweepNettyService anonNetty = new SweepNettyService(domain, anon);
            assertEquals(1, anonNetty.handleSweep(
                    DailyLoopSystemProto.SweepStageCsReq.newBuilder().setBattleStageId(100).setMultiplier(1).build(),
                    new EmbeddedChannel()).getRetcode());

            DailyLoopSystemProto.GetSweepInfoScRsp locked = netty.handleGetInfo(
                    DailyLoopSystemProto.GetSweepInfoCsReq.newBuilder().setBattleStageId(100).build(),
                    new EmbeddedChannel());
            assertEquals(0, locked.getRetcode());
            assertFalse(locked.getUnlocked());

            assertEquals(3, netty.handleSweep(
                    DailyLoopSystemProto.SweepStageCsReq.newBuilder().setBattleStageId(100).setMultiplier(1).build(),
                    new EmbeddedChannel()).getRetcode());

            domain.recordClear(501, 100, 7);
            DailyLoopSystemProto.GetSweepInfoScRsp info = netty.handleGetInfo(
                    DailyLoopSystemProto.GetSweepInfoCsReq.newBuilder().setBattleStageId(100).build(),
                    new EmbeddedChannel());
            assertTrue(info.getUnlocked());
            assertEquals(7, info.getBestTurnCount());
            assertEquals(3, info.getMaxMultiplier());

            when(stamina.tryConsume(anyInt(), anyInt())).thenReturn(StaminaService.ConsumeResult.fail(2));
            assertEquals(2, netty.handleSweep(
                    DailyLoopSystemProto.SweepStageCsReq.newBuilder().setBattleStageId(100).setMultiplier(2).build(),
                    new EmbeddedChannel()).getRetcode());

            when(stamina.tryConsume(anyInt(), anyInt())).thenReturn(StaminaService.ConsumeResult.ok(120));
            DailyLoopSystemProto.SweepStageScRsp ok = netty.handleSweep(
                    DailyLoopSystemProto.SweepStageCsReq.newBuilder().setBattleStageId(100).setMultiplier(3).build(),
                    new EmbeddedChannel());
            assertEquals(0, ok.getRetcode());
            assertEquals(3, ok.getMultiplier());
            assertEquals(60, ok.getStaminaCost());
            assertEquals(120, ok.getStaminaRemaining());
            assertEquals(1, ok.getRewardsCount());
            assertEquals(2001, ok.getRewards(0).getItemId());
        }

        @Test
        @DisplayName("PacketHandler 扫荡应写出 SWEEP_STAGE_SC_RSP")
        void packetSweepWritesRsp() throws Exception {
            DailyLoopPacketHandlers handlers = new DailyLoopPacketHandlers(
                    mock(cn.itcast.demo.mylunarcore.player.StaminaNettyService.class),
                    mock(cn.itcast.demo.mylunarcore.quest.DailyMissionNettyService.class),
                    mock(cn.itcast.demo.mylunarcore.story.StoryChapterNettyService.class),
                    netty,
                    mock(PlayerContextResolver.class),
                    mock(cn.itcast.demo.mylunarcore.net.SensitiveApiRateLimiter.class));
            ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
            when(ctx.channel()).thenReturn(new EmbeddedChannel());
            domain.recordClear(501, 100, 5);
            handlers.onSweepStage(ctx, new GamePacket(CmdIds.SWEEP_STAGE_CS_REQ,
                    DailyLoopSystemProto.SweepStageCsReq.newBuilder()
                            .setBattleStageId(100).setMultiplier(1).build().toByteArray()));
            ArgumentCaptor<GamePacket> cap = ArgumentCaptor.forClass(GamePacket.class);
            verify(ctx).writeAndFlush(cap.capture());
            assertEquals(CmdIds.SWEEP_STAGE_SC_RSP, cap.getValue().getCmdId());
        }
    }

    @Nested
    @DisplayName("4. 家园：摆放家具 → 坐椅互动 → 好友看见 Avatar")
    class HomeSocialPresenceFlow {

        @Test
        @DisplayName("房主坐椅后访客互访应看到 sit 状态")
        void visitSeesHostSitPose(@TempDir Path dir) throws Exception {
            Files.writeString(dir.resolve("HomeFacilityConfigs.json"), """
                    [{"facilityId":1,"name":"矿场","maxLevel":5,"staminaPerHour":10,"slotCount":2,"produceItemId":2001,"producePerHour":3}]
                    """);
            HomeBaseService home = new HomeBaseService(new ObjectMapper(), dir.toString());
            home.load();
            HomePresenceService presence = new HomePresenceService();
            FurnitureInteractHandler interact = new FurnitureInteractHandler(home, presence);

            PlayerContextResolver hostResolver = mock(PlayerContextResolver.class);
            when(hostResolver.resolvePlayerId(any())).thenReturn(42);
            PlayerContextResolver visitorResolver = mock(PlayerContextResolver.class);
            when(visitorResolver.resolvePlayerId(any())).thenReturn(99);

            HomeNettyService hostNetty = new HomeNettyService(home, hostResolver, presence, interact, null);
            HomeNettyService visitorNetty = new HomeNettyService(home, visitorResolver, presence, interact, null);

            assertEquals(0, hostNetty.handlePlaceFurniture(
                    HomeSystemProto.HomePlaceFurnitureCsReq.newBuilder()
                            .setFurnitureId(501).setX(2).setY(0).setZ(3).setRotateY(90).build(),
                    new EmbeddedChannel()).getRetcode());

            HomeSystemProto.HomeFurnitureInteractScRsp sit = hostNetty.handleFurnitureInteract(
                    HomeSystemProto.HomeFurnitureInteractCsReq.newBuilder()
                            .setFurnitureId(501).setAction("sit").setX(2).setY(0).setZ(3).build(),
                    new EmbeddedChannel());
            assertEquals(0, sit.getRetcode());
            assertEquals("sit", sit.getSelfPresence().getIdleAnim());
            assertEquals(501, sit.getSelfPresence().getInteractFurnitureId());

            HomeSystemProto.HomeVisitScRsp visit = visitorNetty.handleVisit(
                    HomeSystemProto.HomeVisitCsReq.newBuilder().setHostPlayerId(42).build(),
                    new EmbeddedChannel());
            assertEquals(0, visit.getRetcode());
            assertTrue(visit.getHostAvatarsCount() >= 1);
            assertTrue(visit.getHostAvatarsList().stream().anyMatch(a ->
                    a.getPlayerUid() == 42 && "sit".equals(a.getIdleAnim())));
            assertTrue(visit.getHostAvatarsList().stream().anyMatch(a -> a.getPlayerUid() == 99));
        }

        @Test
        @DisplayName("非法互动动作 retcode=3；未登录=1")
        void interactRejectsBadActionAndAnon() {
            HomeBaseService home = mock(HomeBaseService.class);
            HomePresenceService presence = new HomePresenceService();
            FurnitureInteractHandler handler = new FurnitureInteractHandler(home, presence);
            assertEquals(3, handler.interact(1, 1, 1, "dance", 0, 0, 0, 0, 0).retcode());
            assertEquals(1, handler.interact(0, 0, 1, "sit", 0, 0, 0, 0, 0).retcode());
        }

        @Test
        @DisplayName("PacketHandler 家具互动应写出 HOME_FURNITURE_INTERACT_SC_RSP")
        void packetInteract(@TempDir Path dir) throws Exception {
            Files.writeString(dir.resolve("HomeFacilityConfigs.json"), "[]");
            HomeBaseService home = new HomeBaseService(new ObjectMapper(), dir.toString());
            home.load();
            PlayerContextResolver resolver = mock(PlayerContextResolver.class);
            when(resolver.resolvePlayerId(any())).thenReturn(7);
            HomeNettyService netty = new HomeNettyService(home, resolver);
            HomePacketHandlers handlers = new HomePacketHandlers(netty);
            ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
            when(ctx.channel()).thenReturn(new EmbeddedChannel());
            handlers.onFurnitureInteract(ctx, new GamePacket(CmdIds.HOME_FURNITURE_INTERACT_CS_REQ,
                    HomeSystemProto.HomeFurnitureInteractCsReq.newBuilder()
                            .setFurnitureId(1).setAction("photo").build().toByteArray()));
            ArgumentCaptor<GamePacket> cap = ArgumentCaptor.forClass(GamePacket.class);
            verify(ctx).writeAndFlush(cap.capture());
            assertEquals(CmdIds.HOME_FURNITURE_INTERACT_SC_RSP, cap.getValue().getCmdId());
        }
    }

    @Nested
    @DisplayName("5. 战斗打击感：权威元数据 → 协议 BattleFx")
    class BattleFxFlow {

        @Test
        @DisplayName("击杀/暴击/小伤害应映射不同震屏、慢放与跳字")
        void composeMatrix() {
            BattleFxComposer.FxHint kill = BattleFxComposer.compose(-1200, true, 2001, true, 9);
            assertTrue(kill.kill());
            assertTrue(kill.critical());
            assertTrue(kill.weakness());
            assertEquals(3, kill.cameraShake());
            assertTrue(kill.timeScale() < 0.5f);
            assertEquals("crit_burst", kill.damagePopupStyle());

            BattleFxComposer.FxHint small = BattleFxComposer.compose(-10, false, 1, false, 2);
            assertEquals("small", small.damagePopupStyle());
            assertEquals(0, small.cameraShake());
            assertEquals(1.0f, small.timeScale());
        }

        @Test
        @DisplayName("BattleFxScNotify 应能承载服务器建议的 camera_shake/time_scale")
        void protoCarriesFxMeta() {
            BattleSystemProto.BattleFxScNotify notify = BattleSystemProto.BattleFxScNotify.newBuilder()
                    .setBattleId(88)
                    .setSkillId(1001)
                    .setCasterId(1)
                    .addFx(BattleSystemProto.BattleFxMeta.newBuilder()
                            .setCritical(true)
                            .setKill(true)
                            .setCameraShake(3)
                            .setTimeScale(0.35f)
                            .setDamagePopupStyle("crit_burst")
                            .setDisplayDamage(900)
                            .setTargetEntityId(7)
                            .build())
                    .build();
            assertEquals(CmdIds.BATTLE_FX_SC_NOTIFY, 213);
            assertEquals(88, notify.getBattleId());
            assertEquals(3, notify.getFx(0).getCameraShake());
            assertEquals("crit_burst", notify.getFx(0).getDamagePopupStyle());
        }
    }

    @Nested
    @DisplayName("6. 新手引导：检查点落库 → 重连恢复 → 强制跳过")
    class NewbieGuideFlow {

        @Test
        @DisplayName("推进检查点后 Get 从 checkpoint 恢复；Skip 需 confirm")
        void checkpointReconnectAndSkip() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            when(jdbc.queryForList(anyString(), anyInt())).thenReturn(List.of());
            when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
            when(jdbc.update(anyString(), any(), any(), any(), any())).thenReturn(1);

            NewbieGuideService domain = new NewbieGuideService(jdbc, new DefaultResourceLoader());
            domain.load();
            PlayerContextResolver resolver = mock(PlayerContextResolver.class);
            when(resolver.resolvePlayerId(any())).thenReturn(88);
            NewbieGuideNettyService netty = new NewbieGuideNettyService(domain, resolver);

            NewbieGuideSystemProto.GetNewbieGuideScRsp first = netty.handleGet(new EmbeddedChannel());
            assertEquals(0, first.getRetcode());
            assertFalse(first.getSkipped());
            String step0 = domain.steps().get(0).id();

            NewbieGuideSystemProto.AdvanceNewbieGuideScRsp adv = netty.handleAdvance(
                    NewbieGuideSystemProto.AdvanceNewbieGuideCsReq.newBuilder()
                            .setStepId(step0).setCommitCheckpoint(true).build(),
                    new EmbeddedChannel());
            assertEquals(0, adv.getRetcode());
            assertEquals(step0, adv.getCheckpointStepId());

            NewbieGuideSystemProto.GetNewbieGuideScRsp reconnect = netty.handleGet(new EmbeddedChannel());
            assertEquals(step0, reconnect.getCheckpointStepId());
            assertTrue(reconnect.getStepsList().stream().anyMatch(s -> s.getId().equals(step0) && s.getCommitted()));

            assertEquals(2, netty.handleSkip(
                    NewbieGuideSystemProto.SkipNewbieGuideCsReq.newBuilder().setConfirm(false).build(),
                    new EmbeddedChannel()).getRetcode());

            NewbieGuideSystemProto.SkipNewbieGuideScRsp skip = netty.handleSkip(
                    NewbieGuideSystemProto.SkipNewbieGuideCsReq.newBuilder().setConfirm(true).build(),
                    new EmbeddedChannel());
            assertEquals(0, skip.getRetcode());
            assertTrue(skip.getSkipped());
            assertTrue(netty.handleGet(new EmbeddedChannel()).getCompleted());
        }

        @Test
        @DisplayName("PacketHandler GET/SKIP 应写出对应 ScRsp")
        void packetGetAndSkip() throws Exception {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            when(jdbc.queryForList(anyString(), anyInt())).thenReturn(List.of());
            when(jdbc.update(anyString(), any(), any(), any(), any(), any(), any(), any())).thenReturn(1);
            NewbieGuideService domain = new NewbieGuideService(jdbc, new DefaultResourceLoader());
            domain.load();
            PlayerContextResolver resolver = mock(PlayerContextResolver.class);
            when(resolver.resolvePlayerId(any())).thenReturn(3);
            TutorialPacketHandlers handlers = new TutorialPacketHandlers(new NewbieGuideNettyService(domain, resolver));

            ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
            when(ctx.channel()).thenReturn(new EmbeddedChannel());
            handlers.onGet(ctx, new GamePacket(CmdIds.GET_NEWBIE_GUIDE_CS_REQ, new byte[0]));
            ArgumentCaptor<GamePacket> cap = ArgumentCaptor.forClass(GamePacket.class);
            verify(ctx).writeAndFlush(cap.capture());
            assertEquals(CmdIds.GET_NEWBIE_GUIDE_SC_RSP, cap.getValue().getCmdId());

            ChannelHandlerContext ctx2 = mock(ChannelHandlerContext.class);
            when(ctx2.channel()).thenReturn(new EmbeddedChannel());
            handlers.onSkip(ctx2, new GamePacket(CmdIds.SKIP_NEWBIE_GUIDE_CS_REQ,
                    NewbieGuideSystemProto.SkipNewbieGuideCsReq.newBuilder().setConfirm(true).build().toByteArray()));
            ArgumentCaptor<GamePacket> cap2 = ArgumentCaptor.forClass(GamePacket.class);
            verify(ctx2).writeAndFlush(cap2.capture());
            assertEquals(CmdIds.SKIP_NEWBIE_GUIDE_SC_RSP, cap2.getValue().getCmdId());
        }

        @Test
        @DisplayName("未登录引导协议 retcode=1")
        void unauthGuide() {
            NewbieGuideService domain = new NewbieGuideService(mock(JdbcTemplate.class), new DefaultResourceLoader());
            domain.load();
            PlayerContextResolver resolver = mock(PlayerContextResolver.class);
            when(resolver.resolvePlayerId(any())).thenReturn(0);
            NewbieGuideNettyService netty = new NewbieGuideNettyService(domain, resolver);
            assertEquals(1, netty.handleGet(new EmbeddedChannel()).getRetcode());
            assertEquals(1, netty.handleSkip(
                    NewbieGuideSystemProto.SkipNewbieGuideCsReq.newBuilder().setConfirm(true).build(),
                    new EmbeddedChannel()).getRetcode());
        }
    }

    @Test
    @DisplayName("新 CmdId 段应成对且不互相冲突")
    void newCmdIdsArePaired() {
        assertEquals(CmdIds.SCENE_PRELOAD_CS_REQ + 1, CmdIds.SCENE_PRELOAD_SC_RSP);
        assertEquals(CmdIds.SWEEP_STAGE_CS_REQ + 1, CmdIds.SWEEP_STAGE_SC_RSP);
        assertEquals(CmdIds.GET_SWEEP_INFO_CS_REQ + 1, CmdIds.GET_SWEEP_INFO_SC_RSP);
        assertEquals(CmdIds.HOME_PLACE_FURNITURE_CS_REQ + 1, CmdIds.HOME_PLACE_FURNITURE_SC_RSP);
        assertEquals(CmdIds.HOME_FURNITURE_INTERACT_CS_REQ + 1, CmdIds.HOME_FURNITURE_INTERACT_SC_RSP);
        assertEquals(CmdIds.SKIP_NEWBIE_GUIDE_CS_REQ + 1, CmdIds.SKIP_NEWBIE_GUIDE_SC_RSP);
        assertEquals(213, CmdIds.BATTLE_FX_SC_NOTIFY);
        assertEquals(356, CmdIds.SCENE_PRELOAD_READY_SC_NOTIFY);
        assertEquals(872, CmdIds.HOME_AVATAR_SYNC_SC_NOTIFY);
    }
}
