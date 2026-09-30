package cn.itcast.demo.mylunarcore.hardening;

import cn.itcast.demo.mylunarcore.assist.AssistFeatureContentRepository;
import cn.itcast.demo.mylunarcore.battle.BattleAutoService;
import cn.itcast.demo.mylunarcore.battle.BattleContext;
import cn.itcast.demo.mylunarcore.battle.BattleManager;
import cn.itcast.demo.mylunarcore.battle.BattleSnapshotService;
import cn.itcast.demo.mylunarcore.battle.EncounterConfig;
import cn.itcast.demo.mylunarcore.battle.EncounterConfigRepository;
import cn.itcast.demo.mylunarcore.battle.FxQualityAdvisor;
import cn.itcast.demo.mylunarcore.battle.assist.HeuristicBattleAssistPolicy;
import cn.itcast.demo.mylunarcore.challenge.SweepNettyService;
import cn.itcast.demo.mylunarcore.challenge.SweepService;
import cn.itcast.demo.mylunarcore.character.CharacterDevelopmentNettyService;
import cn.itcast.demo.mylunarcore.character.DevelopmentPlanService;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.cutscene.CutsceneTriggerService;
import cn.itcast.demo.mylunarcore.dialogue.DialogueCutsceneNettyService;
import cn.itcast.demo.mylunarcore.dialogue.DialogueProgressService;
import cn.itcast.demo.mylunarcore.dialogue.DialogueTreeRepository;
import cn.itcast.demo.mylunarcore.dialogue.DialogueTriggerEngine;
import cn.itcast.demo.mylunarcore.economy.RewardDistributor;
import cn.itcast.demo.mylunarcore.home.HomeBaseService;
import cn.itcast.demo.mylunarcore.home.HomeNettyService;
import cn.itcast.demo.mylunarcore.item.ItemApplicationService;
import cn.itcast.demo.mylunarcore.item.ItemNettyService;
import cn.itcast.demo.mylunarcore.model.AvatarEntity;
import cn.itcast.demo.mylunarcore.net.CharacterPacketHandlers;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.DailyLoopPacketHandlers;
import cn.itcast.demo.mylunarcore.net.DialogueCutscenePacketHandlers;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.net.HallPacketHandlers;
import cn.itcast.demo.mylunarcore.net.HomePacketHandlers;
import cn.itcast.demo.mylunarcore.net.SettingsPacketHandlers;
import cn.itcast.demo.mylunarcore.net.mapper.ItemProtoMapper;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.player.PlayerContextResolver;
import cn.itcast.demo.mylunarcore.player.PlayerDataSyncService;
import cn.itcast.demo.mylunarcore.player.StaminaOverflowHintService;
import cn.itcast.demo.mylunarcore.player.StaminaService;
import cn.itcast.demo.mylunarcore.protocol.CharacterSystemProto;
import cn.itcast.demo.mylunarcore.protocol.DailyLoopSystemProto;
import cn.itcast.demo.mylunarcore.protocol.DialogueCutsceneProto;
import cn.itcast.demo.mylunarcore.protocol.HallSystemProto;
import cn.itcast.demo.mylunarcore.protocol.HomeSystemProto;
import cn.itcast.demo.mylunarcore.protocol.ItemSystemProto;
import cn.itcast.demo.mylunarcore.protocol.SettingsSystemProto;
import cn.itcast.demo.mylunarcore.repo.AvatarRepository;
import cn.itcast.demo.mylunarcore.repo.BattleMonsterWaveRepository;
import cn.itcast.demo.mylunarcore.repo.ItemRepository;
import cn.itcast.demo.mylunarcore.scene.MovementPhysics;
import cn.itcast.demo.mylunarcore.settings.KeyBindCloudService;
import cn.itcast.demo.mylunarcore.settings.PlayerSettings;
import cn.itcast.demo.mylunarcore.settings.PlayerSettingsApplicationService;
import cn.itcast.demo.mylunarcore.settings.SettingsNettyService;
import cn.itcast.demo.mylunarcore.settings.SupportTicketApplicationService;
import cn.itcast.demo.mylunarcore.social.EmoteInventoryService;
import cn.itcast.demo.mylunarcore.social.EmoteNettyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 体验沉浸 8 项：协议门面 + PacketHandler 端到端业务流程回归。
 */
@DisplayName("体验沉浸协议与业务流程")
class ExperienceImmersionProtocolFlowsTest {

    private static final int PLAYER_ID = 8801;

