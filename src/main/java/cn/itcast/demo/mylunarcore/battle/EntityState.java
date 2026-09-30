package cn.itcast.demo.mylunarcore.battle;

import java.util.HashMap;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * 战斗中任意实体的运行时状态：HP、韧性击破、死亡标记及 Buff 叠层。
 */
public class EntityState {

    private final int id;
    private int hp;
    private boolean dead;
    private int toughness;
    private int maxToughness;
    private boolean broken;
    /** 外观皮肤 id（仅表现，不影响战斗数值）；0 表示默认。 */
    private int skinId;
    private final Map<Integer, Integer> buffStacks = new HashMap<>();
    /** 增量属性表：基础 + Buff 缓存。 */
    private CombatAttributeSheet attributeSheet;

    public EntityState(int id, int hp, boolean dead) {
        this.id = id;
        this.hp = hp;
        this.dead = dead;
        this.maxToughness = Math.max(0, hp / 5);
        this.toughness = this.maxToughness;
        this.broken = this.maxToughness <= 0;
        this.skinId = 0;
        // 默认以当前 HP 推导占位 atk/def，后续可由开战面板覆盖
        this.attributeSheet = new CombatAttributeSheet(
                new CombatAttributeSheet.Snapshot(hp, Math.max(50, hp / 10), Math.max(30, hp / 20), 100, 5.0, 50.0));
    }

    public int getId() {
        return id;
    }

    public int getSkinId() {
        return skinId;
    }

    public void setSkinId(int skinId) {
        this.skinId = Math.max(0, skinId);
    }

    public int getHp() {
        return hp;
    }

    public void setHp(int hp) {
        this.hp = hp;
    }

    public boolean isDead() {
        return dead;
    }

    public void setDead(boolean dead) {
        this.dead = dead;
    }

    public int getToughness() {
        return toughness;
    }

    public int getMaxToughness() {
        return maxToughness;
    }

    public boolean isBroken() {
        return broken;
    }

    public void setToughness(int toughness, int maxToughness) {
        this.maxToughness = Math.max(0, maxToughness);
        this.toughness = Math.min(this.maxToughness, Math.max(0, toughness));
        this.broken = this.maxToughness > 0 && this.toughness <= 0;
    }

    /**
     * 削减韧性；归零时进入击破态。
     *
     * @return 是否本次触发击破
     */
    public boolean reduceToughness(int amount) {
        if (broken || amount <= 0 || maxToughness <= 0) {
            return false;
        }
        toughness = Math.max(0, toughness - amount);
        if (toughness <= 0) {
            broken = true;
            return true;
        }
        return false;
    }

    public Map<Integer, Integer> getBuffStacks() {
        return buffStacks;
    }

    public void addBuffStack(int buffId, int add, int maxStack) {
        addBuffStack(buffId, add, maxStack, null);
    }

    /**
     * 叠 Buff 并增量刷新属性；{@code perStackLookup} 为空时按零影响处理。
     */
    public void addBuffStack(int buffId, int add, int maxStack,
                             IntFunction<CombatAttributeSheet.BuffDelta> perStackLookup) {
        int old = buffStacks.getOrDefault(buffId, 0);
        int next = Math.min(maxStack, old + add);
        if (next <= 0) {
            buffStacks.remove(buffId);
        } else {
            buffStacks.put(buffId, next);
        }
        CombatAttributeSheet.BuffDelta perStack = perStackLookup == null
                ? CombatAttributeSheet.BuffDelta.zero()
                : perStackLookup.apply(buffId);
        ensureSheet().onBuffStacksChanged(buffId, Math.max(0, next), perStack);
    }

    public CombatAttributeSheet ensureSheet() {
        if (attributeSheet == null) {
            attributeSheet = new CombatAttributeSheet(
                    new CombatAttributeSheet.Snapshot(hp, Math.max(50, hp / 10), Math.max(30, hp / 20), 100, 5.0, 50.0));
        }
        return attributeSheet;
    }

    public CombatAttributeSheet getAttributeSheet() {
        return ensureSheet();
    }

    public void setAttributeSheet(CombatAttributeSheet sheet) {
        this.attributeSheet = sheet == null
                ? new CombatAttributeSheet(new CombatAttributeSheet.Snapshot(hp, 100, 50, 100, 5.0, 50.0))
                : sheet;
    }

    public CombatAttributeSheet.Snapshot resolveAttributes() {
        return ensureSheet().resolve();
    }
}
