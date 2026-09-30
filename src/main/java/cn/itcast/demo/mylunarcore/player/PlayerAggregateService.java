package cn.itcast.demo.mylunarcore.player;

import cn.itcast.demo.mylunarcore.model.PlayerData;
import cn.itcast.demo.mylunarcore.model.PlayerEntity;
import cn.itcast.demo.mylunarcore.repo.PlayerDataRepository;
import org.springframework.stereotype.Service;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 玩家聚合写入口：所有在线玩法变更应经本服务在 uid 锁内修改内存、标记 dirty，
 * 再异步乐观落盘，并从内存推送 DATA_CHANGE（避免先写库再全量 reload）。
 */
@Service
public class PlayerAggregateService {

    private final GameSessionManager sessionManager;
    private final PlayerSessionLockService sessionLockService;
    private final PlayerSyncCoordinator playerSyncCoordinator;
    private final PlayerDataPeriodicPersistenceService persistenceService;
    private final PlayerDataRepository repository;
    private final PlayerDataCacheService cacheService;

    public PlayerAggregateService(GameSessionManager sessionManager,
                                  PlayerSessionLockService sessionLockService,
                                  PlayerSyncCoordinator playerSyncCoordinator,
                                  PlayerDataPeriodicPersistenceService persistenceService,
                                  PlayerDataRepository repository,
                                  PlayerDataCacheService cacheService) {
        this.sessionManager = sessionManager;
        this.sessionLockService = sessionLockService;
        this.playerSyncCoordinator = playerSyncCoordinator;
        this.persistenceService = persistenceService;
        this.repository = repository;
        this.cacheService = cacheService;
    }

    /**
     * 在 uid 锁内修改内存聚合，标记 dirty，推送客户端，并异步落盘。
     *
     * @return mutator 返回值；玩家不在线时返回 null
     */
    public <T> T commit(long uid, Function<PlayerData, T> mutator) {
        Objects.requireNonNull(mutator, "mutator");
        return sessionLockService.withLock(uid, () -> {
            GameSession session = sessionManager.getOrNull(uid);
            if (session == null) {
                return null;
            }
            PlayerData data = session.getPlayerData();
            if (data == null) {
                return null;
            }
            T result = mutator.apply(data);
            session.markDirty();
            cacheService.put(uid, data.deepCopy());
            playerSyncCoordinator.pushToSession(session, data, SyncReason.DATA_CHANGE);
            persistenceService.persistAsync(uid, SyncReason.DATA_CHANGE);
            return result;
        });
    }

    /**
     * 无返回值的 commit 便捷入口。
     */
    public void commit(long uid, Consumer<PlayerData> mutator) {
        commit(uid, data -> {
            mutator.accept(data);
            return Boolean.TRUE;
        });
    }

    /**
     * 外部事务已写库后的在线同步：按 scope 增量 merge 到内存并推送，不走全量 reload。
     * 用于商店/邮件等仍直接写库的过渡路径。
     */
    public void syncAfterExternalPersist(long uid, DataChangeScope... scopes) {
        if (uid <= 0 || scopes == null || scopes.length == 0) {
            return;
        }
        Set<DataChangeScope> scopeSet = EnumSet.noneOf(DataChangeScope.class);
        for (DataChangeScope scope : scopes) {
            if (scope != null) {
                scopeSet.add(scope);
            }
        }
        if (scopeSet.isEmpty()) {
            return;
        }
        sessionLockService.withLock(uid, () -> {
            GameSession session = sessionManager.getOrNull(uid);
            if (session == null) {
                return;
            }
            PlayerData data = session.getPlayerData();
            if (data == null) {
                data = repository.loadAllData(uid);
                session.setPlayerData(data);
            } else {
                for (DataChangeScope scope : scopeSet) {
                    if (scope == DataChangeScope.ALL) {
                        PlayerData loaded = repository.loadAllData(uid);
                        session.setPlayerData(loaded);
                        data = loaded;
                        break;
                    }
                    repository.mergeScope(data, uid, scope);
                }
            }
            PlayerEntity player = data.getPlayer();
            if (player != null) {
                session.bindDataVersion(player.getDataVersion());
            }
            session.clearDirty();
            cacheService.put(uid, data.deepCopy());
            playerSyncCoordinator.pushToSession(session, data, SyncReason.DATA_CHANGE);
        });
    }

    /**
     * 登录或全量加载完成后，将会话版本与 DB 对齐。
     */
    public void bindLoadedVersion(long uid, PlayerData data) {
        if (uid <= 0 || data == null || data.getPlayer() == null) {
            return;
        }
        GameSession session = sessionManager.getOrNull(uid);
        if (session == null) {
            return;
        }
        session.bindDataVersion(data.getPlayer().getDataVersion());
        session.clearDirty();
    }
}
