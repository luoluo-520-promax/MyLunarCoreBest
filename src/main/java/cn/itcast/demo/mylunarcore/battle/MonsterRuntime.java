// 战斗怪物运行时所在包
package cn.itcast.demo.mylunarcore.battle;

/**
 * 单个怪物的运行时状态：配置 ID、等级、最大/当前 HP，供战斗流程读写。
 */
public class MonsterRuntime {

    private final int configMonsterId; // 配置表怪物 ID
    private final int level; // 怪物等级
    private final int maxHp; // 最大生命值
    private int hp; // 当前生命值（战斗中可变）

    /**
     * 创建满血怪物运行时实例。
     */
    public MonsterRuntime(int configMonsterId, int level, int maxHp) { // 构造怪物运行时
        this.configMonsterId = configMonsterId; // 保存配置怪物 ID
        this.level = level; // 保存等级
        this.maxHp = maxHp; // 保存最大 HP
        this.hp = maxHp; // 初始化为满血
    }

    /** 返回配置怪物 ID。 */
    public int getConfigMonsterId() { // 获取配置 ID
        return configMonsterId; // 返回 configMonsterId
    }

    /** 返回怪物等级。 */
    public int getLevel() { // 获取等级
        return level; // 返回 level
    }

    /** 返回最大生命值。 */
    public int getMaxHp() { // 获取最大 HP
        return maxHp; // 返回 maxHp
    }

    /** 返回当前生命值。 */
    public int getHp() { // 获取当前 HP
        return hp; // 返回 hp
    }

    /** 更新当前生命值（受击/治疗时调用）。 */
    public void setHp(int hp) { // 设置当前 HP
        this.hp = hp; // 写入新的当前血量
    }
}
