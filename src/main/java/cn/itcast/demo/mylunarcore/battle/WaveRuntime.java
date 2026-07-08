// 战斗波次运行时所在包
package cn.itcast.demo.mylunarcore.battle;

// 不可变空列表工厂
import java.util.Collections;
// 列表接口
import java.util.List;

/**
 * 单个波次的运行时数据：保存波次序号及该波怪物列表快照。
 */
public class WaveRuntime {

    private final int waveOrder; // 波次编号（第几波）
    private final List<MonsterRuntime> monsters; // 该波怪物运行时列表（永不为 null）

    /**
     * 创建波次运行时快照。
     */
    public WaveRuntime(int waveOrder, List<MonsterRuntime> monsters) { // 构造波次运行时
        this.waveOrder = waveOrder; // 保存波次顺序编号
        this.monsters = monsters == null ? Collections.<MonsterRuntime>emptyList() : monsters; // null 时降级为空列表
    }

    /** 返回波次顺序编号。 */
    public int getWaveOrder() { // 获取波次编号
        return waveOrder; // 返回 waveOrder 字段
    }

    /** 返回该波怪物列表。 */
    public List<MonsterRuntime> getMonsters() { // 获取怪物列表
        return monsters; // 返回不可变列表引用
    }
}
