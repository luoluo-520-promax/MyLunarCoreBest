package cn.itcast.demo.mylunarcore.hardening;

import cn.itcast.demo.mylunarcore.battle.HateTable;
import cn.itcast.demo.mylunarcore.battle.RoleBuffProvider;
import cn.itcast.demo.mylunarcore.battle.TeamComboTracker;
import cn.itcast.demo.mylunarcore.character.CharacterConstellationConfigRepository;
import cn.itcast.demo.mylunarcore.character.ConstellationEffectApplier;
import cn.itcast.demo.mylunarcore.character.ConstellationService;
import cn.itcast.demo.mylunarcore.dialogue.DialogueNode;
import cn.itcast.demo.mylunarcore.dialogue.DialoguePerformanceScriptRepository;
import cn.itcast.demo.mylunarcore.dialogue.DialoguePerformanceService;
import cn.itcast.demo.mylunarcore.dialogue.DialogueVoiceConfigRepository;
import cn.itcast.demo.mylunarcore.exploration.PuzzleStateService;
import cn.itcast.demo.mylunarcore.guild.RaidRoleAssignmentService;
import cn.itcast.demo.mylunarcore.minigame.MiniGameFramework;
import cn.itcast.demo.mylunarcore.model.AvatarEntity;
import cn.itcast.demo.mylunarcore.repo.AvatarRepository;
import cn.itcast.demo.mylunarcore.scene.ScenePingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 五大内容缺口：剧情表演 / 命座 / 谜题 / Raid 职责连携 / MiniGame。
 */
