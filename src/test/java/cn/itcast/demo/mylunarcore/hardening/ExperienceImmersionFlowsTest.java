package cn.itcast.demo.mylunarcore.hardening;

import cn.itcast.demo.mylunarcore.assist.AssistFeatureContentRepository;
import cn.itcast.demo.mylunarcore.battle.BattleAutoService;
import cn.itcast.demo.mylunarcore.battle.BattleContext;
import cn.itcast.demo.mylunarcore.battle.BattleManager;
import cn.itcast.demo.mylunarcore.battle.BattleSnapshotService;
import cn.itcast.demo.mylunarcore.battle.FxQualityAdvisor;
import cn.itcast.demo.mylunarcore.battle.assist.HeuristicBattleAssistPolicy;
import cn.itcast.demo.mylunarcore.challenge.SweepService;
import cn.itcast.demo.mylunarcore.character.DevelopmentPlanService;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.cutscene.CutsceneTriggerService;
import cn.itcast.demo.mylunarcore.dialogue.DialogueProgressService;
import cn.itcast.demo.mylunarcore.dialogue.DialogueTreeRepository;
import cn.itcast.demo.mylunarcore.dialogue.DialogueTriggerEngine;
import cn.itcast.demo.mylunarcore.economy.RewardDistributor;
import cn.itcast.demo.mylunarcore.home.HomeBaseService;
import cn.itcast.demo.mylunarcore.item.ItemApplicationService;
import cn.itcast.demo.mylunarcore.model.AvatarEntity;
import cn.itcast.demo.mylunarcore.net.BattlePacketHandlers;
import cn.itcast.demo.mylunarcore.net.CharacterPacketHandlers;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.DailyLoopPacketHandlers;
import cn.itcast.demo.mylunarcore.net.DialogueCutscenePacketHandlers;
import cn.itcast.demo.mylunarcore.net.HallPacketHandlers;
import cn.itcast.demo.mylunarcore.net.HomePacketHandlers;
import cn.itcast.demo.mylunarcore.net.PacketCmd;
import cn.itcast.demo.mylunarcore.net.ScenePacketHandlers;
import cn.itcast.demo.mylunarcore.net.SettingsPacketHandlers;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.player.StaminaOverflowHintService;
import cn.itcast.demo.mylunarcore.player.StaminaService;
import cn.itcast.demo.mylunarcore.protocol.BattleSystemProto;
import cn.itcast.demo.mylunarcore.protocol.ItemSystemProto;
import cn.itcast.demo.mylunarcore.repo.AvatarRepository;
import cn.itcast.demo.mylunarcore.repo.BattleMonsterWaveRepository;
import cn.itcast.demo.mylunarcore.repo.ItemRepository;
import cn.itcast.demo.mylunarcore.scene.MovementPhysics;
import cn.itcast.demo.mylunarcore.settings.KeyBindCloudService;
import cn.itcast.demo.mylunarcore.social.EmoteInventoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.util.ReflectionUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 体验补齐 8 项：养成计划、Auto 策略、移动状态机、表情包、键位云同步、剧情树、家园助产、体力/合成路径。
 */
@DisplayName("体验沉浸补齐业务流程")
class ExperienceImmersionFlowsTest {

    @Nested
    @DisplayName("1. 养成计划 + 一键连续扫荡")
    class DevelopmentPlanFlow {

