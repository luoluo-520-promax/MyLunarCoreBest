package cn.itcast.demo.mylunarcore.battle;

/**
 * 根据权威伤害结算结果合成打击感元数据，供客户端严格播放震屏/慢放/跳字样式。
 */
public final class BattleFxComposer {

    private BattleFxComposer() {}

    public record FxHint(boolean critical, boolean weakness, boolean kill,
                         int cameraShake, float timeScale, String damagePopupStyle,
                         int displayDamage, int targetEntityId,
                         HitStopComposer.HitStopPlan hitStop,
                         float cameraShakeIntensity, float cameraFovImpact) {
        public FxHint(boolean critical, boolean weakness, boolean kill,
                      int cameraShake, float timeScale, String damagePopupStyle,
                      int displayDamage, int targetEntityId) {
            this(critical, weakness, kill, cameraShake, timeScale, damagePopupStyle,
                    displayDamage, targetEntityId, HitStopComposer.HitStopPlan.empty(),
                    shakeIntensity(cameraShake), fovImpact(cameraShake, kill, critical));
        }

        public FxHint(boolean critical, boolean weakness, boolean kill,
                      int cameraShake, float timeScale, String damagePopupStyle,
                      int displayDamage, int targetEntityId,
                      HitStopComposer.HitStopPlan hitStop) {
            this(critical, weakness, kill, cameraShake, timeScale, damagePopupStyle,
                    displayDamage, targetEntityId, hitStop,
                    shakeIntensity(cameraShake), fovImpact(cameraShake, kill, critical));
        }

        private static float shakeIntensity(int shake) {
            return switch (Math.max(0, Math.min(3, shake))) {
                case 1 -> 0.35f;
                case 2 -> 0.65f;
                case 3 -> 0.95f;
                default -> 0f;
            };
        }

        private static float fovImpact(int shake, boolean kill, boolean critical) {
            if (kill) {
                return 8.0f;
            }
            if (critical) {
                return 4.5f;
            }
            if (shake >= 2) {
                return 2.5f;
            }
            if (shake >= 1) {
                return 1.2f;
            }
            return 0f;
        }
    }

    /**
     * @param hpChange     协议 HP 变化（负数为伤害）
     * @param targetDead   结算后目标是否死亡
     * @param skillId      技能 ID（大招启发式）
     * @param brokenTarget 目标是否处于击破态（视为克制加强）
     */
    public static FxHint compose(int hpChange, boolean targetDead, int skillId,
                                 boolean brokenTarget, int targetEntityId) {
        int damage = Math.max(0, -hpChange);
        boolean critical = damage >= 800 || (skillId > 0 && skillId % 7 == 0 && damage >= 200);
        boolean weakness = brokenTarget && damage > 0;
        boolean kill = targetDead && damage > 0;

        int shake = 0;
        if (kill) {
            shake = 3;
        } else if (critical) {
            shake = 2;
        } else if (damage >= 150) {
            shake = 1;
        }

        // 技能等级启发式：大招额外抬升震屏与 FOV
        if (skillId >= 3000) {
            shake = Math.min(3, shake + 1);
        }

        float timeScale = 1.0f;
        if (kill) {
            timeScale = 0.35f;
        } else if (critical) {
            timeScale = 0.55f;
        } else if (skillId >= 1000) {
            timeScale = 0.7f;
        }

        String style = "normal";
        if (kill) {
            style = "crit_burst";
        } else if (critical) {
            style = "large";
        } else if (damage < 50) {
            style = "small";
        }

        HitStopComposer.HitStopPlan hitStop = HitStopComposer.compose(skillId, critical, kill, damage);
        return new FxHint(critical, weakness, kill, shake, timeScale, style, damage, targetEntityId, hitStop);
    }
}
