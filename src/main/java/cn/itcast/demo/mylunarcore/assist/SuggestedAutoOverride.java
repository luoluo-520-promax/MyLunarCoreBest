package cn.itcast.demo.mylunarcore.assist;



/**

 * AI 建议落地到 BattleAuto 的策略覆盖参数（含微观干预单次锁定）。

 */

public record SuggestedAutoOverride(

        int targetFocus,

        int skillPriority,

        boolean ultReserve,

        String reason,

        int applyCountdownMs,

        int lockNextSkillId,

        int lockCasterEntityId,

        boolean autoRevertAfterAction,

        String battleStateSummary

) {

    public static final int COUNTDOWN_DEFAULT_MS = 3000;



    public static SuggestedAutoOverride of(int targetFocus, int skillPriority, boolean ultReserve, String reason) {

        return new SuggestedAutoOverride(targetFocus, skillPriority, ultReserve,

                reason == null ? "" : reason, COUNTDOWN_DEFAULT_MS,

                0, 0, false, "");

    }



    public static SuggestedAutoOverride withMicro(int targetFocus, int skillPriority, boolean ultReserve,

                                                  String reason, int lockSkillId, int lockCasterId,

                                                  boolean revertAfter, String battleStateSummary) {

        return new SuggestedAutoOverride(targetFocus, skillPriority, ultReserve,

                reason == null ? "" : reason, COUNTDOWN_DEFAULT_MS,

                lockSkillId, lockCasterId, revertAfter,

                battleStateSummary == null ? "" : battleStateSummary);

    }



    public static SuggestedAutoOverride empty() {

        return new SuggestedAutoOverride(0, 0, false, "", 0, 0, 0, false, "");

    }



    public boolean isPresent() {

        return skillPriority > 0 || targetFocus > 0 || lockNextSkillId > 0

                || (reason != null && !reason.isBlank());

    }



    public boolean hasMicroIntervention() {

        return lockNextSkillId > 0;

    }

}