        @Test
        @DisplayName("目标等级材料缺口按掉落效率排序，并可一键连续扫荡")
        void calculateThenBatchSweep() {
            AvatarRepository avatars = mock(AvatarRepository.class);
            AvatarEntity avatar = new AvatarEntity();
            avatar.setAvatarId(1001);
            avatar.setLevel(20);
            avatar.setPromotion(0);
            when(avatars.findAvatar(11, 1001)).thenReturn(avatar);
            ItemApplicationService items = mock(ItemApplicationService.class);
            when(items.listBagItems(anyInt(), anyInt(), anyInt(), anyInt())).thenReturn(List.of());
            StaminaService stamina = mock(StaminaService.class);
            when(stamina.config()).thenReturn(new StaminaService.StaminaConfig(240, 1, 60_000L, 40, 20, 8, 101,
                    List.of(50), 60));
            when(stamina.snapshot(11)).thenReturn(new StaminaService.StaminaSnapshot(240, 240, 0, 8, 0L, 0, 40));
            when(stamina.tryConsume(anyInt(), anyInt())).thenReturn(StaminaService.ConsumeResult.ok(200));
            DevelopmentPlanService plan = new DevelopmentPlanService(avatars, items, provider(stamina));
            DevelopmentPlanService.PlanResult r = plan.calculate(11, 1001, 80);
            assertTrue(r.ok());
            assertEquals(0, r.retcode());
            assertFalse(r.materials().isEmpty());
            assertTrue(r.materials().stream().anyMatch(m -> m.deficit() > 0));
            assertFalse(r.stages().isEmpty());
            for (int i = 1; i < r.stages().size(); i++) {
                assertTrue(r.stages().get(i - 1).efficiencyBp() >= r.stages().get(i).efficiencyBp());
            }

            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            when(jdbc.update(anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any())).thenThrow(new RuntimeException("no db"));
            cn.itcast.demo.mylunarcore.battle.EncounterConfigRepository encounters =
                    mock(cn.itcast.demo.mylunarcore.battle.EncounterConfigRepository.class);
            when(encounters.current()).thenReturn(new cn.itcast.demo.mylunarcore.battle.EncounterConfig(1,
                    List.of(), List.of(new cn.itcast.demo.mylunarcore.battle.EncounterConfig.DropEntry(201, 2, null, null)),
                    10, 0, 0, 0, 5.0));
            RewardDistributor rewards = mock(RewardDistributor.class);
            when(rewards.grantBattleRewards(anyInt(), anyList(), anyInt(), anyString()))
                    .thenReturn(List.of(new RewardDistributor.GrantedItem(201, 2)));
            SweepService sweep = new SweepService(jdbc, stamina, encounters, rewards);
            sweep.recordClear(11, 101, 6);
            SweepService.BatchSweepResult batch = sweep.batchSweep(11,
                    List.of(new SweepService.BatchSweepStep(101, 2)), 40);
            assertTrue(batch.ok());
            assertEquals(2, batch.completedTimes());
            assertEquals(40, batch.staminaCost());
        }

        @Test
        @DisplayName("未登录/非法目标等级失败")
        void calculateRejectsBadInput() {
            DevelopmentPlanService plan = new DevelopmentPlanService(mock(AvatarRepository.class),
                    mock(ItemApplicationService.class), provider(null));
            assertEquals(1, plan.calculate(0, 1, 80).retcode());
            assertEquals(3, plan.calculate(1, 1, 0).retcode());
            assertEquals(2, plan.calculate(1, 1, 80).retcode());
        }
    }

    @Nested
    @DisplayName("2. Auto 策略权重 + 手动大招")
    class AutoStrategyFlow {

        @Test
        @DisplayName("PRIORITY_BASIC 走普攻；手动大招打断后恢复 Auto")
        void strategyAndManualUlt() {
            BattleManager battles = new BattleManager(mock(BattleSnapshotService.class));
            BattleContext ctx = newBattle(99001L, 42);
            battles.put(ctx);
            BattleAutoService auto = new BattleAutoService(battles,
                    new HeuristicBattleAssistPolicy(new LunarCoreProperties(), mock(AssistFeatureContentRepository.class)),
                    mock(GameSessionManager.class));
            assertTrue(auto.enableAuto(99001L, 42, true, "manual", 2));
            assertEquals(2, ctx.getAutoStrategy());
            BattleAutoService.AutoAction basic = auto.executeOneTurn(ctx);
            assertNotNull(basic);
            assertEquals(1, basic.skillId());

            assertTrue(auto.enableAuto(99001L, 42, true, "manual", 1));
            BattleAutoService.AutoAction ult = auto.executeManualUlt(99001L, 42, 3, 42, List.of());
            assertNotNull(ult);
            assertEquals(3, ult.skillId());
            assertTrue(ult.reason().contains("manual_ult"));
            assertTrue(ctx.isAutoBattle());
            assertFalse(auto.enableAuto(99001L, 42, true, "manual", 9));
        }
    }

