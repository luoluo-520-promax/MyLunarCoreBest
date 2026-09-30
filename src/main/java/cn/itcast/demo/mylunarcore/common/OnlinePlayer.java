package cn.itcast.demo.mylunarcore.common;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.scene.SceneContext;
import cn.itcast.demo.mylunarcore.scene.SceneManager;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.player.PlayerDataAsyncLoadService;
import cn.itcast.demo.mylunarcore.player.PlayerDataPeriodicPersistenceService;
import cn.itcast.demo.mylunarcore.player.StaminaService;
import cn.itcast.demo.mylunarcore.player.SyncReason;
import lombok.Getter;
import org.slf4j.Logger;

/**
 * 在线玩家 Tick 单元：挂在全局 Tick 循环上，按 delta 驱动：
 * <ul>
 *   <li>{@link StaminaService#onTick} 体力自然恢复；</li>
 *   <li>被动统一同步 {@link SyncReason#TIMER} 异步重载全量玩家数据；</li>
 *   <li>周期性落库 {@link PlayerDataPeriodicPersistenceService#persistAsync}。</li>
 * </ul>
 * 会话超时仍由心跳路径维护 lastActive；本类在 session 已注销时直接跳过。
 */
public final class OnlinePlayer implements Tickable {

    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_SESSION, OnlinePlayer.class);

    /** 玩家 UID（与 GameSession / Scene 索引一致）。 */
    @Getter
    private final long uid;

    // 查当前连接会话；null 表示已下线
    private final GameSessionManager sessionManager;
    // 取玩家所在场景并转发 scene.onTick
    private final SceneManager sceneManager;
    // 被动同步：定时 reloadFullAsync
    private final PlayerDataAsyncLoadService playerDataAsyncLoadService;
    // 定时把脏数据刷到 DB
    private final PlayerDataPeriodicPersistenceService periodicPersistenceService;
    // sync.passiveIntervalMs / periodicPersistence* 配置
    private final LunarCoreProperties lunarCoreProperties;
    // 可为 null（旧构造）；非空时每 tick 推进体力
    private final StaminaService staminaService;

    // 上次被动同步墙钟毫秒
    private long lastPassiveSyncMillis;
    // 上次周期落库墙钟毫秒
    private long lastPersistMillis;

    /** 兼容无体力服务的构造：staminaService 传 null。 */
    public OnlinePlayer(long uid,
                        GameSessionManager sessionManager,
                        SceneManager sceneManager,
                        PlayerDataAsyncLoadService playerDataAsyncLoadService,
                        PlayerDataPeriodicPersistenceService periodicPersistenceService,
                        LunarCoreProperties lunarCoreProperties) {
        this(uid, sessionManager, sceneManager, playerDataAsyncLoadService,
                periodicPersistenceService, lunarCoreProperties, null);
    }

    public OnlinePlayer(long uid,
                        GameSessionManager sessionManager,
                        SceneManager sceneManager,
                        PlayerDataAsyncLoadService playerDataAsyncLoadService,
                        PlayerDataPeriodicPersistenceService periodicPersistenceService,
                        LunarCoreProperties lunarCoreProperties,
                        StaminaService staminaService) {
        this.uid = uid;
        this.sessionManager = sessionManager;
        this.sceneManager = sceneManager;
        this.playerDataAsyncLoadService = playerDataAsyncLoadService;
        this.periodicPersistenceService = periodicPersistenceService;
        this.lunarCoreProperties = lunarCoreProperties;
        this.staminaService = staminaService;
        // 以构造时刻为基准，避免立刻触发第一次被动同步/落库
        this.lastPassiveSyncMillis = System.currentTimeMillis();
        this.lastPersistMillis = this.lastPassiveSyncMillis;
    }

    /**
     * 全局 Tick 入口：无会话则返回；否则先 tick 玩家态，再把 delta 转给所在场景。
     */
    @Override
    public void onTick(long nowMillis, long deltaMillis) {
        GameSession session = sessionManager.getOrNull(uid);
        if (session == null) {
            return; // 已断开，从 Tick 列表移除前可能仍短暂调用
        }
        tickPlayerState(session, nowMillis, deltaMillis);
        SceneContext scene = sceneManager.getByPlayerUid(uid);
        if (scene != null) {
            scene.onTick(nowMillis, deltaMillis); // AOI/遭遇等场景逻辑
        }
    }

    /**
     * 玩家侧：可选体力 tick → 被动同步 → 周期持久化。
     */
    private void tickPlayerState(GameSession session, long nowMillis, long deltaMillis) {
        if (log.isTraceEnabled()) {
            log.trace("player tick uid={} deltaMs={}", uid, deltaMillis);
        }
        if (staminaService != null) {
            try {
                // uid 在体力服务侧用 int playerId
                staminaService.onTick((int) uid, nowMillis, deltaMillis);
            } catch (Exception e) {
                log.debug("stamina tick skipped uid={}: {}", uid, e.getMessage());
            }
        }
        tickPassiveUnifiedSync(session, nowMillis);
        tickPeriodicPersistence(nowMillis);
    }

    /**
     * 按 sync.passiveIntervalMs 节流，到期则异步全量重载（TIMER 原因），用于纠偏缓存漂移。
     * interval&lt;=0 表示关闭被动同步。
     */
    private void tickPassiveUnifiedSync(GameSession session, long nowMillis) {
        long interval = lunarCoreProperties.getSync().getPassiveIntervalMs();
        if (interval <= 0) {
            return;
        }
        if (nowMillis - lastPassiveSyncMillis < interval) {
            return; // 未到间隔
        }
        lastPassiveSyncMillis = nowMillis;
        playerDataAsyncLoadService.reloadFullAsync(uid, SyncReason.TIMER);
    }

    /**
     * 开关 sync.periodicPersistenceEnabled 且间隔&gt;0 时，到期触发 persistAsync。
     */
    private void tickPeriodicPersistence(long nowMillis) {
        if (!lunarCoreProperties.getSync().isPeriodicPersistenceEnabled()) {
            return;
        }
        long interval = lunarCoreProperties.getSync().getPeriodicPersistenceIntervalMs();
        if (interval <= 0) {
            return;
        }
        if (nowMillis - lastPersistMillis < interval) {
            return;
        }
        lastPersistMillis = nowMillis;
        periodicPersistenceService.persistAsync(uid);
    }
}