    @Nested
    @DisplayName("1. 养成计划协议 → 一键连续扫荡协议")
    class DevelopmentPlanProtocolFlow {

        @Test
        @DisplayName("CalculateUpgradeMaterials 返回缺口与推荐关卡；BatchSweep 写出进度推送")
        void calculateThenBatchSweepViaNetty() throws Exception {
            AvatarRepository avatars = mock(AvatarRepository.class);
            AvatarEntity avatar = new AvatarEntity();
            avatar.setAvatarId(1001);
            avatar.setLevel(20);
            avatar.setPromotion(0);
            when(avatars.findAvatar(PLAYER_ID, 1001)).thenReturn(avatar);
            ItemApplicationService items = mock(ItemApplicationService.class);
            when(items.listBagItems(anyInt(), anyInt(), anyInt(), anyInt())).thenReturn(List.of());
            StaminaService stamina = mock(StaminaService.class);
            when(stamina.config()).thenReturn(new StaminaService.StaminaConfig(240, 1, 60_000L, 40, 20, 8, 101,
                    List.of(50), 60));
            when(stamina.snapshot(PLAYER_ID)).thenReturn(new StaminaService.StaminaSnapshot(240, 240, 0, 8, 0L, 0, 40));
            when(stamina.tryConsume(anyInt(), anyInt())).thenReturn(StaminaService.ConsumeResult.ok(200));

            PlayerContextResolver resolver = mock(PlayerContextResolver.class);
            when(resolver.resolvePlayerId(any())).thenReturn(PLAYER_ID);
            CharacterDevelopmentNettyService planNetty = new CharacterDevelopmentNettyService(
                    new DevelopmentPlanService(avatars, items, provider(stamina)), resolver,
                    mock(cn.itcast.demo.mylunarcore.player.GameSessionManager.class),
                    mock(cn.itcast.demo.mylunarcore.assist.cultivation.AssistCultivationAdvisorService.class));

            CharacterSystemProto.CalculateUpgradeMaterialsScRsp planRsp = planNetty.handleCalculate(
                    CharacterSystemProto.CalculateUpgradeMaterialsCsReq.newBuilder()
                            .setAvatarId(1001).setTargetLevel(80).build(),
                    mock(Channel.class));
            assertEquals(0, planRsp.getRetcode());
            assertEquals(1001, planRsp.getAvatarId());
            assertTrue(planRsp.getMaterialsCount() > 0);
            assertTrue(planRsp.getRecommendedStagesCount() > 0);
            assertTrue(planRsp.getMaterialsList().stream().anyMatch(m -> m.getDeficit() > 0));

            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            when(jdbc.update(anyString(), any(), any(), any())).thenThrow(new RuntimeException("no db"));
            EncounterConfigRepository encounters = mock(EncounterConfigRepository.class);
            when(encounters.current()).thenReturn(new EncounterConfig(1, List.of(),
                    List.of(new EncounterConfig.DropEntry(201, 2, null, null)), 10, 0, 0, 0, 5.0));
            RewardDistributor rewards = mock(RewardDistributor.class);
            when(rewards.grantBattleRewards(anyInt(), anyList(), anyInt(), anyString()))
                    .thenReturn(List.of(new RewardDistributor.GrantedItem(201, 2)));
            SweepService sweep = new SweepService(jdbc, stamina, encounters, rewards);
            sweep.recordClear(PLAYER_ID, 101, 6);
            SweepNettyService sweepNetty = new SweepNettyService(sweep, resolver);

            Channel channel = mock(Channel.class);
            when(channel.isActive()).thenReturn(true);
            when(channel.writeAndFlush(any())).thenReturn(mock(ChannelFuture.class));
            DailyLoopSystemProto.BatchSweepScRsp batchRsp = sweepNetty.handleBatch(
                    DailyLoopSystemProto.BatchSweepCsReq.newBuilder()
                            .addSteps(DailyLoopSystemProto.BatchSweepStep.newBuilder()
                                    .setBattleStageId(101).setTimes(2).build())
                            .setConfirmedStaminaCost(40)
                            .build(),
                    channel);
            assertEquals(0, batchRsp.getRetcode());
            assertEquals(2, batchRsp.getCompletedTimes());
            assertEquals(40, batchRsp.getStaminaCost());
            assertFalse(batchRsp.getRewardsList().isEmpty());

            ArgumentCaptor<GamePacket> captor = ArgumentCaptor.forClass(GamePacket.class);
            verify(channel, org.mockito.Mockito.atLeast(2)).writeAndFlush(captor.capture());
            assertTrue(captor.getAllValues().stream()
                    .anyMatch(p -> p.getCmdId() == CmdIds.BATCH_SWEEP_PROGRESS_SC_NOTIFY));
            assertTrue(captor.getAllValues().stream()
                    .anyMatch(p -> p.getCmdId() == CmdIds.BATCH_SWEEP_REWARD_SUMMARY_SC_NOTIFY));
            DailyLoopSystemProto.BatchSweepRewardSummaryScNotify summary =
                    DailyLoopSystemProto.BatchSweepRewardSummaryScNotify.parseFrom(
                            captor.getAllValues().stream()
                                    .filter(p -> p.getCmdId() == CmdIds.BATCH_SWEEP_REWARD_SUMMARY_SC_NOTIFY)
                                    .findFirst().orElseThrow().getPayload());
            assertTrue(summary.getSummary().getSkipLootBoxAnim());
            assertTrue(summary.getSummary().getSummaryText().length() > 0);

            CharacterPacketHandlers characterHandlers = new CharacterPacketHandlers(
                    mock(cn.itcast.demo.mylunarcore.character.CharacterNettyService.class), planNetty);
            ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
            when(ctx.channel()).thenReturn(new EmbeddedChannel());
            when(ctx.writeAndFlush(any())).thenReturn(mock(ChannelFuture.class));
            characterHandlers.onCalculateUpgradeMaterials(ctx, new GamePacket(
                    CmdIds.CALCULATE_UPGRADE_MATERIALS_CS_REQ,
                    CharacterSystemProto.CalculateUpgradeMaterialsCsReq.newBuilder()
                            .setAvatarId(1001).setTargetLevel(80).build().toByteArray()));
            ArgumentCaptor<GamePacket> rspCap = ArgumentCaptor.forClass(GamePacket.class);
            verify(ctx).writeAndFlush(rspCap.capture());
            assertEquals(CmdIds.CALCULATE_UPGRADE_MATERIALS_SC_RSP, rspCap.getValue().getCmdId());
            assertEquals(0, CharacterSystemProto.CalculateUpgradeMaterialsScRsp
                    .parseFrom(rspCap.getValue().getPayload()).getRetcode());
        }

