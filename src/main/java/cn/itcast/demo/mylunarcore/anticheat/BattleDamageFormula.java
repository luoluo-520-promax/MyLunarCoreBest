package cn.itcast.demo.mylunarcore.anticheat;

/**
 * 回合制伤害确定性公式：服务端根据攻防属性重算，不信任客户端申报伤害。
 */
public final class BattleDamageFormula {

    private BattleDamageFormula() {
    }

    /**
     * @param attackerAtk 攻击方攻击
     * @param defenderDef 防御方防御
     * @param skillPower  技能倍率（百分数，100=1.0x）
     * @param broken      目标是否处于韧性击破
     */
    public static int compute(int attackerAtk, int defenderDef, int skillPower, boolean broken) {
        int atk = Math.max(0, attackerAtk);
        int def = Math.max(0, defenderDef);
        int power = Math.max(1, skillPower);
        long raw = (long) atk * power;
        long mitigated = raw * 100L / (100L + def);
        if (broken) {
            mitigated = mitigated * 130L / 100L;
        }
        return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, mitigated / 100L));
    }
}
