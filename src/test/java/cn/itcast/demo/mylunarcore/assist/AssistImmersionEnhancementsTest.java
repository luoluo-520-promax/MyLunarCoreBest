package cn.itcast.demo.mylunarcore.assist;

import cn.itcast.demo.mylunarcore.assist.funnel.AssistIntentFunnelService;
import cn.itcast.demo.mylunarcore.assist.visual.SpatialPuzzleHintBuilder;
import cn.itcast.demo.mylunarcore.battle.BattleContext;
import cn.itcast.demo.mylunarcore.battle.assist.BattleStateVectorSerializer;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.repo.BattleMonsterWaveRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("AI 助手六项增强单元测试")
class AssistImmersionEnhancementsTest {

    @Test
    @DisplayName("空间锚点链应生成 3 步顺序指引")
    void spatialChainHasThreeSteps() {
        SpatialPuzzleHintBuilder builder = new SpatialPuzzleHintBuilder();
        var chain = builder.build(1, 10f, 0f, 10f, null,
                new cn.itcast.demo.mylunarcore.assist.visual.VisualFrameTemporalAnalyzer.MotionHint(
                        true, 0.2, "浮台移动"));
        assertEquals(3, chain.steps().size());
        assertEquals(1, chain.steps().get(0).sequenceStep());
        assertTrue(chain.steps().get(0).directionHint().contains("花坛"));
    }

    @Test
    @DisplayName("三级漏斗一级应命中「附近」意图")
    void funnelTier1Nearby() {
        LunarCoreProperties props = new LunarCoreProperties();
        AssistAnswerCache cache = new AssistAnswerCache(props);
        cache.init();
        AssistIntentFunnelService funnel = new AssistIntentFunnelService(props, cache);
        Optional<AssistIntentFunnelService.FunnelResult> hit =
                funnel.tryTier1Local(1L, "附近有什么", "general", "", "v1", "zh-CN");
        assertTrue(hit.isPresent());
        assertTrue(hit.get().answer().isPresent());
        assertTrue(hit.get().estimatedWaitMs() < 100);
    }

    @Test
    @DisplayName("战斗状态向量应识别丝血斩杀窗口")
    void battleStateDetectsExecute() {
        List<BattleMonsterWaveRepository.WaveConfig> waves = List.of(
                new BattleMonsterWaveRepository.WaveConfig(1, 100, 1, "[101]", 5));
        BattleContext ctx = BattleContext.createNew(1L, 77, 1, 100, 1_700_000_000L, waves);
        var monsters = ctx.listAliveMonsterIdsInCurrentWave();
        if (!monsters.isEmpty()) {
            var entity = ctx.getEntity(monsters.get(0));
            if (entity != null) {
                entity.setHp(50);
            }
        }
        var vec = BattleStateVectorSerializer.serialize(ctx);
        assertTrue(vec.summary().contains("executeWindow") || vec.lowHpExecute());
    }

    @Test
    @DisplayName("SuggestedAutoOverride 应支持微观干预字段")
    void microOverrideFields() {
        SuggestedAutoOverride o = SuggestedAutoOverride.withMicro(
                1, 2, false, "留战技", 2, 77, true, "skillPts=2");
        assertTrue(o.hasMicroIntervention());
        assertEquals(2, o.lockNextSkillId());
        assertTrue(o.autoRevertAfterAction());
    }
}