        @Test
        @DisplayName("未登录计算/扫荡均返回 retcode=1")
        void unauthenticatedFails() {
            PlayerContextResolver resolver = mock(PlayerContextResolver.class);
            when(resolver.resolvePlayerId(any())).thenReturn(0);
            CharacterDevelopmentNettyService planNetty = new CharacterDevelopmentNettyService(
                    new DevelopmentPlanService(mock(AvatarRepository.class), mock(ItemApplicationService.class),
                            provider(null)), resolver,
                    mock(cn.itcast.demo.mylunarcore.player.GameSessionManager.class),
                    mock(cn.itcast.demo.mylunarcore.assist.cultivation.AssistCultivationAdvisorService.class));
            assertEquals(1, planNetty.handleCalculate(
                    CharacterSystemProto.CalculateUpgradeMaterialsCsReq.newBuilder()
                            .setAvatarId(1).setTargetLevel(80).build(),
                    mock(Channel.class)).getRetcode());
            SweepNettyService sweepNetty = new SweepNettyService(mock(SweepService.class), resolver);
            assertEquals(1, sweepNetty.handleBatch(
                    DailyLoopSystemProto.BatchSweepCsReq.getDefaultInstance(), mock(Channel.class)).getRetcode());
        }
    }

    @Nested
    @DisplayName("2. Auto 策略切换与手动大招覆盖")
    class AutoBattleFlow {

