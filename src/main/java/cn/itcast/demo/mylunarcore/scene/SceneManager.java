// 场景运行时索引所在包
package cn.itcast.demo.mylunarcore.scene;

// Spring 管理的单例组件
import org.springframework.stereotype.Component;

// 线程安全的 uid → SceneContext 映射
import java.util.concurrent.ConcurrentHashMap;

/**
 * 在线玩家当前 {@link SceneContext} 的并发映射：进场景时放入，切场景或下线时移除。
 */
@Component // 注册为 Spring Bean
public class SceneManager {

    // 玩家 uid → 当前场景上下文
    private final ConcurrentHashMap<Long, SceneContext> scenesByPlayer = new ConcurrentHashMap<>();

    /**
     * 获取玩家当前场景上下文。
     *
     * @param playerUid 玩家 uid
     * @return 场景上下文；未进场景则 null
     */
    public SceneContext getByPlayerUid(long playerUid) {
        return scenesByPlayer.get(playerUid);
    }

    /**
     * 玩家进入或切换场景后注册上下文。
     *
     * @param playerUid 玩家 uid
     * @param ctx       新的场景上下文
     */
    public void put(long playerUid, SceneContext ctx) {
        scenesByPlayer.put(playerUid, ctx);
    }

    /**
     * 玩家离开场景或下线时移除。
     *
     * @param playerUid 玩家 uid
     */
    public void remove(long playerUid) {
        scenesByPlayer.remove(playerUid);
    }
}