@DisplayName("内容缺口补齐业务流程")
class ContentGapFillFlowsTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final String dataDir = Path.of("data").toAbsolutePath().toString();

    @Test
    @DisplayName("剧情表演：脚本时间轴与亲和收尾动作可下发")
    void dialoguePerformanceBuildsTimeline() {
        DialoguePerformanceScriptRepository scripts =
                new DialoguePerformanceScriptRepository(mapper, dataDir);
        scripts.reload();
        DialogueVoiceConfigRepository voices =
                new DialogueVoiceConfigRepository(mapper, dataDir);
        voices.reload();
        DialoguePerformanceService svc = new DialoguePerformanceService(
                scripts, voices, mapper, emptyProvider(), emptyProvider());

        DialogueNode node = new DialogueNode("n1", "停云", "关键台词测试", List.of(),
                "", "", 0, "", "perf_intro_climax", "", "tree_1001_n1", "");
        DialoguePerformanceService.PerformancePush push = svc.pushOnEnterNode(1, "1001", node);
        assertNotNull(push);
        assertTrue(push.timelineJson().contains("PLAY_VOICE")
                || push.timelineJson().contains("CAMERA_SHOT"));
        assertTrue(push.estimatedDurationMs() > 0);

        DialogueNode.Choice choice = new DialogueNode.Choice("1", "好感", "n2", "", "",
                List.of("+Affinity"));
        assertDoesNotThrow(() -> svc.pushChoiceAck(1, "1001", choice));
    }

    @Test
    @DisplayName("命座：重复抽取升层并应用到战斗面板")
    void constellationDuplicateRaisesLayer() {
        AvatarRepository avatars = mock(AvatarRepository.class);
        CharacterConstellationConfigRepository cfg =
                new CharacterConstellationConfigRepository(mapper, dataDir);
        cfg.reload();
        ConstellationService service = new ConstellationService(avatars, cfg);

        AvatarEntity existing = new AvatarEntity();
        existing.setAvatarId(1211);
        existing.setRank(1);
        when(avatars.findAvatar(10, 1211)).thenReturn(existing);
        when(avatars.updateRank(10, 1211, 2)).thenReturn(1);

        ConstellationService.ConstellationResult r = service.onAvatarObtained(10, 1211, false);
        assertTrue(r.isNewConstellation());
        assertEquals(2, r.currentLayer());
        verify(avatars).updateRank(10, 1211, 2);

        ConstellationEffectApplier applier = new ConstellationEffectApplier(cfg,
                new cn.itcast.demo.mylunarcore.battle.BuffModifierCatalog());
        ConstellationEffectApplier.ApplyResult applied = applier.apply(1211, 2);
        assertFalse(applied.effects().isEmpty());
        assertTrue(applied.startShieldPct() > 0 || applied.skillDmgBonus() > 0
                || applied.atkPctBonus() >= 0);
    }

    @Test
    @DisplayName("压力板谜题：进入范围后标记已解")
    void pressurePlatePuzzleSolvesInRadius() {
        PuzzleStateService puzzles = new PuzzleStateService(mapper, dataDir,
                emptyProvider(), emptyProvider());
        puzzles.reload();
        puzzles.activate(100, 10001);
        PuzzleStateService.SolveResult r = puzzles.onPlayerPosition(100, 10001, 1, 12.0f, 0f, 8.0f);
        assertNotNull(r);
        assertTrue(r.solved());
        assertEquals(10001, r.puzzleId());
    }

    @Test
    @DisplayName("Raid 职责推荐 + 仇恨表 + 元素连携")
    void raidRoleHateAndCombo() {
        RaidRoleAssignmentService roles = new RaidRoleAssignmentService();
        List<RaidRoleAssignmentService.Assignment> as =
                roles.recommend("raid-1", List.of(1, 2, 3, 4, 5));
        assertEquals(5, as.size());
        assertEquals(RaidRoleAssignmentService.RaidRole.TANK, roles.roleOf("raid-1", 1));

        RoleBuffProvider buffs = new RoleBuffProvider();
        assertEquals(80, buffs.applyIncomingDamage(100, RaidRoleAssignmentService.RaidRole.TANK));
        assertEquals(125, buffs.applyHeal(100, RaidRoleAssignmentService.RaidRole.HEALER));

        HateTable hate = new HateTable();
        hate.addDamageHate(99L, 1, 50);
        hate.applyTaunt(99L, 2, 10);
        assertEquals(2, hate.topTarget(99L));

        TeamComboTracker combo = new TeamComboTracker(mapper, dataDir, emptyProvider());
        combo.reload();
        TeamComboTracker.ComboHit hit = combo.recordSkill(7L, 1, "FIRE", List.of(1, 2));
        assertNull(hit);
        hit = combo.recordSkill(7L, 2, "ICE", List.of(1, 2));
        assertNotNull(hit);
        assertEquals("融化", hit.name());

        ScenePingService ping = new ScenePingService(emptyProvider());
        ping.registerMember(1L, 1);
        ping.registerMember(1L, 2);
        ScenePingService.PingResult pr = ping.ping(1L, 1, ScenePingService.PingType.FOCUS_FIRE,
                1, 0, 1, 9, "FOCUS_FIRE", 5000);
        assertTrue(pr.ok());
        assertEquals(5000, pr.ping().countdownMs());
        assertFalse(ping.isReady(pr.ping().pingId(), 2));
        ping.ack(pr.ping().pingId(), 2);
        assertTrue(ping.isReady(pr.ping().pingId(), 2));
    }

    @Test
    @DisplayName("MiniGame：跑酷开始与检查点结算")
    void miniGameRacingFlow() {
        MiniGameFramework framework = new MiniGameFramework(mapper, dataDir,
                emptyProvider(), emptyProvider(), emptyProvider());
        framework.reload();
        MiniGameFramework.StartResult start = framework.start(42, "racing_festival");
        assertTrue(start.ok());
        assertNotNull(start.payload().get("checkpoints"));

        Map<String, Object> tick1 = framework.tick(42, "racing_festival", Map.of("checkpointIndex", 0));
        assertEquals(0, ((Number) tick1.get("retcode")).intValue());
        framework.tick(42, "racing_festival", Map.of("checkpointIndex", 1));
        framework.tick(42, "racing_festival", Map.of("checkpointIndex", 2));
        Map<String, Object> end = framework.tick(42, "racing_festival", Map.of("checkpointIndex", 3));
        assertTrue(Boolean.TRUE.equals(end.get("ended")));
        assertTrue(((Number) end.get("score")).intValue() > 0);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> emptyProvider() {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(null);
        return p;
    }
}