    @Nested
    @DisplayName("3. 移动状态机 + 物理反馈")
    class MovementPhysicsFlow {

        @Test
        @DisplayName("JUMP 落地给出地板材质、触觉与声效")
        void jumpLandingFeedback() {
            MovementPhysics.Feedback air = MovementPhysics.resolve(1, 0, 0, 0, MovementPhysics.JUMP, MovementPhysics.WALK);
            assertEquals(MovementPhysics.JUMP, air.moveState());
            assertFalse(air.landing());
            MovementPhysics.Feedback land = MovementPhysics.resolve(1, 0, 0, 0, MovementPhysics.WALK, MovementPhysics.JUMP);
            assertTrue(land.landing());
            assertTrue(List.of("wood", "metal", "grass").contains(land.floorMaterial()));
            assertTrue(land.hapticStrength() > 0);
            assertTrue(land.footstepSfxId() > 0);
        }
    }

    @Nested
    @DisplayName("4. 表情包仓库")
    class EmoteFlow {

        @Test
        @DisplayName("默认拥有挥手/跳舞/击掌；未拥有贴纸 5 失败")
        void inventoryAndPlay() {
            EmoteInventoryService inv = new EmoteInventoryService();
            assertTrue(inv.owns(7, 1));
            assertTrue(inv.play(7, 3, true).duo());
            assertEquals(3, inv.play(7, 5, false).retcode());
            assertEquals(0, inv.play(7, 4, false).retcode());
        }
    }

    @Nested
    @DisplayName("5. 键位云同步 + 特效降级")
    class KeyBindAndFxFlow {

        @Test
        @DisplayName("键位写入内存后可按设备读回；高 RTT 降特效档")
        void cloudBindAndFxQuality() {
            KeyBindCloudService cloud = new KeyBindCloudService(provider(null));
            var saved = cloud.save(8, "mobile", List.of(
                    new KeyBindCloudService.Bind("skill_ult", "Q", "mobile", 120, 0.8f, 0.2f)));
            assertEquals(1, saved.size());
            assertEquals("Q", cloud.load(8, "mobile").get(0).keyCode());
            assertEquals(2, FxQualityAdvisor.resolve(40, 0));
            assertEquals(1, FxQualityAdvisor.resolve(100, 0));
            assertEquals(0, FxQualityAdvisor.resolve(200, 900));
            BattleSystemProto.BattleFxScNotify fx = BattleSystemProto.BattleFxScNotify.newBuilder()
                    .setBattleId(1).setFxQualityLevel(0).setRttMs(200).setPacketLossBp(900).build();
            assertEquals(0, fx.getFxQualityLevel());
        }
    }

    @Nested
    @DisplayName("6. 对话影响标签 + 剧情树快照")
    class StoryTreeFlow {

        @Test
        @DisplayName("选项带 impact_tags；快照含锁/解锁节点")
        void impactTagsAndSnapshot() {
            DialogueTreeRepository trees = new DialogueTreeRepository(new ObjectMapper(), "data");
            assertTrue(trees.reload());
            DialogueTriggerEngine engine = new DialogueTriggerEngine(
                    trees, new DialogueProgressService(), new CutsceneTriggerService(new ObjectMapper(), "data"));
            var start = engine.startByNpc(3, "1001");
            assertTrue(start.ok());
            assertFalse(start.node().safeChoices().get(0).impactTags().isEmpty());
            DialogueTriggerEngine.StoryTreeSnapshot snap = engine.storyTreeSnapshot(3, "1001");
            assertTrue(snap.ok());
            assertTrue(snap.nodes().stream().anyMatch(n -> n.unlocked()));
            assertTrue(snap.nodes().stream().anyMatch(n -> n.current()));
        }
    }

    @Nested
    @DisplayName("7. 家园助产")
    class HomeAssistFlow {

        @Test
        @DisplayName("拜访好友可加速产出 CD；同设施当日重复失败")
        void harvestAssistOncePerDay() {
            HomeBaseService home = new HomeBaseService(new ObjectMapper(), "data");
            assertTrue(home.reloadCatalog());
            assertTrue(home.placeFacility(501, 1, 1).success());
            HomeBaseService.AssistResult ok = home.harvestAssist(502, 501, 1);
            assertTrue(ok.success());
            assertEquals(180_000, ok.reducedCdMs());
            assertEquals(5, home.harvestAssist(502, 501, 1).retcode());
            assertEquals(2, home.harvestAssist(501, 501, 1).retcode());
        }
    }