        @Test
        @DisplayName("PRIORITY_SKILL/BASIC/SAVE_ENERGY 选技差异；手动大招后 Auto 仍开启")
        void strategiesAndManualOverride() {
            BattleManager battles = new BattleManager(mock(BattleSnapshotService.class));
            BattleContext ctx = newBattle(77001L, PLAYER_ID);
            battles.put(ctx);
            BattleAutoService auto = new BattleAutoService(battles,
                    new HeuristicBattleAssistPolicy(new LunarCoreProperties(), mock(AssistFeatureContentRepository.class)),
                    mock(GameSessionManager.class));

            assertTrue(auto.enableAuto(77001L, PLAYER_ID, true, "manual", 2));
            assertEquals(1, auto.executeOneTurn(ctx).skillId());

            assertTrue(auto.enableAuto(77001L, PLAYER_ID, true, "manual", 3));
            BattleAutoService.AutoAction save = auto.executeOneTurn(ctx);
            assertEquals(1, save.skillId());
            assertEquals(-80, save.hpDelta());

            assertTrue(auto.enableAuto(77001L, PLAYER_ID, true, "manual", 1));
            BattleAutoService.AutoAction skill = auto.executeOneTurn(ctx);
            assertTrue(skill.skillId() >= 2);

            BattleAutoService.AutoAction ult = auto.executeManualUlt(77001L, PLAYER_ID, 3, PLAYER_ID, List.of());
            assertEquals(3, ult.skillId());
            assertTrue(ctx.isAutoBattle());
            assertFalse(auto.enableAuto(77001L, PLAYER_ID, true, "manual", 99));
        }
    }

    @Nested
    @DisplayName("3. 移动状态机反馈矩阵")
    class MovementMatrixFlow {

        @Test
        @DisplayName("WALK/RUN/JUMP/CLIMB 与落地切换覆盖反馈字段")
        void moveStateMatrix() {
            MovementPhysics.Feedback walk = MovementPhysics.resolve(1, 0, 0, 0, MovementPhysics.WALK, MovementPhysics.WALK);
            assertEquals(MovementPhysics.WALK, walk.moveState());
            assertFalse(walk.landing());

            MovementPhysics.Feedback run = MovementPhysics.resolve(1, 8, 0, 8, MovementPhysics.RUN, MovementPhysics.WALK);
            assertEquals(MovementPhysics.RUN, run.moveState());

            MovementPhysics.Feedback jump = MovementPhysics.resolve(2, 0, 1, 0, MovementPhysics.JUMP, MovementPhysics.RUN);
            assertEquals(MovementPhysics.JUMP, jump.moveState());
            assertFalse(jump.landing());

            MovementPhysics.Feedback land = MovementPhysics.resolve(2, 0, 0, 0, MovementPhysics.WALK, MovementPhysics.JUMP);
            assertTrue(land.landing());
            assertTrue(land.hapticStrength() > 0);
            assertTrue(land.footstepSfxId() > 0);

            MovementPhysics.Feedback climb = MovementPhysics.resolve(3, 16, 0, 16, MovementPhysics.CLIMB, MovementPhysics.WALK);
            assertEquals(MovementPhysics.CLIMB, climb.moveState());
            assertTrue(climb.hapticStrength() >= MovementPhysics.resolve(3, 16, 0, 16, MovementPhysics.WALK, MovementPhysics.WALK)
                    .hapticStrength());
            assertEquals(MovementPhysics.WALK, MovementPhysics.normalizeState(0));
        }
    }

    @Nested
    @DisplayName("4. 表情包协议：仓库查询 + 聊天发送")
    class EmoteProtocolFlow {

        @Test
        @DisplayName("库存含默认动作；聊天表情成功推送 CHAT_EMOTE_SC_NOTIFY；未拥有失败")
        void inventoryAndChatEmote() throws Exception {
            PlayerContextResolver resolver = mock(PlayerContextResolver.class);
            when(resolver.resolvePlayerId(any())).thenReturn(PLAYER_ID);
            when(resolver.resolveUid(any())).thenReturn(OptionalLong.of(PLAYER_ID));
            EmoteNettyService emote = new EmoteNettyService(new EmoteInventoryService(), resolver,
                    provider(null), provider(null), provider(null));

            HallSystemProto.GetEmoteInventoryScRsp inv = emote.handleGetInventory(mock(Channel.class));
            assertEquals(0, inv.getRetcode());
            assertTrue(inv.getEmotesCount() >= 4);
            assertTrue(inv.getEmotesList().stream().anyMatch(e -> e.getEmoteId() == 1 && e.getOwned()));
            assertTrue(inv.getEmotesList().stream().anyMatch(e -> e.getEmoteId() == 5 && !e.getOwned()));

            Channel channel = mock(Channel.class);
            when(channel.isActive()).thenReturn(true);
            when(channel.writeAndFlush(any())).thenReturn(mock(ChannelFuture.class));
            HallSystemProto.SendChatEmoteScRsp ok = emote.handleSendChatEmote(
                    HallSystemProto.SendChatEmoteCsReq.newBuilder()
                            .setEmoteId(1).setChannelType(0).setTargetId(0).build(),
                    channel);
            assertEquals(0, ok.getRetcode());
            ArgumentCaptor<GamePacket> captor = ArgumentCaptor.forClass(GamePacket.class);
            verify(channel).writeAndFlush(captor.capture());
            assertEquals(CmdIds.CHAT_EMOTE_SC_NOTIFY, captor.getValue().getCmdId());

            assertEquals(3, emote.handleSendChatEmote(
                    HallSystemProto.SendChatEmoteCsReq.newBuilder().setEmoteId(5).build(),
                    mock(Channel.class)).getRetcode());

            HallPacketHandlers handlers = new HallPacketHandlers(
                    mock(cn.itcast.demo.mylunarcore.hall.HallNettyService.class), emote);
            ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
            when(ctx.channel()).thenReturn(new EmbeddedChannel());
            when(ctx.writeAndFlush(any())).thenReturn(mock(ChannelFuture.class));
            handlers.onGetEmoteInventory(ctx, new GamePacket(CmdIds.GET_EMOTE_INVENTORY_CS_REQ, new byte[0]));
            ArgumentCaptor<GamePacket> rsp = ArgumentCaptor.forClass(GamePacket.class);
            verify(ctx).writeAndFlush(rsp.capture());
            assertEquals(CmdIds.GET_EMOTE_INVENTORY_SC_RSP, rsp.getValue().getCmdId());
        }
    }

