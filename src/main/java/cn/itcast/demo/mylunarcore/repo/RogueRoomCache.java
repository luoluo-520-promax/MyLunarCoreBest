// 模拟宇宙房间 L1 进程内缓存所在包
package cn.itcast.demo.mylunarcore.repo;

// 房间领域模型
import cn.itcast.demo.mylunarcore.model.RogueRoomData;
// Spring 组件（非 @Repository，表示缓存而非直接持久化）
import org.springframework.stereotype.Component;

// 线程安全并发 HashMap
import java.util.concurrent.ConcurrentHashMap;

/**
 * L1：按房间 id 缓存已从 L2 加载的 {@link RogueRoomData}，实现 O(1) 命中与按需加载。
 */
@Component // 注册为 Spring Bean，可被 Service 注入
public class RogueRoomCache {

    // L2 数据库仓储，未命中 L1 时回源查询
    private final RogueRoomDataRepository repository;
    // roomId → 已加载的房间数据（ConcurrentHashMap 支持并发读写的 computeIfAbsent）
    private final ConcurrentHashMap<Integer, RogueRoomData> byRoomId = new ConcurrentHashMap<>();

    /**
     * 构造器注入 L2 仓储。
     *
     * @param repository RogueRoomDataRepository 实例
     */
    public RogueRoomCache(RogueRoomDataRepository repository) {
        this.repository = repository; // 保存 L2 依赖
    }

    /**
     * 先查 L1，未命中则查 MySQL（L2），仍无则使用与旧逻辑一致的默认房间数据并写入 L1。
     *
     * @param roomId 房间 id
     * @return 房间配置（永不为 null，缺省时有默认值）
     */
    public RogueRoomData getOrLoad(int roomId) {
        // computeIfAbsent：原子地「不存在则 loadOrDefault 并放入 Map」
        return byRoomId.computeIfAbsent(roomId, this::loadOrDefault);
    }

    /**
     * 热更新或表变更后使单房间缓存失效，下次 getOrLoad 将重新读库。
     *
     * @param roomId 房间 id
     */
    public void invalidate(int roomId) {
        byRoomId.remove(roomId); // 从 L1 移除，强制下次回源
    }

    /**
     * L1 未命中时的加载逻辑：查库，无行则生成默认房间。
     *
     * @param roomId 房间 id（computeIfAbsent 传入的 key）
     * @return 数据库行或默认房间，永非 null
     */
    private RogueRoomData loadOrDefault(int roomId) {
        RogueRoomData fromDb = repository.findByRoomId(roomId); // 访问 L2
        if (fromDb != null) {
            return fromDb; // 库中有配置则直接使用
        }
        return defaultRoom(roomId); // 配置缺失时的兜底，与历史硬编码行为一致
    }

    /**
     * 与历史逻辑一致的默认房间（类型 1=普通，坐标原点 0,0）。
     *
     * @param roomId 房间 id
     * @return  synthetic 默认 RogueRoomData
     */
    private static RogueRoomData defaultRoom(int roomId) {
        return new RogueRoomData(roomId, 1, 0, 0); // roomType=1, pos=(0,0)
    }
}
