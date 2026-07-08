// 模拟宇宙（Rogue）局内运行时管理所在包
package cn.itcast.demo.mylunarcore.rogue;

// 房间图 L1 缓存：按 roomId 解析邻接与类型
import cn.itcast.demo.mylunarcore.repo.RogueRoomCache;
// Spring 组件：注册为单例 Bean
import org.springframework.stereotype.Component;

// 玩家 id → 运行时上下文
import java.util.Map;
// 线程安全哈希表
import java.util.concurrent.ConcurrentHashMap;

/**
 * 在线 Rogue 局内运行时索引：每玩家至多一条 {@link RogueRuntime}，配合 {@link RogueRoomCache} 解析房间图。
 */
@Component // 由 Spring 扫描注入
public class RogueManager {

    // 当前进行中的 Rogue 局：playerId → 运行时状态
    private final Map<Integer, RogueRuntime> running = new ConcurrentHashMap<>();
    // 房间配置缓存（L1），构造 RogueRuntime 时使用
    private final RogueRoomCache rogueRoomCache;

    /**
     * 构造器注入房间缓存。
     */
    public RogueManager(RogueRoomCache rogueRoomCache) {
        this.rogueRoomCache = rogueRoomCache;
    }

    /**
     * 查询玩家是否有一局进行中的 Rogue。
     *
     * @param playerId 玩家 id
     * @return 运行时对象；未开局则 null
     */
    public RogueRuntime get(int playerId) {
        return running.get(playerId);
    }

    /**
     * 新开或覆盖一局 Rogue（顶号重开）。
     *
     * @param playerId   玩家 id
     * @param rogueId    玩法/赛季配置 id
     * @param difficulty 难度档位
     * @return 新建并已注册的运行时对象
     */
    public RogueRuntime createOrReplace(int playerId, int rogueId, int difficulty) {
        RogueRuntime rt = new RogueRuntime(playerId, rogueId, difficulty, rogueRoomCache);
        running.put(playerId, rt); // 覆盖同玩家旧局
        return rt;
    }

    /**
     * 玩家退出 Rogue 或结算后移除内存局。
     *
     * @param playerId 玩家 id
     */
    public void remove(int playerId) {
        running.remove(playerId);
    }
}