    @Nested
    @DisplayName("5. 键位云同步 + 特效降级")
    class KeyBindProtocolFlow {

        @Test
        @DisplayName("SyncKeyBind 落盘并回读；推送 PUSH_KEY_BIND；RTT 降档")
        void syncKeyBindAndFx() {
            PlayerContextResolver resolver = mock(PlayerContextResolver.class);
            when(resolver.resolvePlayerId(any())).thenReturn(PLAYER_ID);
            PlayerSettingsApplicationService settings = mock(PlayerSettingsApplicationService.class);
            when(settings.update(eq(PLAYER_ID), any(), anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean()))
                    .thenReturn(new PlayerSettings());
            KeyBindCloudService cloud = new KeyBindCloudService(provider(null));
            SettingsNettyService netty = new SettingsNettyService(resolver, settings,
                    mock(SupportTicketApplicationService.class), provider(cloud));

            Channel channel = mock(Channel.class);
            when(channel.isActive()).thenReturn(true);
            when(channel.writeAndFlush(any())).thenReturn(mock(ChannelFuture.class));
            SettingsSystemProto.SyncKeyBindScRsp rsp = netty.handleSyncKeyBind(
                    SettingsSystemProto.SyncKeyBindCsReq.newBuilder()
                            .setDeviceType("mobile")
                            .addKeybinds(SettingsSystemProto.KeyBinding.newBuilder()
                                    .setActionId("skill_ult").setKeyCode("Q")
                                    .setDeviceType("mobile").setScale(120).setPosX(0.8f).setPosY(0.2f)
                                    .build())
                            .build(),
                    channel);
            assertEquals(0, rsp.getRetcode());
            assertEquals("Q", rsp.getKeybinds(0).getKeyCode());
            assertEquals("Q", cloud.load(PLAYER_ID, "mobile").get(0).keyCode());

            ArgumentCaptor<GamePacket> captor = ArgumentCaptor.forClass(GamePacket.class);
            verify(channel).writeAndFlush(captor.capture());
            assertEquals(CmdIds.PUSH_KEY_BIND_SC_NOTIFY, captor.getValue().getCmdId());

            assertEquals(2, FxQualityAdvisor.resolve(30, 0));
            assertEquals(1, FxQualityAdvisor.resolve(120, 0));
            assertEquals(0, FxQualityAdvisor.resolve(250, 1000));

            when(resolver.resolvePlayerId(any())).thenReturn(0);
            assertEquals(1, netty.handleSyncKeyBind(
                    SettingsSystemProto.SyncKeyBindCsReq.getDefaultInstance(), mock(Channel.class)).getRetcode());
            when(resolver.resolvePlayerId(any())).thenReturn(PLAYER_ID);
            assertEquals(2, netty.handleSyncKeyBind(
                    SettingsSystemProto.SyncKeyBindCsReq.newBuilder().setDeviceType("pc").build(),
                    mock(Channel.class)).getRetcode());

            SettingsPacketHandlers handlers = new SettingsPacketHandlers(netty);
            ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
            when(ctx.channel()).thenReturn(channel);
            when(ctx.writeAndFlush(any())).thenReturn(mock(ChannelFuture.class));
            when(resolver.resolvePlayerId(any())).thenReturn(PLAYER_ID);
            try {
                handlers.onSyncKeyBind(ctx, new GamePacket(CmdIds.SYNC_KEY_BIND_CS_REQ,
                        SettingsSystemProto.SyncKeyBindCsReq.newBuilder()
                                .setDeviceType("pc")
                                .addKeybinds(SettingsSystemProto.KeyBinding.newBuilder()
                                        .setActionId("jump").setKeyCode("Space").build())
                                .build().toByteArray()));
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            ArgumentCaptor<GamePacket> rspCap = ArgumentCaptor.forClass(GamePacket.class);
            verify(ctx).writeAndFlush(rspCap.capture());
            assertEquals(CmdIds.SYNC_KEY_BIND_SC_RSP, rspCap.getValue().getCmdId());
        }
    }

