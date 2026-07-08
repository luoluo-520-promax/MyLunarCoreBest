// 战斗运行时管理所在包
package cn.itcast.demo.mylunarcore.battle;

// Spring 组件扫描注册
import org.springframework.stereotype.Component;

// 线程安全哈希映射
import java.util.concurrent.ConcurrentHashMap;

/**
 * 战局管理器：维护当前进行中战斗的内存索引，供协议处理与结算按 battleId 查找 {@link BattleContext}。
 */
@Component // Spring Bean：战局运行时注册表
public class BattleManager {

    // battleId → 战局完整运行时上下文
    private final ConcurrentHashMap<Long, BattleContext> battles = new ConcurrentHashMap<>(); // 并发安全的进行中战局缓存

    /**
     * 按战斗 ID 获取当前战局；不存在返回 null。
     */
    public BattleContext get(long battleId) { // 按 battleId 查询战局
        return battles.get(battleId); // 不存在则返回 null
    }

    /**
     * 注册一场新战斗到内存索引。
     */
    public void put(BattleContext context) { // 注册新战局
        battles.put(context.getBattleId(), context); // 以 battleId 为键存入
    }

    /**
     * 结束并移除一场战斗，释放内存。
     */
    public void remove(long battleId) { // 移除已结束战局
        battles.remove(battleId); // 从索引中删除
    }
}
