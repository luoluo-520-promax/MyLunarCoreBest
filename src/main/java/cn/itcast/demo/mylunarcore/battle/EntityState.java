// 战斗实体运行时状态所在包
package cn.itcast.demo.mylunarcore.battle;

// 可变哈希映射
import java.util.HashMap;
// 映射接口
import java.util.Map;

/**
 * 战斗中任意实体的运行时状态：当前 HP、死亡标记及 Buff 叠层表。
 */
public class EntityState {

    private final int id; // 实体 ID（玩家或怪物配置 ID）
    private int hp; // 当前生命值
    private boolean dead; // 是否已死亡

    // Buff 叠层表：buffId → 当前层数
    private final Map<Integer, Integer> buffStacks = new HashMap<>(); // 记录实体身上的 Buff 层数

    /**
     * 创建实体运行时状态。
     */
    public EntityState(int id, int hp, boolean dead) { // 构造实体状态
        this.id = id; // 保存实体唯一标识
        this.hp = hp; // 初始化当前血量
        this.dead = dead; // 初始化死亡状态
    }

    /** 返回实体 ID。 */
    public int getId() { // 获取实体 ID
        return id; // 返回 id 字段
    }

    /** 返回当前生命值。 */
    public int getHp() { // 获取当前 HP
        return hp; // 返回 hp 字段
    }

    /** 更新当前生命值。 */
    public void setHp(int hp) { // 设置当前 HP
        this.hp = hp; // 写入新血量
    }

    /** 返回是否已死亡。 */
    public boolean isDead() { // 获取死亡状态
        return dead; // 返回 dead 字段
    }

    /** 设置死亡状态。 */
    public void setDead(boolean dead) { // 设置死亡标记
        this.dead = dead; // 写入死亡状态
    }

    /** 返回 Buff 叠层映射（可修改）。 */
    public Map<Integer, Integer> getBuffStacks() { // 获取 Buff 叠层表
        return buffStacks; // 返回 buffStacks 引用
    }

    /**
     * 调整某个 Buff 的层数：正数叠加、负数减少，不超过 maxStack；层数≤0 时移除。
     */
    public void addBuffStack(int buffId, int add, int maxStack) { // 增减 Buff 层数
        int old = buffStacks.getOrDefault(buffId, 0); // 读取当前层数，不存在则为 0
        int next = Math.min(maxStack, old + add); // 计算新层数并限制上限
        if (next <= 0) { // 层数耗尽
            buffStacks.remove(buffId); // 从表中移除该 Buff
        } else { // 仍有有效层数
            buffStacks.put(buffId, next); // 写入新层数
        }
    }
}