    @Nested
    @DisplayName("6. 剧情树快照 + impact_tags")
    class StoryTreeProtocolFlow {

        @Test
        @DisplayName("开始对话选项含影响标签；快照协议返回解锁节点")
        void impactTagsAndSnapshotProtocol() throws Exception {
            DialogueTreeRepository trees = new DialogueTreeRepository(new ObjectMapper(), "data");
            assertTrue(trees.reload());
            DialogueTriggerEngine engine = new DialogueTriggerEngine(
                    trees, new DialogueProgressService(), new CutsceneTriggerService(new ObjectMapper(), "data"));
            PlayerContextResolver resolver = mock(PlayerContextResolver.class);
            when(resolver.resolvePlayerId(any())).thenReturn(PLAYER_ID);
            DialogueCutsceneNettyService netty = new DialogueCutsceneNettyService(
                    engine, new CutsceneTriggerService(new ObjectMapper(), "data"), resolver);

            var start = engine.startByNpc(PLAYER_ID, "1001");
            assertTrue(start.ok());
            assertTrue(start.node().safeChoices().get(0).impactTags().contains("+Affinity"));

            DialogueCutsceneProto.GetStoryTreeSnapshotScRsp snap = netty.handleStoryTreeSnapshot(
                    DialogueCutsceneProto.GetStoryTreeSnapshotCsReq.newBuilder().setTreeId("1001").build(),
                    mock(Channel.class));
            assertEquals(0, snap.getRetcode());
            assertEquals("1001", snap.getTreeId());
            assertTrue(snap.getNodesCount() >= 2);
            assertTrue(snap.getNodesList().stream().anyMatch(DialogueCutsceneProto.StoryTreeNodeSnapshot::getUnlocked));
            assertTrue(snap.getNodesList().stream().anyMatch(DialogueCutsceneProto.StoryTreeNodeSnapshot::getCurrent));

            DialogueCutscenePacketHandlers handlers = new DialogueCutscenePacketHandlers(netty);
            ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
            when(ctx.channel()).thenReturn(new EmbeddedChannel());
            when(ctx.writeAndFlush(any())).thenReturn(mock(ChannelFuture.class));
            handlers.onStoryTreeSnapshot(ctx, new GamePacket(CmdIds.GET_STORY_TREE_SNAPSHOT_CS_REQ,
                    DialogueCutsceneProto.GetStoryTreeSnapshotCsReq.newBuilder().setTreeId("1001").build().toByteArray()));
            ArgumentCaptor<GamePacket> captor = ArgumentCaptor.forClass(GamePacket.class);
            verify(ctx).writeAndFlush(captor.capture());
            assertEquals(CmdIds.GET_STORY_TREE_SNAPSHOT_SC_RSP, captor.getValue().getCmdId());
        }
    }

    @Nested
    @DisplayName("7. 家园助产协议")
    class HomeAssistProtocolFlow {

