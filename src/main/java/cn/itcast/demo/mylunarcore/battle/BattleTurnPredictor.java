package cn.itcast.demo.mylunarcore.battle;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 回合结果预计算：空闲时针对若干可能行动预估伤害/击杀，降低实际 Tick 延迟。
 * <p>
 * 回合制场景下简化为「提前计算下一回合」；服务端仍以权威结算为准，预测仅加速路径。
 */
@Component
public class BattleTurnPredictor {

    public record PredictedOutcome(int skillId, int targetId, int estimatedDamage, boolean killLikely) {
    }

    private final Map<Long, List<PredictedOutcome>> cache = new ConcurrentHashMap<>();

    /** 预计算若干候选行动结果（不修改战局）。 */
    public List<PredictedOutcome> precompute(BattleContext ctx, int casterId, List<Integer> skillIds) {
        if (ctx == null || skillIds == null || skillIds.isEmpty()) {
            return List.of();
        }
        List<Integer> targets = ctx.listAliveMonsterIdsInCurrentWave();
        if (targets.isEmpty()) {
            return List.of();
        }
        List<PredictedOutcome> out = new ArrayList<>(skillIds.size() * Math.min(3, targets.size()));
        for (Integer skillId : skillIds) {
            if (skillId == null || skillId <= 0) {
                continue;
            }
            int dmg = skillId >= 3 ? 240 : (skillId >= 2 ? 160 : 80);
            CombatAttributeSheet sheet = ctx.attributeSheetOf(casterId);
            if (sheet != null) {
                CombatAttributeSheet.Snapshot snap = sheet.resolve();
                dmg = Math.max(10, (int) (dmg * (1.0 + snap.atk() / 500.0)));
            }
            int limit = Math.min(3, targets.size());
            for (int i = 0; i < limit; i++) {
                int tid = targets.get(i);
                EntityState e = ctx.getEntity(tid);
                if (e == null || e.isDead()) {
                    continue;
                }
                boolean kill = e.getHp() <= dmg;
                out.add(new PredictedOutcome(skillId, tid, dmg, kill));
            }
        }
        cache.put(ctx.getBattleId(), List.copyOf(out));
        return out;
    }

    public PredictedOutcome lookup(long battleId, int skillId, int targetId) {
        List<PredictedOutcome> list = cache.get(battleId);
        if (list == null) {
            return null;
        }
        for (PredictedOutcome p : list) {
            if (p.skillId() == skillId && p.targetId() == targetId) {
                return p;
            }
        }
        return null;
    }

    public void clear(long battleId) {
        cache.remove(battleId);
    }
}
