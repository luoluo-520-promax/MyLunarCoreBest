package cn.itcast.demo.mylunarcore.hardening;

import cn.itcast.demo.mylunarcore.activity.VersionThemeService;
import cn.itcast.demo.mylunarcore.battle.BattleHealService;
import cn.itcast.demo.mylunarcore.battle.BuffModifierCatalog;
import cn.itcast.demo.mylunarcore.battle.CombatAttributeSheet;
import cn.itcast.demo.mylunarcore.battle.HateTable;
import cn.itcast.demo.mylunarcore.battle.RoleBuffProvider;
import cn.itcast.demo.mylunarcore.battle.TeamComboTracker;
import cn.itcast.demo.mylunarcore.character.CharacterConstellationConfigRepository;
import cn.itcast.demo.mylunarcore.character.ConstellationEffectApplier;
import cn.itcast.demo.mylunarcore.character.ConstellationService;
import cn.itcast.demo.mylunarcore.cutscene.CutsceneTriggerService;
import cn.itcast.demo.mylunarcore.dialogue.DialogueNode;
import cn.itcast.demo.mylunarcore.dialogue.DialoguePerformanceScriptRepository;
import cn.itcast.demo.mylunarcore.dialogue.DialoguePerformanceService;
import cn.itcast.demo.mylunarcore.dialogue.DialogueProgressService;
import cn.itcast.demo.mylunarcore.dialogue.DialogueTreeRepository;
import cn.itcast.demo.mylunarcore.dialogue.DialogueTriggerEngine;
import cn.itcast.demo.mylunarcore.dialogue.DialogueVoiceConfigRepository;
import cn.itcast.demo.mylunarcore.exploration.CollectibleService;
import cn.itcast.demo.mylunarcore.exploration.ExplorationEventService;
import cn.itcast.demo.mylunarcore.exploration.PuzzleStateService;
import cn.itcast.demo.mylunarcore.gacha.GachaBannerType;
import cn.itcast.demo.mylunarcore.gacha.GachaGuaranteeQueryService;
import cn.itcast.demo.mylunarcore.guild.GuildRaidInstanceService;
import cn.itcast.demo.mylunarcore.guild.RaidRoleAssignmentService;
import cn.itcast.demo.mylunarcore.minigame.FishingActivity;
import cn.itcast.demo.mylunarcore.minigame.MiniGameFramework;
import cn.itcast.demo.mylunarcore.minigame.TowerDefenseActivity;
import cn.itcast.demo.mylunarcore.model.AvatarEntity;
import cn.itcast.demo.mylunarcore.model.PlayerGachaBannerInfoEntity;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.repo.AvatarRepository;
import cn.itcast.demo.mylunarcore.repo.GachaRepository;
import cn.itcast.demo.mylunarcore.scene.ScenePingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 五大内容缺口全面业务流程：配置加载 → 核心逻辑 → 协议 CmdId / 副作用闭环。
 */
