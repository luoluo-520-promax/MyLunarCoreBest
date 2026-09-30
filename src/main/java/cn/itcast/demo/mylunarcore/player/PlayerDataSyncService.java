// 业务模块数据变更后触发异步刷新与客户端推送的门面服务
package cn.itcast.demo.mylunarcore.player;

import org.springframework.stereotype.Service;

/**
 * 玩家数据变更同步门面。
 * <p>
 * 优先走 {@link PlayerAggregateService#syncAfterExternalPersist}（内存 merge + 推送），
 * 避免高频路径默认 ALL 全量 reload。遗留调用仍可走 {@link #notifyDataChanged(long, DataChangeScope)}。
 */
@Service
public class PlayerDataSyncService {

    private final GameSessionManager sessionManager;
    private final PlayerDataAsyncLoadService playerDataAsyncLoadService;
    private final PlayerAggregateService playerAggregateService;

    public PlayerDataSyncService(GameSessionManager sessionManager,
                                 PlayerDataAsyncLoadService playerDataAsyncLoadService,
                                 PlayerAggregateService playerAggregateService) {
        this.sessionManager = sessionManager;
        this.playerDataAsyncLoadService = playerDataAsyncLoadService;
        this.playerAggregateService = playerAggregateService;
    }

    /**
     * @deprecated 请传入精确 {@link DataChangeScope}，避免 ALL 放大 SQL。
     */
    @Deprecated
    public void notifyDataChanged(long uid) {
        notifyDataChanged(uid, DataChangeScope.ALL);
    }

    /**
     * 外部已写库后的在线同步：精确 scope 时走聚合 merge；ALL 时回退异步全量 reload。
     */
    public void notifyDataChanged(long uid, DataChangeScope scope) {
        if (sessionManager.getOrNull(uid) == null) {
            return;
        }
        if (scope == null || scope == DataChangeScope.ALL) {
            playerDataAsyncLoadService.reloadAsync(uid, SyncReason.DATA_CHANGE, DataChangeScope.ALL);
            return;
        }
        playerAggregateService.syncAfterExternalPersist(uid, scope);
    }

    /**
     * 多 scope 合并同步（一次锁内 merge，避免重复推送）。
     */
    public void notifyDataChanged(long uid, DataChangeScope first, DataChangeScope... rest) {
        if (sessionManager.getOrNull(uid) == null) {
            return;
        }
        DataChangeScope[] scopes = new DataChangeScope[1 + (rest == null ? 0 : rest.length)];
        scopes[0] = first;
        if (rest != null) {
            System.arraycopy(rest, 0, scopes, 1, rest.length);
        }
        playerAggregateService.syncAfterExternalPersist(uid, scopes);
    }
}
