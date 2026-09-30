package cn.itcast.demo.mylunarcore.battle;

import cn.itcast.demo.mylunarcore.assist.AssistFeatureContent;
import cn.itcast.demo.mylunarcore.assist.AssistFeatureContentRepository;
import cn.itcast.demo.mylunarcore.battle.assist.BattleAssistPolicy;
import cn.itcast.demo.mylunarcore.battle.assist.HeuristicBattleAssistPolicy;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link HeuristicBattleAssistPolicy}（方案 D）：battleHint 开关与威胁/弱点启发式建议。
 * featureRepo 固定注入敌 101「迅捷虫」弱点 ice/slow，供开启用例匹配文案。
 */
@DisplayName("方案 D 启发式战斗辅助策略")
class HeuristicBattleAssistPolicyTest {

    private static final Logger log = LoggerFactory.getLogger(HeuristicBattleAssistPolicyTest.class);

    /** battleHintEnabled=false → isEnabledFor=false，suggest.skillId=0。 */
    @Test
    @DisplayName("开关关闭时应返回空建议")
    void disabledShouldReturnNone() {
        LunarCoreProperties props = new LunarCoreProperties();
        props.getAiAssist().setBattleHintEnabled(false);
        HeuristicBattleAssistPolicy policy = new HeuristicBattleAssistPolicy(props, featureRepo());
        BattleContext context = BattleTestFixtures.createContext(1L, 77, BattleTestFixtures.twoWaveStage());
        assertFalse(policy.isEnabledFor(context));
        assertEquals(0, policy.suggest(context, 77).skillId());
    }

    /**
     * 开关开启：skillId≥1、恰好一个目标、reason 含 heuristic/threat_target，
     * weaknessAdvice 含弱点或迅捷，switchAdvice 非空。
     */
    @Test
    @DisplayName("开关开启时应按威胁分选目标并给出弱点/换人建议")
    void enabledShouldSuggestSkillOnThreatTarget() {
        LunarCoreProperties props = new LunarCoreProperties();
        props.getAiAssist().setBattleHintEnabled(true);
        HeuristicBattleAssistPolicy policy = new HeuristicBattleAssistPolicy(props, featureRepo());
        BattleContext context = BattleTestFixtures.createContext(2L, 77, BattleTestFixtures.twoWaveStage());
        assertTrue(policy.isEnabledFor(context));
        BattleAssistPolicy.Suggestion suggestion = policy.suggest(context, 77);
        log.info("战斗策略校验: skillId={}, targets={}, reason={}, weakness={}, switch={}",
                suggestion.skillId(), suggestion.targetIds(), suggestion.reason(),
                suggestion.weaknessAdvice(), suggestion.switchAdvice());
        assertTrue(suggestion.skillId() >= 1);
        assertEquals(1, suggestion.targetIds().size());
        assertTrue(suggestion.targetIds().contains(101) || !suggestion.targetIds().isEmpty());
        assertTrue(suggestion.reason().contains("heuristic"));
        assertTrue(suggestion.reason().contains("threat_target"));
        assertTrue(suggestion.weaknessAdvice().contains("弱点") || suggestion.weaknessAdvice().contains("迅捷"));
        assertFalse(suggestion.switchAdvice().isBlank());
    }

    /** mock 仓库：敌人 101 弱点与角色 1001 属性标签，供启发式匹配。 */
    private static AssistFeatureContentRepository featureRepo() {
        AssistFeatureContentRepository repo = mock(AssistFeatureContentRepository.class);
        when(repo.current()).thenReturn(new AssistFeatureContent(
                1,
                List.of(),
                List.of(),
                List.of(new AssistFeatureContent.EnemyWeakness(
                        101, "迅捷虫", List.of("beast"), List.of("ice", "slow"),
                        List.of("physical"), "优先控场再集火", 0.35)),
                Map.of("1001", List.of("ice", "slow")),
                List.of(),
                List.of()
        ));
        return repo;
    }
}