@DisplayName("内容缺口全面业务流程")
class ContentGapComprehensiveFlowsTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final String dataDir = Path.of("data").toAbsolutePath().toString();

    @Nested
    @DisplayName("缺口一：剧情表演指令集")
    class StoryBeatFlows {

        private DialoguePerformanceService performanceService;
        private DialogueTriggerEngine engine;
        private GameSession session;
        private java.util.List<GamePacket> sentPackets;

        @BeforeEach
        void setUp() {
            DialoguePerformanceScriptRepository scripts =
                    new DialoguePerformanceScriptRepository(mapper, dataDir);
            assertTrue(scripts.reload());
            DialogueVoiceConfigRepository voices =
                    new DialogueVoiceConfigRepository(mapper, dataDir);
            assertTrue(voices.reload());

            sentPackets = new java.util.ArrayList<>();
            session = mock(GameSession.class);
            doAnswer(inv -> {
                sentPackets.add(inv.getArgument(0));
                return null;
            }).when(session).send(any(GamePacket.class));
            GameSessionManager sessions = mock(GameSessionManager.class);
            when(sessions.getOrNull(anyLong())).thenReturn(session);

            performanceService = new DialoguePerformanceService(
                    scripts, voices, mapper, providerOf(sessions), emptyProvider());

            DialogueTreeRepository trees = new DialogueTreeRepository(mapper, dataDir);
            assertTrue(trees.reload());
            engine = new DialogueTriggerEngine(
                    trees,
                    new DialogueProgressService(),
                    new CutsceneTriggerService(mapper, dataDir),
                    emptyProvider(),
                    providerOf(sessions),
                    providerOf(performanceService));
        }

        @Test
        @DisplayName("进入带 performanceId 的节点应推送 1248 时间轴与语音口型")
        void enterNodePushesPerformanceNotify() {
            sentPackets.clear();
            DialogueNode node = new DialogueNode("n1", "列车长", "欢迎登上星穹列车。",
                    List.of(), "", "", 0, "", "perf_intro_climax", "", "tree_1001_n1", "");
            DialoguePerformanceService.PerformancePush push =
                    performanceService.pushOnEnterNode(1001, "1001", node);
            assertNotNull(push);
            assertEquals("perf_intro_climax", push.performanceId());
            assertTrue(push.timelineJson().contains("CAMERA_SHOT"));
            assertTrue(push.timelineJson().contains("PLAY_EMOTION"));
            assertTrue(push.estimatedDurationMs() >= 1000);
            assertFalse(push.voiceId().isBlank());
            assertFalse(sentPackets.isEmpty());
            GamePacket packet = sentPackets.get(sentPackets.size() - 1);
            assertEquals(CmdIds.DIALOGUE_PERFORMANCE_SC_NOTIFY, packet.getCmdId());
            String body = new String(packet.getPayload(), StandardCharsets.UTF_8);
            assertTrue(body.contains("lipSyncTimestamps") || body.contains("timelineJson"));
        }

        @Test
        @DisplayName("无脚本节点应回退为 PLAY_VOICE 时间轴")
        void voiceOnlyFallbackTimeline() {
            DialogueNode node = new DialogueNode("n9", "NPC", "普通台词",
                    List.of(), "", "", 0, "", "", "", "tree_1001_n2", "");
            DialoguePerformanceService.PerformancePush push =
                    performanceService.pushOnEnterNode(1, "1001", node);
            assertNotNull(push);
            assertTrue(push.timelineJson().contains("PLAY_VOICE"));
            assertTrue(push.audioKey().contains("vo/") || push.audioKey().startsWith("tts:"));
        }

        @Test
        @DisplayName("对话树启动→选择亲和选项应触发表演链路")
        void dialogueTreeStartAndAffinityChoice() {
            sentPackets.clear();
            DialogueTriggerEngine.DialogueStepResult start = engine.startByNpc(3001, "1001");
            assertTrue(start.ok());
            assertEquals("n1", start.node().nodeId());
            assertEquals("perf_intro_climax", start.node().performanceId());
            assertTrue(sentPackets.stream().anyMatch(p ->
                    p.getCmdId() == CmdIds.DIALOGUE_PERFORMANCE_SC_NOTIFY));

            int before = sentPackets.size();
            DialogueTriggerEngine.DialogueStepResult next = engine.choose(3001, "1001", "1");
            assertTrue(next.ok());
            assertTrue(sentPackets.size() > before,
                    "选择后应继续推送表演包, before=" + before + " after=" + sentPackets.size());
            assertTrue(sentPackets.stream().skip(before).anyMatch(p ->
                    p.getCmdId() == CmdIds.DIALOGUE_PERFORMANCE_SC_NOTIFY));
        }
    }

    @Nested
    @DisplayName("缺口二：命座/星魂系统")
    class ConstellationFlows {

        private CharacterConstellationConfigRepository cfg;
        private AvatarRepository avatars;
        private ConstellationService service;
        private ConstellationEffectApplier applier;

        @BeforeEach
        void setUp() {
            cfg = new CharacterConstellationConfigRepository(mapper, dataDir);
            assertTrue(cfg.reload());
            avatars = mock(AvatarRepository.class);
            service = new ConstellationService(avatars, cfg);
            applier = new ConstellationEffectApplier(cfg, new BuffModifierCatalog());
        }

        @Test
        @DisplayName("首次获得创建 rank=0；重复获得升层至上限")
        void obtainAndCapAtMaxLayer() {
            when(avatars.findAvatar(1, 1211)).thenReturn(null);
            when(avatars.insertAvatar(1, 1211)).thenReturn(99L);
            ConstellationService.ConstellationResult first = service.onAvatarObtained(1, 1211, true);
            assertFalse(first.isNewConstellation());
            assertEquals(0, first.currentLayer());
            verify(avatars).insertAvatar(1, 1211);

            AvatarEntity e = new AvatarEntity();
            e.setAvatarId(1211);
            e.setRank(5);
            when(avatars.findAvatar(1, 1211)).thenReturn(e);
            when(avatars.updateRank(1, 1211, 6)).thenReturn(1);
            ConstellationService.ConstellationResult up = service.onAvatarObtained(1, 1211, false);
            assertTrue(up.isNewConstellation());
            assertEquals(6, up.currentLayer());

            e.setRank(6);
            ConstellationService.ConstellationResult capped = service.onAvatarObtained(1, 1211, false);
            assertFalse(capped.isNewConstellation());
            assertEquals(6, capped.currentLayer());
            verify(avatars, times(1)).updateRank(anyInt(), anyInt(), anyInt());
        }

        @Test
        @DisplayName("命座效果写入 CombatAttributeSheet 并提升 ATK%")
        void applyEffectsToCombatSheet() {
            ConstellationEffectApplier.ApplyResult r = applier.apply(1001, 1);
            assertEquals(1, r.effects().size());
            assertEquals(8.0, r.atkPctBonus(), 0.01);

            CombatAttributeSheet sheet = new CombatAttributeSheet(
                    new CombatAttributeSheet.Snapshot(1000, 100, 50, 100, 5, 50));
            applier.applyToSheet(sheet, 1001, 1);
            CombatAttributeSheet.Snapshot resolved = sheet.resolve();
            assertTrue(resolved.atk() > 100);

            ConstellationEffectApplier.ApplyResult layered = applier.apply(1211, 2);
            assertTrue(layered.skillDmgBonus() >= 20 || layered.startShieldPct() >= 10);
            assertTrue(layered.effects().size() >= 2);
        }

        @Test
        @DisplayName("保底查询携带 UP 命座层数字段结构")
        void guaranteeInfoIncludesConstellationMap() {
            GachaRepository gachaRepo = mock(GachaRepository.class);
            PlayerGachaBannerInfoEntity pity = new PlayerGachaBannerInfoEntity();
            pity.setPity5(70);
            pity.setPity4(5);
            pity.setFailedUpCount(1);
            when(gachaRepo.loadOrCreateBannerInfo(eq(9), anyInt())).thenReturn(pity);

            AvatarEntity av = new AvatarEntity();
            av.setRank(3);
            when(avatars.findAvatar(9, 1211)).thenReturn(av);

            GachaGuaranteeQueryService guarantee =
                    new GachaGuaranteeQueryService(gachaRepo, emptyProvider(), providerOf(service));
            GachaGuaranteeQueryService.GuaranteeInfo info =
                    guarantee.getGuaranteeInfo(9, GachaBannerType.AVATAR_UP);
            assertEquals(70, info.pity5());
            assertEquals(20, info.remainToHard5());
            assertTrue(info.softGuarantee5());
            assertNotNull(info.upConstellationLayers());
            assertEquals(4, guarantee.listGuaranteeInfo(9, 0).size());
        }
    }

    @Nested
    @DisplayName("缺口三：探索谜题 / 收集 / 裂隙事件")
    class ExplorationFlows {

        private PuzzleStateService puzzles;

        @BeforeEach
        void setUp() {
            puzzles = new PuzzleStateService(mapper, dataDir, emptyProvider(), emptyProvider());
            assertTrue(puzzles.reload());
        }

        @Test
        @DisplayName("元素点亮：错误属性无效，正确属性解谜")
        void elementLightPuzzle() {
            puzzles.activate(1, 10002);
            assertNull(puzzles.onElementHit(1, 10002, "ICE"));
            PuzzleStateService.SolveResult ok = puzzles.onElementHit(1, 10002, "FIRE");
            assertNotNull(ok);
            assertTrue(ok.solved());
            assertEquals(90002, ok.rewardId());
            assertNotNull(ok.hiddenDoorPos());
        }

        @Test
        @DisplayName("顺序踩踏：错误重置，正确序列解谜")
        void sequenceStepPuzzle() {
            puzzles.activate(2, 10003);
            assertNull(puzzles.onSequencePad(2, 10003, "pad_b")); // 错误，重置
            assertNull(puzzles.onSequencePad(2, 10003, "pad_a"));
            assertNull(puzzles.onSequencePad(2, 10003, "pad_b"));
            PuzzleStateService.SolveResult ok = puzzles.onSequencePad(2, 10003, "pad_c");
            assertNotNull(ok);
            assertTrue(ok.solved());
        }

        @Test
        @DisplayName("限时跑酷：按检查点顺序完成")
        void timedParkourPuzzle() {
            puzzles.activate(3, 10004);
            assertNull(puzzles.onParkourCheckpoint(3, 10004, "cp1"));
            assertNull(puzzles.onParkourCheckpoint(3, 10004, "cp2"));
            PuzzleStateService.SolveResult ok = puzzles.onParkourCheckpoint(3, 10004, "cp3");
            assertNotNull(ok);
            assertTrue(ok.solved());
        }

        @Test
        @DisplayName("收集品：普通可见、隐藏近距可见、彩蛋需动作")
        void collectibleVisibilityAndObtain() {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            when(jdbc.update(anyString(), any(), any(), any())).thenReturn(1);
            when(jdbc.queryForObject(anyString(), eq(Integer.class), any())).thenReturn(1);

            CollectibleService collectibles = new CollectibleService(
                    jdbc, mapper, dataDir, emptyProvider(), emptyProvider());
            collectibles.reload();

            List<CollectibleService.CollectibleDef> far =
                    collectibles.listVisibleInAoi(10001, 1, 0f, 0f, 0f);
            assertTrue(far.stream().anyMatch(c -> "NORMAL".equalsIgnoreCase(c.tier())));
            assertTrue(far.stream().noneMatch(c -> "HIDDEN".equalsIgnoreCase(c.tier())));
            assertTrue(far.stream().noneMatch(c -> "EASTER_EGG".equalsIgnoreCase(c.tier())));

            List<CollectibleService.CollectibleDef> near =
                    collectibles.listVisibleInAoi(10001, 1, 42f, 0.5f, 27f);
            assertTrue(near.stream().anyMatch(c -> "archive_book_01".equals(c.collectibleId())));

            CollectibleService.CollectResult eggFail =
                    collectibles.obtain(7, "easter_egg_kick_01", "WALK");
            assertFalse(eggFail.newlyObtained());
            CollectibleService.CollectResult eggOk =
                    collectibles.obtain(7, "easter_egg_kick_01", "KICK_PEBBLE");
            assertTrue(eggOk.newlyObtained());
        }

        @Test
        @DisplayName("裂隙事件：刷新→开战→清波领奖")
        void explorationRiftChallenge() {
            ExplorationEventService events = new ExplorationEventService(
                    mapper, dataDir, emptyProvider(), emptyProvider(), emptyProvider());
            assertTrue(events.reload());
            assertFalse(events.refreshActiveRifts().isEmpty());

            ExplorationEventService.ChallengeResult start =
                    events.startChallenge(11, "rift_dusk_10001");
            assertTrue(start.ok());

            assertTrue(events.onWaveCleared(11, "rift_dusk_10001").ok());
            assertTrue(events.onWaveCleared(11, "rift_dusk_10001").ok());
            ExplorationEventService.ChallengeResult done =
                    events.onWaveCleared(11, "rift_dusk_10001");
            assertTrue(done.ok());
            assertEquals(21001, done.rewardItemId());
            assertEquals(5, done.rewardCount());
            assertEquals("relic_fragment", done.rewardHint());
        }
    }

    @Nested
    @DisplayName("缺口四：Raid 职责 / 连携 / Ping")
    class RaidSocialFlows {

        @Test
        @DisplayName("进本满员后进入 READY_ROOM 策略期并分配职责")
        void raidStrategyPhaseWithRoles() {
            RaidRoleAssignmentService roles = new RaidRoleAssignmentService();
            GuildRaidInstanceService raid = new GuildRaidInstanceService(
                    emptyProvider(), emptyProvider(), providerOf(roles));
            GuildRaidInstanceService.RaidInstance inst = raid.create(88L, 1_000_000L);
            for (int i = 1; i <= GuildRaidInstanceService.MIN_PLAYERS; i++) {
                assertTrue(raid.join(inst.instanceId(), i, 1000L + i));
            }
            assertTrue(raid.canStart(inst.instanceId()));
            assertTrue(raid.enterStrategyPhase(inst.instanceId()));
            assertEquals("READY_ROOM", raid.get(inst.instanceId()).phase());
            assertTrue(raid.get(inst.instanceId()).strategyEndsAtMs().get() > System.currentTimeMillis());
            assertEquals(RaidRoleAssignmentService.RaidRole.TANK, roles.roleOf(inst.instanceId(), 1));
            assertTrue(raid.assignRole(inst.instanceId(), 5, "HEALER"));
            assertEquals(RaidRoleAssignmentService.RaidRole.HEALER, roles.roleOf(inst.instanceId(), 5));
            assertTrue(raid.applyDamage(inst.instanceId(), 1, 5000) < 1_000_000L);
        }

        @Test
        @DisplayName("治疗溅射 + 职责减伤/加疗")
        void healSplashAndRoleBuffs() {
            RoleBuffProvider buffs = new RoleBuffProvider();
            BattleHealService heal = new BattleHealService(buffs);
            List<BattleHealService.HealTarget> allies = List.of(
                    new BattleHealService.HealTarget(1, 0, 0, 0),
                    new BattleHealService.HealTarget(2, 1, 0, 0),
                    new BattleHealService.HealTarget(3, 50, 0, 0)
            );
            BattleHealService.HealResult r = heal.heal(1, 100,
                    RaidRoleAssignmentService.RaidRole.HEALER,
                    0, 0, 0, 5.0f, 0.3, allies);
            assertEquals(125, r.primaryHeal());
            assertEquals(1, r.splashes().size());
            assertEquals(2, r.splashes().get(0).entityId());
            assertEquals(38, r.splashes().get(0).amount()); // 125 * 0.3
        }

        @Test
        @DisplayName("元素连携火+雷超载；Ping 打断倒计时与未就绪名单")
        void comboAndPingDiscipline() {
            TeamComboTracker combo = new TeamComboTracker(mapper, dataDir, emptyProvider());
            assertTrue(combo.reload());
            assertNull(combo.recordSkill(3L, 1, "FIRE", List.of(1)));
            TeamComboTracker.ComboHit hit = combo.recordSkill(3L, 2, "LIGHTNING", List.of(1, 2));
            assertNotNull(hit);
            assertEquals("超载", hit.name());
            assertEquals(25.0, hit.damageBonusPct(), 0.01);

            HateTable hate = new HateTable();
            hate.addDamageHate(3L, 10, 100);
            hate.addDamageHate(3L, 11, 200);
            assertEquals(11, hate.topTarget(3L));
            hate.applyTaunt(3L, 10, 1);
            assertEquals(10, hate.topTarget(3L));

            ScenePingService ping = new ScenePingService(emptyProvider());
            ping.registerMember(9L, 1);
            ping.registerMember(9L, 2);
            ping.registerMember(9L, 3);
            ScenePingService.PingResult pr = ping.ping(9L, 1, ScenePingService.PingType.INTERRUPT,
                    0, 0, 0, 7, "INTERRUPT", 0);
            assertTrue(pr.ok());
            assertEquals(3000, pr.ping().countdownMs());
            ping.ack(pr.ping().pingId(), 1);
            List<Integer> pending = ping.pendingMembers(pr.ping().pingId(), 9L);
            assertTrue(pending.contains(2));
            assertTrue(pending.contains(3));
            assertFalse(pending.contains(1));
        }
    }

    @Nested
    @DisplayName("缺口五：MiniGame 框架与版本主题池")
    class MiniGameFlows {

        private MiniGameFramework framework;

        @BeforeEach
        void setUp() {
            framework = new MiniGameFramework(mapper, dataDir,
                    emptyProvider(), emptyProvider(), emptyProvider());
            assertTrue(framework.reload());
            assertEquals(3, framework.listGames().size());
        }

        @Test
        @DisplayName("跑酷：检查点推进至结算出分")
        void racingEndToEnd() {
            assertTrue(framework.start(1, "racing_festival").ok());
            for (int i = 0; i < 3; i++) {
                Map<String, Object> tick = framework.tick(1, "racing_festival",
                        Map.of("checkpointIndex", i));
                assertFalse(Boolean.TRUE.equals(tick.get("ended")));
            }
            Map<String, Object> end = framework.tick(1, "racing_festival",
                    Map.of("checkpointIndex", 3));
            assertTrue(Boolean.TRUE.equals(end.get("ended")));
            assertTrue(((Number) end.get("score")).intValue() > 0);
            assertEquals("racing_token", end.get("rewardHint"));
        }

        @Test
        @DisplayName("塔防：放置炮台→击杀→清波结束")
        void towerDefenseEndToEnd() {
            assertTrue(framework.start(2, "tower_defense_outpost").ok());
            framework.tick(2, "tower_defense_outpost", Map.of("action", "place_turret"));
            framework.tick(2, "tower_defense_outpost", Map.of("action", "kill", "points", 20));
            Map<String, Object> mid = null;
            for (int w = 0; w < 5; w++) {
                mid = framework.tick(2, "tower_defense_outpost", Map.of("action", "wave_clear"));
            }
            assertNotNull(mid);
            assertTrue(Boolean.TRUE.equals(mid.get("ended")));
            assertTrue(((Number) mid.get("score")).intValue() >= 20);
        }

        @Test
        @DisplayName("钓鱼：多次尝试后结束并返回收获分")
        void fishingEndToEnd() {
            assertTrue(framework.start(3, "fishing_harbor").ok());
            Map<String, Object> last = Map.of();
            for (int i = 0; i < 10; i++) {
                last = framework.tick(3, "fishing_harbor",
                        Map.of("qteSuccess", true, "maxAttempts", 10));
            }
            assertTrue(Boolean.TRUE.equals(last.get("ended")));
            assertTrue(last.containsKey("catches"));
            assertTrue(((Number) last.get("score")).intValue() >= 0);
        }

        @Test
        @DisplayName("独立玩法实例钩子契约 onStart/onTick/onEnd/getReward")
        void miniGameInstanceContract() {
            TowerDefenseActivity td = new TowerDefenseActivity("td", 2, 2);
            td.onStart(9, Map.of());
            assertFalse(td.onTick(9, Map.of("action", "place_turret")));
            assertFalse(td.onTick(9, Map.of("action", "wave_clear")));
            assertTrue(td.onTick(9, Map.of("action", "wave_clear")));
            assertTrue(((Number) td.getReward(9).get("score")).intValue() >= 0);

            FishingActivity fish = new FishingActivity("fish", 1.0);
            fish.onStart(8, Map.of());
            assertTrue(fish.onTick(8, Map.of("qteSuccess", true, "maxAttempts", 1)));
            assertEquals(100, ((Number) fish.getReward(8).get("score")).intValue());
        }

        @Test
        @DisplayName("版本主题含 miniGamePool")
        void versionThemeExposesMiniGamePool() {
            VersionThemeService themes = new VersionThemeService(
                    mapper, emptyProvider(), emptyProvider(),
                    Path.of(dataDir, "VersionThemeConfigs.json").toString());
            themes.reload();
            VersionThemeService.ThemeView view = themes.currentView();
            assertNotNull(view.current());
            assertFalse(view.current().miniGamePool().isEmpty());
            assertTrue(view.current().miniGamePool().contains("racing_festival")
                    || view.current().miniGamePool().contains("fishing_harbor"));
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> emptyProvider() {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(null);
        return p;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> providerOf(T bean) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(bean);
        return p;
    }
}
