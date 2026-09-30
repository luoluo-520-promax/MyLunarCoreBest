package cn.itcast.demo.mylunarcore.battle;

import java.util.HashMap;
import java.util.Map;

/**
 * 战斗内增量属性表：基础面板 + Buff 加成缓存。
 * <p>
 * 仅在 Buff 叠层变化时增量更新，避免每行动全量遍历增益。
 */
public class CombatAttributeSheet {

    public record Snapshot(int hp, int atk, int def, int spd, double critRate, double critDmg) {
    }

    /** 单条 Buff 对属性的最终影响（已乘叠层）。 */
    public record BuffDelta(int atkFlat, int defFlat, int spdFlat, double atkPct, double defPct,
                            double critRate, double critDmg) {
        public static BuffDelta zero() {
            return new BuffDelta(0, 0, 0, 0, 0, 0, 0);
        }

        public BuffDelta scaled(int stacks) {
            int s = Math.max(0, stacks);
            return new BuffDelta(atkFlat * s, defFlat * s, spdFlat * s,
                    atkPct * s, defPct * s, critRate * s, critDmg * s);
        }

        public BuffDelta plus(BuffDelta o) {
            if (o == null) {
                return this;
            }
            return new BuffDelta(atkFlat + o.atkFlat, defFlat + o.defFlat, spdFlat + o.spdFlat,
                    atkPct + o.atkPct, defPct + o.defPct, critRate + o.critRate, critDmg + o.critDmg);
        }

        public BuffDelta minus(BuffDelta o) {
            if (o == null) {
                return this;
            }
            return new BuffDelta(atkFlat - o.atkFlat, defFlat - o.defFlat, spdFlat - o.spdFlat,
                    atkPct - o.atkPct, defPct - o.defPct, critRate - o.critRate, critDmg - o.critDmg);
        }
    }

    private Snapshot base;
    private BuffDelta buffSum = BuffDelta.zero();
    private final Map<Integer, BuffDelta> perBuffApplied = new HashMap<>();
    private volatile Snapshot cached;
    private volatile boolean dirty = true;

    public CombatAttributeSheet(Snapshot base) {
        this.base = base == null ? new Snapshot(0, 0, 0, 0, 5.0, 50.0) : base;
        this.cached = this.base;
    }

    public void replaceBase(Snapshot base) {
        this.base = base == null ? new Snapshot(0, 0, 0, 0, 5.0, 50.0) : base;
        dirty = true;
    }

    /**
     * Buff 叠层变化时调用：按新叠层重算该 Buff 贡献并增量合并。
     */
    public void onBuffStacksChanged(int buffId, int stacks, BuffDelta perStack) {
        BuffDelta template = perStack == null ? BuffDelta.zero() : perStack;
        BuffDelta next = stacks <= 0 ? BuffDelta.zero() : template.scaled(stacks);
        BuffDelta prev = perBuffApplied.getOrDefault(buffId, BuffDelta.zero());
        buffSum = buffSum.minus(prev).plus(next);
        if (stacks <= 0) {
            perBuffApplied.remove(buffId);
        } else {
            perBuffApplied.put(buffId, next);
        }
        dirty = true;
    }

    public Snapshot resolve() {
        if (!dirty && cached != null) {
            return cached;
        }
        int atk = (int) Math.round((base.atk() + buffSum.atkFlat()) * (1.0 + buffSum.atkPct() / 100.0));
        int def = (int) Math.round((base.def() + buffSum.defFlat()) * (1.0 + buffSum.defPct() / 100.0));
        int spd = base.spd() + buffSum.spdFlat();
        Snapshot snap = new Snapshot(base.hp(), Math.max(0, atk), Math.max(0, def), Math.max(0, spd),
                base.critRate() + buffSum.critRate(), base.critDmg() + buffSum.critDmg());
        cached = snap;
        dirty = false;
        return snap;
    }

    public boolean isDirty() {
        return dirty;
    }

    public Map<Integer, BuffDelta> snapshotBuffs() {
        return Map.copyOf(perBuffApplied);
    }
}
