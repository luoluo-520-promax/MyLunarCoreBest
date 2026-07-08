// 数据变更后触发异步刷新与会话推送的门面服务
package cn.itcast.demo.mylunarcore.player;

// 会话管理器：判断玩家是否在线
import cn.itcast.demo.mylunarcore.player.GameSessionManager;

// 声明为 Spring 服务层组件
import org.springframework.stereotype.Service;

/**
 * 玩家数据变更同步门面。
 * <p>
 * 业务模块（战斗、商店等）在持久化玩家数据变更后调用 {@link #notifyDataChanged(long)}，
 * 触发重新加载 L2 内存数据并经由 {@link GameSession} 以 {@link SyncReason#DATA_CHANGE} 推送给客户端。
 * </p>
 */
@Service // 注册为 Spring Bean，供各业务模块注入调用
public class PlayerDataSyncService {

    /** 会话管理器，用于判断 uid 是否仍在线 */
    private final GameSessionManager sessionManager;

    /** 异步全量加载服务，负责后台 reload 并推送 */
    private final PlayerDataAsyncLoadService playerDataAsyncLoadService;

    /**
     * Spring 构造注入。
     *
     * @param sessionManager               全局会话管理器
     * @param playerDataAsyncLoadService   异步加载与推送服务
     */
    public PlayerDataSyncService(GameSessionManager sessionManager,
                                 PlayerDataAsyncLoadService playerDataAsyncLoadService) {
        this.sessionManager = sessionManager; // 保存在线状态查询能力
        this.playerDataAsyncLoadService = playerDataAsyncLoadService; // 保存异步 reload 能力
    }

    /**
     * 任意模块持久化变更后可调用：若玩家在线则异步刷新会话内 {@link cn.itcast.demo.mylunarcore.model.PlayerData}
     * 并推送 {@link SyncReason#DATA_CHANGE} 统一同步包。
     *
     * @param uid 发生数据变更的玩家 uid
     */
    public void notifyDataChanged(long uid) {
        if (sessionManager.getOrNull(uid) == null) { // 玩家已离线，无需推送（数据已在 DB 更新）
            return;
        }
        // 投递异步全量加载任务，完成后以 DATA_CHANGE 原因推送
        playerDataAsyncLoadService.reloadFullAsync(uid, SyncReason.DATA_CHANGE);
    }
}
