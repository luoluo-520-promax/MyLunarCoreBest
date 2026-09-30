package cn.itcast.demo.mylunarcore.assist;

/**
 * 从战斗提示文本推断可一键落地的 Auto 策略。
 */
public final class AiAutoSuggestionFactory {

    private AiAutoSuggestionFactory() {
    }

    public static SuggestedAutoOverride fromSuggestion(
            cn.itcast.demo.mylunarcore.battle.assist.BattleAssistPolicy.Suggestion suggestion) {
        if (suggestion == null) {
            return SuggestedAutoOverride.empty();
        }
        SuggestedAutoOverride base = fromBattleAdvice(
                suggestion.reason(), suggestion.weaknessAdvice(), suggestion.switchAdvice());
        if (suggestion.lockNextSkillId() > 0) {
            return SuggestedAutoOverride.withMicro(
                    base.targetFocus(), base.skillPriority(), base.ultReserve(), base.reason(),
                    suggestion.lockNextSkillId(), suggestion.lockCasterEntityId(),
                    suggestion.autoRevertAfterMicro(), suggestion.battleStateSummary());
        }
        if (suggestion.battleStateSummary() != null && !suggestion.battleStateSummary().isBlank()) {
            return SuggestedAutoOverride.withMicro(
                    base.targetFocus(), base.skillPriority(), base.ultReserve(), base.reason(),
                    0, 0, false, suggestion.battleStateSummary());
        }
        return base;
    }

    public static SuggestedAutoOverride fromBattleAdvice(String reason, String weaknessAdvice, String switchAdvice) {
        String hay = ((reason == null ? "" : reason) + " "
                + (weaknessAdvice == null ? "" : weaknessAdvice) + " "
                + (switchAdvice == null ? "" : switchAdvice)).toLowerCase();
        int focus = 0;
        if (hay.contains("精英") || hay.contains("elite") || hay.contains("集火")) {
            focus = 1; // TARGET_FOCUS_ELITE
        } else if (hay.contains("破盾") || hay.contains("盾") || hay.contains("break")) {
            focus = 2; // TARGET_FOCUS_BREAK_SHIELD
        }
        int skillPriority = 1; // PRIORITY_SKILL
        boolean ultReserve = false;
        if (hay.contains("攒能") || hay.contains("保留大招") || hay.contains("save")) {
            skillPriority = 3;
            ultReserve = true;
        } else if (hay.contains("普攻") || hay.contains("basic")) {
            skillPriority = 2;
        }
        String tip = reason == null || reason.isBlank()
                ? "AI 建议已可一键应用到 Auto"
                : reason;
        return SuggestedAutoOverride.of(focus, skillPriority, ultReserve, tip);
    }

    public static SuggestedAutoOverride fromQuestion(String question) {
        if (question == null || question.isBlank()) {
            return SuggestedAutoOverride.empty();
        }
        String q = question.toLowerCase();
        if (!(q.contains("自动") || q.contains("auto") || q.contains("集火") || q.contains("优先")
                || q.contains("战斗") || q.contains("打法"))) {
            return SuggestedAutoOverride.empty();
        }
        return fromBattleAdvice(question, "", "");
    }
}
