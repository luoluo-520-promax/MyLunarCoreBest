package cn.itcast.demo.mylunarcore.dialogue;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * DialogueConditionEvaluatorTest。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code DialogueConditionEvaluatorTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
class DialogueConditionEvaluatorTest {

    /**
     * 验证点：emptyConditionIsTrue。
     * <p>测试方法 {@code emptyConditionIsTrue}：
     * <ul>
     *   <li>{@code assertTrue(DialogueConditionEvaluator.evaluate("", DialogueConditionEvaluator.EvalContext.ofFlags(Set.of())));}</li>
     *   <li>{@code assertTrue(DialogueConditionEvaluator.evaluate(null, DialogueConditionEvaluator.EvalContext.ofFlags(Set.of())));}</li>
     * </ul>
     */
    @Test
    void emptyConditionIsTrue() {
        assertTrue(DialogueConditionEvaluator.evaluate("", DialogueConditionEvaluator.EvalContext.ofFlags(Set.of())));
        assertTrue(DialogueConditionEvaluator.evaluate(null, DialogueConditionEvaluator.EvalContext.ofFlags(Set.of())));
    }

    /**
     * 验证点：flagAndOrNot。
     * <p>测试方法 {@code flagAndOrNot}：
     * <ul>
     *   <li>{@code assertTrue(DialogueConditionEvaluator.evaluate("flag:met_npc", ctx));}</li>
     *   <li>{@code assertTrue(DialogueConditionEvaluator.evaluate("flag:met_npc && flag:vip", ctx));}</li>
     *   <li>{@code assertFalse(DialogueConditionEvaluator.evaluate("flag:met_npc && flag:missing", ctx));}</li>
     *   <li>{@code assertTrue(DialogueConditionEvaluator.evaluate("flag:missing || flag:vip", ctx));}</li>
     *   <li>{@code assertFalse(DialogueConditionEvaluator.evaluate("!flag:vip", ctx));}</li>
     *   <li>{@code assertTrue(DialogueConditionEvaluator.evaluate("!flag:missing", ctx));}</li>
     * </ul>
     */
    @Test
    void flagAndOrNot() {
        var ctx = DialogueConditionEvaluator.EvalContext.ofFlags(Set.of("met_npc", "vip"));
        assertTrue(DialogueConditionEvaluator.evaluate("flag:met_npc", ctx));
        assertTrue(DialogueConditionEvaluator.evaluate("flag:met_npc && flag:vip", ctx));
        assertFalse(DialogueConditionEvaluator.evaluate("flag:met_npc && flag:missing", ctx));
        assertTrue(DialogueConditionEvaluator.evaluate("flag:missing || flag:vip", ctx));
        assertFalse(DialogueConditionEvaluator.evaluate("!flag:vip", ctx));
        assertTrue(DialogueConditionEvaluator.evaluate("!flag:missing", ctx));
    }

    /**
     * 验证点：levelCompare。
     * <p>测试方法 {@code levelCompare}：
     * <ul>
     *   <li>{@code assertTrue(DialogueConditionEvaluator.evaluate("level:>=10", ctx));}</li>
     *   <li>{@code assertFalse(DialogueConditionEvaluator.evaluate("level:>=30", ctx));}</li>
     * </ul>
     */
    @Test
    void levelCompare() {
        var ctx = new DialogueConditionEvaluator.EvalContext(Set.of(), 20, id -> false, k -> 0);
        assertTrue(DialogueConditionEvaluator.evaluate("level:>=10", ctx));
        assertFalse(DialogueConditionEvaluator.evaluate("level:>=30", ctx));
    }
}
