package cn.itcast.demo.mylunarcore.battle.assist;

import cn.itcast.demo.mylunarcore.battle.BattleContext;
import cn.itcast.demo.mylunarcore.battle.EntityState;
import cn.itcast.demo.mylunarcore.battle.MonsterRuntime;
import cn.itcast.demo.mylunarcore.battle.WaveRuntime;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 将战局运行时状态序列化为 Prompt/气泡可用的战斗状态向量摘要。
 */
public final class BattleStateVectorSerializer {

    /** 约定：狂暴预警 buff */
    public static final int BUFF_ENRAGE_WARN = 9001;
    /** 约定：能量溢出标记 */
    public static final int BUFF_ENERGY_OVERFLOW = 9002;

    private BattleStateVectorSerializer() {
    }

    public record BattleStateVector(String summary, boolean enrageImminent, boolean energyOverflow,
                                    boolean lowHpExecute, int teamSkillPoints, int ultEnergyPercent) {
    }

    public static BattleStateVector serialize(BattleContext context) {
        if (context == null || context.isEnded()) {
            return new BattleStateVector("", false, false, false, 0, 0);
        }
        int skillPts = context.getTeamSkillPoints();
        int ultPct = context.getUltEnergyPercent();
        boolean enrage = false;
        boolean overflow = false;
        boolean execute = false;
        List<String> parts = new ArrayList<>();
        parts.add("turn=" + context.getTurn());
        parts.add("wave=" + context.getCurrentWave());
        parts.add("skillPts=" + skillPts);
        parts.add("ultPct=" + ultPct + "%");
        parts.add("autoStrategy=" + context.getAutoStrategy());
        parts.add("targetFocus=" + context.getTargetFocus());

        EntityState player = context.getEntity(context.getPlayerId());
        if (player != null && !player.isDead()) {
            parts.add("playerHp=" + player.getHp());
            overflow = player.getBuffStacks().containsKey(BUFF_ENERGY_OVERFLOW);
            if (overflow) {
                parts.add("playerEnergy=overflow");
            }
        }

        for (Integer mid : context.listAliveMonsterIdsInCurrentWave()) {
            EntityState m = context.getEntity(mid);
            if (m == null || m.isDead()) {
                continue;
            }
            MonsterRuntime rt = findMonster(context, mid);
            int maxHp = rt == null ? Math.max(1, m.getHp()) : Math.max(1, rt.getMaxHp());
            double hpRatio = m.getHp() * 1.0 / maxHp;
            Map<Integer, Integer> buffs = m.getBuffStacks();
            if (buffs.containsKey(BUFF_ENRAGE_WARN) || hpRatio <= 0.25) {
                enrage = true;
                parts.add("bossEnrageWarn=" + mid);
            }
            if (hpRatio <= 0.15) {
                execute = true;
                parts.add("executeWindow=" + mid);
            }
            if (!buffs.isEmpty()) {
                parts.add("monster" + mid + "Buffs=" + buffs.size());
            }
            if (m.isBroken()) {
                parts.add("broken=" + mid);
            }
        }

        String summary = String.join(",", parts);
        return new BattleStateVector(summary, enrage, overflow, execute, skillPts, ultPct);
    }

    public static String toPromptBlock(BattleStateVector v) {
        if (v == null || v.summary().isBlank()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("【战斗状态向量】").append(v.summary());
        if (v.enrageImminent()) {
            sb.append("；BOSS 即将狂暴，建议留战技点给盾奶");
        }
        if (v.energyOverflow()) {
            sb.append("；我方能量溢出，优先释放终结技");
        }
        if (v.lowHpExecute()) {
            sb.append("；存在丝血目标，建议普攻补刀节省战技点");
        }
        return sb.toString();
    }

    private static MonsterRuntime findMonster(BattleContext context, int monsterId) {
        List<WaveRuntime> waves = context.getWaves();
        if (waves == null) {
            return null;
        }
        for (WaveRuntime wave : waves) {
            if (wave == null || wave.getMonsters() == null) {
                continue;
            }
            for (MonsterRuntime monster : wave.getMonsters()) {
                if (monster != null
                        && (monster.getRuntimeEntityId() == monsterId
                        || monster.getConfigMonsterId() == monsterId)) {
                    return monster;
                }
            }
        }
        return null;
    }

    public static void tickEconomy(BattleContext context) {
        if (context == null || context.isEnded()) {
            return;
        }
        int ult = Math.min(100, context.getUltEnergyPercent() + 8);
        context.setUltEnergyPercent(ult);
        EntityState player = context.getEntity(context.getPlayerId());
        if (player != null && ult >= 95) {
            player.addBuffStack(BUFF_ENERGY_OVERFLOW, 1, 1);
        }
        for (Integer mid : context.listAliveMonsterIdsInCurrentWave()) {
            EntityState m = context.getEntity(mid);
            MonsterRuntime rt = findMonster(context, mid);
            if (m == null || rt == null) {
                continue;
            }
            int maxHp = Math.max(1, rt.getMaxHp());
            if (m.getHp() * 1.0 / maxHp <= 0.30) {
                m.addBuffStack(BUFF_ENRAGE_WARN, 1, 1);
            }
        }
    }

    public static void onSkillUsed(BattleContext context, int skillId) {
        if (context == null) {
            return;
        }
        if (skillId >= 2) {
            context.adjustTeamSkillPoints(-1);
        }
        if (skillId >= 3) {
            context.setUltEnergyPercent(0);
            EntityState player = context.getEntity(context.getPlayerId());
            if (player != null) {
                player.getBuffStacks().remove(BUFF_ENERGY_OVERFLOW);
            }
        } else if (skillId == 1) {
            context.adjustTeamSkillPoints(0);
        }
    }
}
