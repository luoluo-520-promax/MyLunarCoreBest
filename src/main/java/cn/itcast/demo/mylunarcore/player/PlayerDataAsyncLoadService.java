// 在独立线程池中异步加载全量玩家数据并回填在线会话
package cn.itcast.demo.mylunarcore.player;

// 全局配置：线程数、队列容量等
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;

// 玩家领域聚合根，异步加载的目标对象
import cn.itcast.demo.mylunarcore.model.PlayerData;

// 统一日志门面
import cn.itcast.demo.mylunarcore.common.AppLogger;

// 日志分类
import cn.itcast.demo.mylunarcore.common.LogCategory;

// 玩家数据持久化仓储，执行全量 loadAllData
import cn.itcast.demo.mylunarcore.repo.PlayerDataRepository;

// 单用户在线会话对象
import cn.itcast.demo.mylunarcore.player.GameSession;

// 全局会话索引，按 uid 查找会话
import cn.itcast.demo.mylunarcore.player.GameSessionManager;

// Bean 销毁前钩子，用于关闭线程池
import jakarta.annotation.PreDestroy;

// SLF4J 日志
import org.slf4j.Logger;

// Spring 服务层组件
import org.springframework.stereotype.Service;

// 有界阻塞队列，限制排队任务数量
import java.util.concurrent.ArrayBlockingQueue;

// 线程池执行器
import java.util.concurrent.ThreadPoolExecutor;

// 时间单位枚举
import java.util.concurrent.TimeUnit;

/**
 * 玩家数据异步全量加载服务。
 * <p>
 * 统一封装「后台线程 loadAllData → 回填 GameSession → 推送统一同步」流程，
 * 避免在 Netty IO 线程直接执行耗时的数据库读取，防止阻塞网络事件循环。
 * </p>
 */
@Service // 注册为 Spring Bean
public class PlayerDataAsyncLoadService {

    /** 本类专用日志，分类 BUSINESS_DATA */
    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_DATA, PlayerDataAsyncLoadService.class);

    /** 玩家数据仓储，负责从 DB/缓存组装 PlayerData */
    private final PlayerDataRepository repository;

    /** 同步协调器，加载完成后组包并推送 */
    private final PlayerSyncCoordinator playerSyncCoordinator;

    /** 会话管理器，按 uid 查找当前在线会话 */
    private final GameSessionManager sessionManager;

    /** 专用线程池，执行 doFullLoad 异步任务 */
    private final ThreadPoolExecutor executor;

    /**
     * 初始化依赖与线程池参数。
     *
     * @param repository              数据仓储
     * @param playerSyncCoordinator   同步协调器
     * @param sessionManager          会话管理器
     * @param properties              全局配置（线程数、队列容量）
     */
    public PlayerDataAsyncLoadService(PlayerDataRepository repository,
                                      PlayerSyncCoordinator playerSyncCoordinator,
                                      GameSessionManager sessionManager,
                                      LunarCoreProperties properties) {
        this.repository = repository; // 保存仓储引用
        this.playerSyncCoordinator = playerSyncCoordinator; // 保存协调器引用
        this.sessionManager = sessionManager; // 保存会话管理器引用
        int cpu = Runtime.getRuntime().availableProcessors(); // 获取 JVM 可见的 CPU 核心数
        int configuredThreads = properties.getSync().getAsyncLoadThreads(); // 配置文件中的线程数（0 表示用默认）
        int threads = configuredThreads > 0 ? configuredThreads : Math.max(4, cpu); // 默认至少 4 线程
        int configuredQueue = properties.getSync().getAsyncLoadQueueCapacity(); // 配置队列容量
        int queueCapacity = configuredQueue > 0 ? configuredQueue : 1024; // 默认 1024 个排队任务
        this.executor = new ThreadPoolExecutor(
                threads, // 核心线程数
                threads, // 最大线程数（固定大小池）
                30L, // 空闲线程存活时间（固定池实际不回收）
                TimeUnit.SECONDS, // 存活时间单位
                new ArrayBlockingQueue<>(queueCapacity), // 有界队列，防止内存无限增长
                runnable -> { // 自定义线程工厂
                    Thread t = new Thread(runnable, "player-data-async-loader"); // 命名便于 jstack 排查
                    t.setDaemon(true); // 守护线程，不阻止 JVM 正常退出
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy() // 队列满时在调用线程执行，形成背压
        );
    }

    /**
     * 触发异步全量加载：若会话仍存在且加载完成后版本号仍有效，则回填并推送同步。
     *
     * @param uid    目标玩家 uid
     * @param reason 同步原因（LOGIN / DATA_CHANGE / TIMER）
     */
    public void reloadFullAsync(long uid, SyncReason reason) {
        GameSession session = sessionManager.getOrNull(uid); // 获取当前在线会话
        if (session == null) { // 玩家已离线，取消加载
            return;
        }
        long loadVersion = session.nextDataLoadVersion(); // 递增版本号，用于丢弃过期的异步结果
        executor.execute(() -> doFullLoad(uid, session, loadVersion, reason)); // 提交到线程池异步执行
    }

    /**
     * 实际加载逻辑：从 DB 全量读取，校验会话与版本号后回填并推送。
     *
     * @param uid             玩家 uid
     * @param expectedSession 发起加载时的会话对象引用（用于检测顶号）
     * @param loadVersion     本次加载携带的版本号
     * @param reason          同步原因
     */
    private void doFullLoad(long uid, GameSession expectedSession, long loadVersion, SyncReason reason) {
        try {
            PlayerData data = repository.loadAllData(uid); // IO 操作：全量组装 PlayerData
            GameSession latest = sessionManager.getOrNull(uid); // 加载完成后再次获取会话
            if (latest == null || latest != expectedSession) { // 会话已断开或被顶号替换
                return;
            }
            if (!latest.isCurrentDataLoadVersion(loadVersion)) { // 已有更新的加载请求，本次结果过期
                return;
            }
            latest.setPlayerData(data); // 将会话内存中的聚合根替换为最新全量数据
            playerSyncCoordinator.pushToSession(latest, data, reason); // 组包并以指定原因推送客户端
        } catch (Exception e) {
            log.warn("async load player data failed, uid={}, reason={}", uid, reason, e); // 记录失败但不抛异常
        }
    }

    /**
     * 应用关闭时立即停止线程池，丢弃排队中的加载任务。
     */
    @PreDestroy
    public void shutdown() {
        executor.shutdownNow(); // 中断正在执行与等待的任务
    }
}