        @Test
        @DisplayName("好友助产成功减 CD；自助/重复失败；PacketHandler 写出 RSP")
        void harvestAssistProtocol() throws Exception {
            HomeBaseService home = new HomeBaseService(new ObjectMapper(), "data");
            assertTrue(home.reloadCatalog());
            assertTrue(home.placeFacility(PLAYER_ID, 1, 1).success());
            PlayerContextResolver resolver = mock(PlayerContextResolver.class);
            when(resolver.resolvePlayerId(any())).thenReturn(PLAYER_ID + 1);
            HomeNettyService netty = new HomeNettyService(home, resolver);

            HomeSystemProto.HomeHarvestAssistScRsp ok = netty.handleHarvestAssist(
                    HomeSystemProto.HomeHarvestAssistCsReq.newBuilder()
                            .setHostPlayerId(PLAYER_ID).setFacilityId(1).build(),
                    mock(Channel.class));
            assertEquals(0, ok.getRetcode());
            assertEquals(180_000, ok.getReducedCdMs());

            assertEquals(5, netty.handleHarvestAssist(
                    HomeSystemProto.HomeHarvestAssistCsReq.newBuilder()
                            .setHostPlayerId(PLAYER_ID).setFacilityId(1).build(),
                    mock(Channel.class)).getRetcode());

            when(resolver.resolvePlayerId(any())).thenReturn(PLAYER_ID);
            assertEquals(2, netty.handleHarvestAssist(
                    HomeSystemProto.HomeHarvestAssistCsReq.newBuilder()
                            .setHostPlayerId(PLAYER_ID).setFacilityId(1).build(),
                    mock(Channel.class)).getRetcode());

            when(resolver.resolvePlayerId(any())).thenReturn(PLAYER_ID + 2);
            HomePacketHandlers handlers = new HomePacketHandlers(netty);
            ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
            when(ctx.channel()).thenReturn(new EmbeddedChannel());
            when(ctx.writeAndFlush(any())).thenReturn(mock(ChannelFuture.class));
            home.placeFacility(9001, 1, 1);
            when(resolver.resolvePlayerId(any())).thenReturn(9002);
            handlers.onHarvestAssist(ctx, new GamePacket(CmdIds.HOME_HARVEST_ASSIST_CS_REQ,
                    HomeSystemProto.HomeHarvestAssistCsReq.newBuilder()
                            .setHostPlayerId(9001).setFacilityId(1).build().toByteArray()));
            ArgumentCaptor<GamePacket> captor = ArgumentCaptor.forClass(GamePacket.class);
            verify(ctx).writeAndFlush(captor.capture());
            assertEquals(CmdIds.HOME_HARVEST_ASSIST_SC_RSP, captor.getValue().getCmdId());
            assertEquals(0, HomeSystemProto.HomeHarvestAssistScRsp.parseFrom(captor.getValue().getPayload()).getRetcode());
        }
    }

    @Nested
    @DisplayName("8. 体力溢出提醒 + 合成路径协议")
    class StaminaCraftProtocolFlow {

        @Test
        @DisplayName("≥80% 不活跃提醒；QueryItemSource 返回合成路径与 CraftItemCsReq")
        void overflowAndCraftPathProtocol() {
            StaminaService stamina = mock(StaminaService.class);
            when(stamina.snapshot(anyInt())).thenReturn(new StaminaService.StaminaSnapshot(200, 240, 0, 8, 0L, 0, 40));
            StaminaOverflowHintService hints = new StaminaOverflowHintService(stamina, provider(null), provider(null));
            long now = System.currentTimeMillis();
            assertTrue(hints.maybeHint(PLAYER_ID, now, now - 12 * 60_000L));
            assertFalse(hints.maybeHint(PLAYER_ID, now + 5_000L, now - 12 * 60_000L)); // 冷却中
            StaminaOverflowHintService active = new StaminaOverflowHintService(stamina, provider(null), provider(null));
            assertFalse(active.maybeHint(PLAYER_ID + 1, now, now - 60_000L)); // 仍活跃

            PlayerContextResolver resolver = mock(PlayerContextResolver.class);
            when(resolver.resolvePlayerId(any())).thenReturn(PLAYER_ID);
            ItemNettyService itemNetty = new ItemNettyService(
                    new ItemApplicationService(mock(ItemRepository.class)),
                    mock(ItemProtoMapper.class), resolver, mock(PlayerDataSyncService.class));
            ItemSystemProto.QueryItemSourceScRsp craft = itemNetty.handleQueryItemSource(
                    ItemSystemProto.QueryItemSourceCsReq.newBuilder().setItemId(301).build(),
                    mock(Channel.class));
            assertEquals(0, craft.getRetcode());
            assertEquals("CraftItemCsReq", craft.getCraftCmd());
            assertFalse(craft.getCraftPathsList().isEmpty());
            assertEquals(301, craft.getCraftPaths(0).getItemId());
            assertTrue(craft.getCraftPaths(0).getCanCraft());
            assertEquals(201, craft.getCraftPaths(0).getSteps(0).getFromItemId());

            ItemSystemProto.QueryItemSourceScRsp craft401 = itemNetty.handleQueryItemSource(
                    ItemSystemProto.QueryItemSourceCsReq.newBuilder().setItemId(401).build(),
                    mock(Channel.class));
            assertEquals(2, craft401.getCraftPaths(0).getStepsCount());
        }
    }