    @Nested
    @DisplayName("8. 体力溢出提醒 + 合成路径")
    class StaminaCraftFlow {

        @Test
        @DisplayName("满体且不活跃时提醒；高级材料给出合成路径")
        void overflowHintAndCraftPath() {
            StaminaService stamina = mock(StaminaService.class);
            when(stamina.snapshot(9)).thenReturn(new StaminaService.StaminaSnapshot(200, 240, 0, 8, 0L, 0, 40));
            StaminaOverflowHintService hints = new StaminaOverflowHintService(stamina, provider(null), provider(null));
            long now = System.currentTimeMillis();
            assertTrue(hints.maybeHint(9, now, now - 15 * 60_000L));
            assertFalse(hints.maybeHint(9, now + 1_000L, now - 15 * 60_000L));
            assertFalse(hints.maybeHint(9, now + 40 * 60_000L, now - 15 * 60_000L,
                    cn.itcast.demo.mylunarcore.player.PlayerSessionState.DIALOGUE));
            assertTrue(StaminaOverflowHintService.narrativeHint(200, 240).contains("列车长"));
            when(stamina.snapshot(9)).thenReturn(new StaminaService.StaminaSnapshot(100, 240, 0, 8, 0L, 0, 40));
            StaminaOverflowHintService low = new StaminaOverflowHintService(stamina, provider(null), provider(null));
            assertFalse(low.maybeHint(9, now, 0L));

            ItemApplicationService items = new ItemApplicationService(mock(ItemRepository.class));
            assertFalse(items.queryCraftPaths(301).isEmpty());
            assertEquals("CraftItemCsReq",
                    ItemSystemProto.QueryItemSourceScRsp.newBuilder().setCraftCmd("CraftItemCsReq").build().getCraftCmd());
        }
    }

    @Nested
    @DisplayName("协议号段 1046–1069")
    class CmdBindingFlow {

        @Test
        @DisplayName("新协议均已绑定 PacketCmd")
        void cmdsRegistered() {
            assertTrue(hasPacketCmd(CharacterPacketHandlers.class, CmdIds.CALCULATE_UPGRADE_MATERIALS_CS_REQ));
            assertTrue(hasPacketCmd(CharacterPacketHandlers.class, CmdIds.CALCULATE_OPTIMAL_SCHEDULE_CS_REQ));
            assertTrue(hasPacketCmd(DailyLoopPacketHandlers.class, CmdIds.BATCH_SWEEP_CS_REQ));
            assertTrue(hasPacketCmd(BattlePacketHandlers.class, CmdIds.BATTLE_MANUAL_ULT_CS_REQ));
            assertTrue(hasPacketCmd(ScenePacketHandlers.class, CmdIds.SCENE_EMOTE_CS_REQ));
            assertTrue(hasPacketCmd(HallPacketHandlers.class, CmdIds.SEND_CHAT_EMOTE_CS_REQ));
            assertTrue(hasPacketCmd(HallPacketHandlers.class, CmdIds.GET_EMOTE_INVENTORY_CS_REQ));
            assertTrue(hasPacketCmd(SettingsPacketHandlers.class, CmdIds.SYNC_KEY_BIND_CS_REQ));
            assertTrue(hasPacketCmd(DialogueCutscenePacketHandlers.class, CmdIds.GET_STORY_TREE_SNAPSHOT_CS_REQ));
            assertTrue(hasPacketCmd(HomePacketHandlers.class, CmdIds.HOME_HARVEST_ASSIST_CS_REQ));
            assertEquals(1046, CmdIds.CALCULATE_UPGRADE_MATERIALS_CS_REQ);
            assertEquals(1069, CmdIds.HOME_OVERFLOW_SC_NOTIFY);
            assertEquals(1071, CmdIds.CALCULATE_OPTIMAL_SCHEDULE_CS_REQ);
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
