// 定时或 Tick 触发将会话内玩家核心快照异步写回数据库
package cn.itcast.demo.mylunarcore.player;

// 全局配置：周期持久化开关与间隔
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;

// 玩家领域聚合根
import cn.itcast.demo.mylunarcore.model.PlayerData;

// 玩家主实体，周期持久化的快照载体
import cn.itcast.demo.mylunarcore.model.PlayerEntity;

// 统一日志门面
import cn.itcast.demo.mylunarcore.common.AppLogger;

// 日志分类
import cn.itcast.demo.mylunarcore.common.LogCategory;

// 持久化仓储，执行 persistPlayerSnapshot
import cn.itcast.demo.mylunarcore.repo.PlayerDataRepository;

// 单用户在线会话
import cn.itcast.demo.mylunarcore.player.GameSession;

// 全局会话管理器
import cn.itcast.demo.mylunarcore.player.GameSessionManager;

// Bean 销毁钩子
import jakarta.annotation.PreDestroy;

// SLF4J 日志
import org.slf4j.Logger;

// Spring 服务组件
import org.springframework.stereotype.Service;

// 并发 Set 接口，标记正在持久化的 uid
import java.util.Set;

// 有界阻塞队列
import java.util.concurrent.ArrayBlockingQueue;

// 线程安全 Set 实现
import java.util.concurrent.ConcurrentHashMap;

// 线程池
import java.util.concurrent.ThreadPoolExecutor;

// 时间单位
import java.util.concurrent.TimeUnit;

/**
 * 玩家数据周期持久化服务。
 * <p>
 * 由 {@link cn.itcast.demo.mylunarcore.common.OnlinePlayer} Tick 周期调用 {@link #persistAsync(long)}，
 * 将会话内存中的 {@link PlayerEntity} 核心快照异步 UPDATE 回数据库，减少登出时一次性写库压力。
 * </p>
 */
@Service // 注册为 Spring Bean
public class PlayerDataPeriodicPersistenceService {

    /** 本类专用日志，分类 BUSINESS_DATA */
    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_DATA, PlayerDataPeriodicPersistenceService.class);

    /** 写库仓储 */
    private final PlayerDataRepository repository;

    /** 按 uid 获取在线会话 */
    private final GameSessionManager sessionManager;

    /** 读取周期持久化开关与间隔配置 */
    private final LunarCoreProperties properties;

    /** 异步持久化专用线程池 */
    private final ThreadPoolExecutor executor;

    /** 正在持久化中的 uid 集合，防止同一玩家并发重复刷库 */
    private final Set<Long> persisting = ConcurrentHashMap.newKeySet();

    /**
     * 初始化依赖与持久化线程池（参数可与异步加载共用配置键）。
     *
     * @param repository      数据仓储
     * @param sessionManager  会话管理器
     * @param properties      全局配置
     */
    public PlayerDataPeriodicPersistenceService(PlayerDataRepository repository,
                                                GameSessionManager sessionManager,
                                                LunarCoreProperties properties) {
        this.repository = repository; // 保存仓储
        this.sessionManager = sessionManager; // 保存会话管理器
        this.properties = properties; // 保存配置
        int cpu = Runtime.getRuntime().availableProcessors(); // CPU 核心数
        int configuredThreads = properties.getSync().getAsyncLoadThreads(); // 复用 asyncLoad 线程数配置
        int threads = configuredThreads > 0 ? configuredThreads : Math.max(2, cpu / 2); // 持久化默认比全量加载更保守
        int configuredQueue = properties.getSync().getAsyncLoadQueueCapacity(); // 复用队列容量配置
        int queueCapacity = configuredQueue > 0 ? configuredQueue : 1024; // 默认 1024
        this.executor = new ThreadPoolExecutor(
                threads, // 核心线程数
                threads, // 最大线程数
                30L, // 空闲存活时间
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(queueCapacity), // 有界队列
                r -> { // 线程工厂
                    Thread t = new Thread(r, "player-data-persist"); // 持久化线程命名
                    t.setDaemon(true); // 守护线程
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy() // 队列满时调用方线程执行
        );
    }

    /**
     * 获取配置的持久化间隔（毫秒）。
     *
     * @return 启用时返回间隔毫秒数；功能关闭时返回 0
     */
    public long intervalMs() {
        if (!properties.getSync().isPeriodicPersistenceEnabled()) { // 配置关闭周期持久化
            return 0L; // OnlinePlayer Tick 据此跳过持久化逻辑
        }
        return Math.max(0L, properties.getSync().getPeriodicPersistenceIntervalMs()); // 读取间隔，负值钳制为 0
    }

    /**
     * 异步触发单个玩家的核心快照持久化（同一 uid 并发去重）。
     *
     * @param uid 目标玩家 uid
     */
    public void persistAsync(long uid) {
        if (uid <= 0 || !persisting.add(uid)) { // 无效 uid 或该 uid 已在持久化中则跳过
            return;
        }
        executor.execute(() -> doPersist(uid)); // 提交异步写库任务
    }

    /**
     * 实际写库：从会话取出 PlayerEntity 并调用仓储 persistPlayerSnapshot。
     *
     * @param uid 玩家 uid
     */
    private void doPersist(long uid) {
        try {
            GameSession session = sessionManager.getOrNull(uid); // 获取在线会话
            if (session == null) { // 玩家已离线
                return;
            }
            PlayerData data = session.getPlayerData(); // 会话内聚合根
            PlayerEntity player = data == null ? null : data.getPlayer(); // 核心快照载体
            if (player == null) { // 主实体尚未加载到会话
                return;
            }
            int updated = repository.persistPlayerSnapshot(player); // 执行 UPDATE，返回受影响行数
            if (updated <= 0 && log.isDebugEnabled()) { // 无行更新（可能数据未变）
                log.debug("periodic persist skipped, uid={}", uid);
            }
        } catch (Exception e) {
            log.warn("periodic persist failed, uid={}", uid, e); // 记录异常，不向上抛出
        } finally {
            persisting.remove(uid); // 无论成功失败都释放进行中标记，允许下次 Tick 再次触发
        }
    }

    /**
     * 应用关闭时立即停止持久化线程池。
     */
    @PreDestroy
    public void shutdown() {
        executor.shutdownNow(); // 中断排队与执行中的持久化任务
    }
}
