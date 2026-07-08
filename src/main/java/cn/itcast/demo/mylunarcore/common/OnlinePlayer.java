// 单个在线玩家的 Tick 目标：被动同步、周期持久化与场景 Tick 转发

// 在线玩家快照：uid、Channel 与 Tick 状态
package cn.itcast.demo.mylunarcore.common;

// 全局配置（被动同步间隔等）

// 本项目业务类
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;

// 场景运行时上下文

// 本项目业务类
import cn.itcast.demo.mylunarcore.scene.SceneContext;

// 场景管理器（按玩家查找所属场景）

// 本项目业务类
import cn.itcast.demo.mylunarcore.scene.SceneManager;

// 会话对象

// 本项目业务类
import cn.itcast.demo.mylunarcore.player.GameSession;

// 会话管理器

// 本项目业务类
import cn.itcast.demo.mylunarcore.player.GameSessionManager;

// 异步全量加载服务

// 本项目业务类
import cn.itcast.demo.mylunarcore.player.PlayerDataAsyncLoadService;

// 周期持久化服务

// 本项目业务类
import cn.itcast.demo.mylunarcore.player.PlayerDataPeriodicPersistenceService;

// 同步原因枚举

// 本项目业务类
import cn.itcast.demo.mylunarcore.player.SyncReason;

// 日志门面

// 本项目业务类
import cn.itcast.demo.mylunarcore.common.AppLogger;

// 日志分类

// 本项目业务类
import cn.itcast.demo.mylunarcore.common.LogCategory;

// SLF4J

// SLF4J 日志接口
import lombok.Getter;
import org.slf4j.Logger;

/**
 * 每个连接对应的玩家 Tick：体力类增量逻辑依赖 delta；会话超时仍由心跳路径维护 lastActive。
 */

public final class OnlinePlayer implements Tickable {

    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_SESSION, OnlinePlayer.class); // 本类日志

    /**
     * -- GETTER --
     *
     * @return 玩家 uid
     */ // 返回 uid
    @Getter
    private final long uid; // 玩家 uid

    private final GameSessionManager sessionManager; // 查询会话是否存在

    private final SceneManager sceneManager; // 驱动所属场景 Tick

    private final PlayerDataAsyncLoadService playerDataAsyncLoadService; // 定时被动全量同步

    private final PlayerDataPeriodicPersistenceService periodicPersistenceService; // 定时快照写库

    private final LunarCoreProperties lunarCoreProperties; // 读取被动同步间隔等

    private long lastPassiveSyncMillis; // 上次触发 TIMER 同步的时间

    private long lastPersistMillis; // 上次触发周期持久化的时间

    /**
     * @param uid                         玩家 uid
     * @param sessionManager              会话管理器
     * @param sceneManager                场景管理器
     * @param playerDataAsyncLoadService  异步加载服务
     * @param periodicPersistenceService    周期持久化服务
     * @param lunarCoreProperties           全局配置
     */

    public OnlinePlayer(long uid,
                        GameSessionManager sessionManager,
                        SceneManager sceneManager,
                        PlayerDataAsyncLoadService playerDataAsyncLoadService,
                        PlayerDataPeriodicPersistenceService periodicPersistenceService,
                        LunarCoreProperties lunarCoreProperties) {
        this.uid = uid; // 保存注入的 uid 引用
        this.sessionManager = sessionManager; // 保存注入的 sessionManager 引用
        this.sceneManager = sceneManager; // 保存注入的 sceneManager 引用
        this.playerDataAsyncLoadService = playerDataAsyncLoadService; // 保存注入的 playerDataAsyncLoadService 引用
        this.periodicPersistenceService = periodicPersistenceService; // 保存注入的 periodicPersistenceService 引用
        this.lunarCoreProperties = lunarCoreProperties; // 保存注入的 lunarCoreProperties 引用
        this.lastPassiveSyncMillis = System.currentTimeMillis(); // 初始化基准时间
        this.lastPersistMillis = this.lastPassiveSyncMillis; // 字段赋值

    }

    @Override
    public void onTick(long nowMillis, long deltaMillis) {
        GameSession session = sessionManager.getOrNull(uid); // 会话可能已被移除

        if (session == null) { // 条件分支
            return;
        }

        tickPlayerState(session, nowMillis, deltaMillis); // 玩家自身逻辑
        SceneContext scene = sceneManager.getByPlayerUid(uid); // 查找所属场景

        if (scene != null) { // 条件分支
            scene.onTick(nowMillis, deltaMillis); // 转发场景 Tick
        }
    }

    /**
     * 玩家侧状态更新：日志 trace、被动同步、持久化节拍。
     */

    private void tickPlayerState(GameSession session, long nowMillis, long deltaMillis) {

        // 体力恢复、Buff 过期等应使用 deltaMillis；会话超时仍由心跳刷新 lastActiveMillis
        if (log.isTraceEnabled()) { // 条件分支
            log.trace("player tick uid={} deltaMs={}", uid, deltaMillis); // 极低噪声诊断
        }

        tickPassiveUnifiedSync(session, nowMillis); // 被动统一同步

        tickPeriodicPersistence(nowMillis); // 周期快照持久化

    }

    /**
     * 按配置间隔触发异步全量加载并以 TIMER 原因推送。
     */

    private void tickPassiveUnifiedSync(GameSession session, long nowMillis) {

        long intervalMs = lunarCoreProperties.getSync().getPassiveIntervalMs(); // <=0 关闭

        if (intervalMs <= 0) { // 条件分支
            return;
        }

        if (nowMillis - lastPassiveSyncMillis < intervalMs) { // 条件分支
            return; // 未到节拍
        }

        lastPassiveSyncMillis = nowMillis; // 更新上次触发时间

        playerDataAsyncLoadService.reloadFullAsync(uid, SyncReason.TIMER); // 异步 reload + 推送

    }

    /**
     * 按持久化服务返回的间隔触发快照写库。
     */

    private void tickPeriodicPersistence(long nowMillis) {

        long intervalMs = periodicPersistenceService.intervalMs(); // 关闭时为 0

        if (intervalMs <= 0) { // 条件分支
            return;
        }

        if (nowMillis - lastPersistMillis < intervalMs) { // 条件分支
            return;
        }

        lastPersistMillis = nowMillis;

        periodicPersistenceService.persistAsync(uid); // 异步写库

    }
}

