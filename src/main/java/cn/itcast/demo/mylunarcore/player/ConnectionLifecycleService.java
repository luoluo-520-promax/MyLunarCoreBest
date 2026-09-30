// 连接断开与显式登出的统一清理服务：落盘、注销会话、释放锁与缓存
package cn.itcast.demo.mylunarcore.player;

import cn.itcast.demo.mylunarcore.common.AppLogger;
import cn.itcast.demo.mylunarcore.common.LogCategory;
import cn.itcast.demo.mylunarcore.repo.PlayerDataRepository;
import cn.itcast.demo.mylunarcore.social.FriendOnlineStatusService;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 连接生命周期服务。
 * <p>
 * TCP/KCP 的 channelInactive 与客户端主动 Logout 可能几乎同时触发；
 * {@link #cleanupGuard} 保证同一 uid 的清理流程（落盘 → 写登出时间 → 移除会话 → 释放锁/缓存）
 * 只执行一次，避免重复写库与双删会话。
 */
@Service
public class ConnectionLifecycleService {

    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_SESSION, ConnectionLifecycleService.class);

    /**
     * 清理进行中标记集合。
     * tryMarkCleanup 使用 ConcurrentHashMap.newKeySet().add 的原子性实现互斥。
     */
    private final Set<Long> cleanupGuard = ConcurrentHashMap.newKeySet();

    private final PlayerDataPeriodicPersistenceService periodicPersistenceService;
    private final PlayerDataRepository repository;
    private final GameSessionManager sessionManager;
    private final PlayerSessionLockService sessionLockService;
    private final PlayerDataCacheService cacheService;
    /**
     * 断线/登出前清理场景、战斗、Rogue 运行时，避免幽灵实体
     */
    private final PlaySessionCleanupService playSessionCleanupService;
    private final ObjectProvider<FriendOnlineStatusService> friendOnlineStatusProvider;

    public ConnectionLifecycleService(PlayerDataPeriodicPersistenceService periodicPersistenceService,
                                        PlayerDataRepository repository,
                                        GameSessionManager sessionManager,
                                        PlayerSessionLockService sessionLockService,
                                        PlayerDataCacheService cacheService,
                                        PlaySessionCleanupService playSessionCleanupService) {
        this(periodicPersistenceService, repository, sessionManager, sessionLockService, cacheService,
                playSessionCleanupService, null);
    }

    @Autowired
    public ConnectionLifecycleService(PlayerDataPeriodicPersistenceService periodicPersistenceService,
                                        PlayerDataRepository repository,
                                        GameSessionManager sessionManager,
                                        PlayerSessionLockService sessionLockService,
                                        PlayerDataCacheService cacheService,
                                        PlaySessionCleanupService playSessionCleanupService,
                                        ObjectProvider<FriendOnlineStatusService> friendOnlineStatusProvider) {
        this.periodicPersistenceService = periodicPersistenceService;
        this.repository = repository;
        this.sessionManager = sessionManager;
        this.sessionLockService = sessionLockService;
        this.cacheService = cacheService;
        this.playSessionCleanupService = playSessionCleanupService;
        this.friendOnlineStatusProvider = friendOnlineStatusProvider;
    }

    /**
     * 尝试将 uid 标记为「清理进行中」。
     *
     * @param uid 玩家 uid
     * @return add 成功表示本次获得清理权；false 表示已有并发清理在执行
     */
    public boolean tryMarkCleanup(long uid) {
        return cleanupGuard.add(uid);
    }

    /**
     * 清理流程结束后释放标记，允许该 uid 再次登录后正常走清理路径。
     */
    public void releaseCleanupMark(long uid) {
        cleanupGuard.remove(uid);
    }

    /**
     * Netty channelInactive 回调入口：从 Channel 属性读取 uid 并执行清理。
     */
    public void onDisconnect(Channel channel) {
        if (channel == null) {
            return;
        }
        Long uid = channel.attr(PlayerChannelAttributes.PLAYER_UID).get();
        if (uid == null) {
            return;
        }
        performCleanup(uid, channel, false);
    }

    /**
     * 显式登出入口，与 onDisconnect 共用 performCleanup 与 cleanupGuard。
     *
     * @return 本次是否实际执行了清理（false 表示已有清理在进行）
     */
    public boolean cleanupOnLogout(long uid, Channel channel) {
        return performCleanup(uid, channel, true);
    }

    /**
     * 统一清理编排：先异步 LOGOUT 落盘，再 finalizeDisconnect 清理会话与缓存。
     *
     * @param abortBattle 主动登出时中断战斗；掉线则托管自动战斗
     */
    private boolean performCleanup(long uid, Channel channel, boolean abortBattle) {
        if (!tryMarkCleanup(uid)) {
            return false;
        }
        try {
            periodicPersistenceService.persistAsync(uid, SyncReason.LOGOUT);
            playSessionCleanupService.leaveCurrentPlay(uid, abortBattle);
            finalizeDisconnect(uid, channel);
            return true;
        } catch (Exception e) {
            log.warn("session cleanup failed, uid={}", uid, e);
            return false;
        } finally {
            releaseCleanupMark(uid);
        }
    }

    /**
     * 断连收尾：写 player 表登出时间 → 移除 GameSession 与 token → 释放玩家锁 → 失效 Caffeine 缓存 → 清空 Channel uid 绑定。
     */
    private void finalizeDisconnect(long uid, Channel channel) {
        FriendOnlineStatusService friendOnline = friendOnlineStatusProvider == null
                ? null : friendOnlineStatusProvider.getIfAvailable();
        if (friendOnline != null) {
            try {
                friendOnline.publishOffline((int) uid);
            } catch (Exception e) {
                log.debug("publishOffline skipped, uid={}", uid);
            }
        }
        try {
            Timestamp now = new Timestamp(System.currentTimeMillis());
            repository.updatePlayerLogout(uid, now);
        } catch (Exception e) {
            log.warn("updatePlayerLogout failed, uid={}", uid, e);
        }
        sessionManager.removeSession(uid);
        sessionLockService.release(uid);
        cacheService.invalidate(uid);
        if (channel != null) {
            channel.attr(PlayerChannelAttributes.PLAYER_UID).set(null);
        }
    }
}
