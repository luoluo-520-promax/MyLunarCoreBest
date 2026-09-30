// 场景运行时索引所在包：维护「玩家 uid → 当前 SceneContext」的全局并发映射
package cn.itcast.demo.mylunarcore.scene;

// Spring 管理的单例组件，供 SceneNettyService 等注入查询玩家当前场景
import org.springframework.stereotype.Component;

// 线程安全的 uid → SceneContext 映射，支持多 Netty 工作线程并发 enter/leave
import java.util.concurrent.ConcurrentHashMap;

/**
 * 在线玩家当前 {@link SceneContext} 的并发索引。
 * <p>
 * 与 {@link ZoneManager}（多玩家共享 Zone + AOI）互补：
 * 本类按玩家维度缓存其个人场景快照（怪物/NPC/道具状态与坐标），
 * 进场景时 put、切场景或下线时 remove，供协议处理与 tick 循环快速按 uid 定位场景上下文。
 */
@Component
public class SceneManager {

    /**
     * 玩家 uid → 当前场景上下文。
     * 一个玩家同一时刻只对应一个 SceneContext；切场景时旧上下文被覆盖或先 remove 再 put。
     */
    private final ConcurrentHashMap<Long, SceneContext> scenesByPlayer = new ConcurrentHashMap<>();

    /**
     * 获取玩家当前场景上下文。
     *
     * @param playerUid 玩家 uid
     * @return 场景上下文；玩家未进入任何场景时返回 null
     */
    public SceneContext getByPlayerUid(long playerUid) {
        return scenesByPlayer.get(playerUid);
    }

    /**
     * 玩家进入或切换场景后注册上下文。
     * 由 SceneNettyService 在 enterScene 完成实体加载后调用，覆盖该 uid 之前的场景（若有）。
     *
     * @param playerUid 玩家 uid
     * @param ctx       新构建并已填充怪物/NPC/道具的 SceneContext
     */
    public void put(long playerUid, SceneContext ctx) {
        scenesByPlayer.put(playerUid, ctx);
    }

    /**
     * 玩家离开场景或下线时移除索引条目。
     * 应与 {@link ZoneManager#leaveZone} 配对调用，分别清理个人快照与共享 Zone 数据。
     *
     * @param playerUid 玩家 uid
     */
    public void remove(long playerUid) {
        scenesByPlayer.remove(playerUid);
    }
}