    @Nested
    @DisplayName("PacketHandler 扫荡命令绑定")
    class BatchSweepHandlerFlow {

        @Test
        @DisplayName("DailyLoopPacketHandlers 写出 BATCH_SWEEP_SC_RSP")
        void batchSweepHandlerWritesRsp() throws Exception {
            PlayerContextResolver resolver = mock(PlayerContextResolver.class);
            when(resolver.resolvePlayerId(any())).thenReturn(PLAYER_ID);
            StaminaService stamina = mock(StaminaService.class);
            when(stamina.config()).thenReturn(new StaminaService.StaminaConfig(240, 1, 60_000L, 40, 20, 8, 101,
                    List.of(50), 60));
            when(stamina.snapshot(PLAYER_ID)).thenReturn(new StaminaService.StaminaSnapshot(240, 240, 0, 8, 0L, 0, 40));
            when(stamina.tryConsume(anyInt(), anyInt())).thenReturn(StaminaService.ConsumeResult.ok(200));
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            when(jdbc.update(anyString(), any(), any(), any())).thenThrow(new RuntimeException("no db"));
            EncounterConfigRepository encounters = mock(EncounterConfigRepository.class);
            when(encounters.current()).thenReturn(new EncounterConfig(1, List.of(),
                    List.of(new EncounterConfig.DropEntry(201, 2, null, null)), 10, 0, 0, 0, 5.0));
            RewardDistributor rewards = mock(RewardDistributor.class);
            when(rewards.grantBattleRewards(anyInt(), anyList(), anyInt(), anyString()))
                    .thenReturn(List.of(new RewardDistributor.GrantedItem(201, 2)));
            SweepService sweep = new SweepService(jdbc, stamina, encounters, rewards);
            sweep.recordClear(PLAYER_ID, 101, 4);
            SweepNettyService sweepNetty = new SweepNettyService(sweep, resolver);
            cn.itcast.demo.mylunarcore.player.StaminaNettyService staminaNetty =
                    mock(cn.itcast.demo.mylunarcore.player.StaminaNettyService.class);
            when(staminaNetty.buildNotify(anyInt(), anyString()))
                    .thenReturn(DailyLoopSystemProto.StaminaUpdateScNotify.newBuilder()
                            .setCurrent(220).setMax(240).build());

            DailyLoopPacketHandlers handlers = new DailyLoopPacketHandlers(
                    staminaNetty,
                    mock(cn.itcast.demo.mylunarcore.quest.DailyMissionNettyService.class),
                    mock(cn.itcast.demo.mylunarcore.story.StoryChapterNettyService.class),
                    sweepNetty, resolver, mock(cn.itcast.demo.mylunarcore.net.SensitiveApiRateLimiter.class));
            ChannelHandlerContext ctx = mock(ChannelHandlerContext.class);
            Channel ch = mock(Channel.class);
            when(ctx.channel()).thenReturn(ch);
            when(ch.isActive()).thenReturn(true);
            when(ch.writeAndFlush(any())).thenReturn(mock(ChannelFuture.class));
            when(ctx.writeAndFlush(any())).thenReturn(mock(ChannelFuture.class));
            handlers.onBatchSweep(ctx, new GamePacket(CmdIds.BATCH_SWEEP_CS_REQ,
                    DailyLoopSystemProto.BatchSweepCsReq.newBuilder()
                            .addSteps(DailyLoopSystemProto.BatchSweepStep.newBuilder()
                                    .setBattleStageId(101).setTimes(1).build())
                            .setConfirmedStaminaCost(20)
                            .build().toByteArray()));
            ArgumentCaptor<GamePacket> captor = ArgumentCaptor.forClass(GamePacket.class);
            verify(ctx, org.mockito.Mockito.atLeastOnce()).writeAndFlush(captor.capture());
            assertTrue(captor.getAllValues().stream().anyMatch(p -> p.getCmdId() == CmdIds.BATCH_SWEEP_SC_RSP));
            GamePacket batchPacket = captor.getAllValues().stream()
                    .filter(p -> p.getCmdId() == CmdIds.BATCH_SWEEP_SC_RSP).findFirst().orElseThrow();
            assertEquals(0, DailyLoopSystemProto.BatchSweepScRsp.parseFrom(batchPacket.getPayload()).getRetcode());
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
