// 在线玩家 Tick 目标注册表：供 {@link cn.itcast.demo.mylunarcore.common.GameServer} 每帧遍历
package cn.itcast.demo.mylunarcore.common;

// Spring 组件注解
// Spring 组件注解
import org.springframework.stereotype.Component;

// 并发哈希表：uid -> OnlinePlayer
// 并发哈希表：uid -> OnlinePlayer
import java.util.concurrent.ConcurrentHashMap;

/**

 * 为当前已登录玩家注册 {@link OnlinePlayer}，随会话生命周期增减；游戏主循环仅遍历快照。

 */

@Component // 注册为 Spring Bean

public class PlayerTickRegistry {

    private final ConcurrentHashMap<Long, OnlinePlayer> onlinePlayers = new ConcurrentHashMap<>(); // uid -> 可 Tick 对象

    /**

     * @param player 在线玩家 Tick 目标

     */

    public void register(OnlinePlayer player) {

        onlinePlayers.put(player.getUid(), player); // 覆盖同 uid（理论上会话层先处理顶号）

    }

    /**

     * @param uid 玩家 uid

     */

    public void unregister(long uid) {

        onlinePlayers.remove(uid); // 登出或会话移除时调用

    }

    /**

     * @return 当前全部在线玩家的可迭代快照（基于 values 视图）

     */

    public Iterable<OnlinePlayer> snapshotOnlinePlayers() {

        return onlinePlayers.values(); // 返回结果

    }

}

