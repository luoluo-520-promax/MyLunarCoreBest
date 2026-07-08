package cn.itcast.demo.mylunarcore.demo;

import cn.itcast.demo.mylunarcore.battle.BattleContext;
import cn.itcast.demo.mylunarcore.battle.EntityState;
import cn.itcast.demo.mylunarcore.battle.MonsterRuntime;
import cn.itcast.demo.mylunarcore.battle.WaveRuntime;

import java.util.Map;

/** 命令行演示的结构化输出工具。 */
final class FlowDemoPrinter {

    private int stepNo;

    void resetSteps() {
        stepNo = 0;
    }

    void section(String title) {
        System.out.println();
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
        System.out.println(title);
        System.out.println("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
    }

    void step(String message) {
        stepNo++;
        System.out.printf("  [%02d] %s%n", stepNo, message);
    }

    void detail(String message) {
        System.out.println("       · " + message);
    }

    void battleSnapshot(BattleContext context, String label) {
        detail(label + " | 回合=" + context.getTurn()
                + " 波次=" + context.getCurrentWave() + "/" + context.getWaveCount()
                + " 已结束=" + context.isEnded());
        EntityState player = context.getEntity(context.getPlayerId());
        if (player != null) {
            detail("玩家[" + player.getId() + "] HP=" + player.getHp()
                    + " 死亡=" + player.isDead() + formatBuffs(player));
        }
        for (Map.Entry<Integer, EntityState> e : context.getEntities().entrySet()) {
            if (e.getKey() == context.getPlayerId()) {
                continue;
            }
            EntityState entity = e.getValue();
            detail("实体[" + entity.getId() + "] HP=" + entity.getHp()
                    + " 死亡=" + entity.isDead() + formatBuffs(entity));
        }
    }

    void waveInfo(BattleContext context) {
        for (WaveRuntime wave : context.getWaves()) {
            StringBuilder monsters = new StringBuilder();
            for (MonsterRuntime m : wave.getMonsters()) {
                if (!monsters.isEmpty()) {
                    monsters.append(", ");
                }
                monsters.append(m.getConfigMonsterId())
                        .append("(Lv").append(m.getLevel())
                        .append(" HP=").append(m.getMaxHp()).append(")");
            }
            detail("波次" + wave.getWaveOrder() + ": " + monsters);
        }
    }

    private static String formatBuffs(EntityState entity) {
        if (entity.getBuffStacks().isEmpty()) {
            return "";
        }
        return " Buff=" + entity.getBuffStacks();
    }
}
