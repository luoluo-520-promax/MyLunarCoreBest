// 战斗辅助策略所在包（方案 D）
package cn.itcast.demo.mylunarcore.battle.assist;

// 当前战局运行时：实体血量、波次、怪物列表
import cn.itcast.demo.mylunarcore.battle.BattleContext;

import java.util.List;

/**
 * 方案 D：战斗辅助策略接口（先启发式，后可替换为 ONNX/小模型）。
 * <p>
 * 仅用于 PVE 提示/自动战斗分支；不得偷看对手隐藏信息；PVP 默认禁用。
 * 输出只读建议，不改写伤害结算与奖励发放。
 */
public interface BattleAssistPolicy {

    /**
     * 综合建议：推荐技能 ID、优先目标实体 ID 列表，以及可解释 reason/弱点/换人文案。
     */
    Suggestion suggest(BattleContext context, int actorEntityId);

    /**
     * 仅取建议技能 ID；无建议时返回 0（客户端表示不托管）。
     */
    default int suggestSkillId(BattleContext context, int actorEntityId) {
        Suggestion s = suggest(context, actorEntityId);
        return s == null ? 0 : s.skillId();
    }

    /**
     * 是否允许在当前战局启用智能托管/提示（开关 + 未结束 + 非 PVP 等由实现决定）。
     */
    boolean isEnabledFor(BattleContext context);

    /**
     * 一条战术建议。
     *
     * @param skillId         建议技能；0 表示无建议
     * @param targetIds       建议锁定的怪物实体 ID（有序）
     * @param reason          可解释字符串，含 heuristic 关键字便于日志检索
     * @param switchAdvice    换人时机文案；可空
     * @param weaknessAdvice  弱点/策略文案；可空
     */
    record Suggestion(int skillId, List<Integer> targetIds, String reason,
                      String switchAdvice, String weaknessAdvice,
                      String battleStateSummary,
                      int lockNextSkillId, int lockCasterEntityId, boolean autoRevertAfterMicro) {
        /** 兼容旧三参构造：换人/弱点留空串 */
        public Suggestion(int skillId, List<Integer> targetIds, String reason) {
            this(skillId, targetIds, reason, "", "", "", 0, 0, false);
        }

        public Suggestion(int skillId, List<Integer> targetIds, String reason,
                          String switchAdvice, String weaknessAdvice) {
            this(skillId, targetIds, reason, switchAdvice, weaknessAdvice, "", 0, 0, false);
        }

        /** 空建议：技能 0、无目标、无文案 */
        public static Suggestion none() {
            return new Suggestion(0, List.of(), "", "", "", "", 0, 0, false);
        }
    }
}
