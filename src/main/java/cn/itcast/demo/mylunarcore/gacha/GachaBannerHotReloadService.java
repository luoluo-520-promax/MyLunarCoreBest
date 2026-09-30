// 抽卡卡池配置热更新服务所在包：监听 Banners.json 文件变更并通知在线玩家刷新卡池展示
package cn.itcast.demo.mylunarcore.gacha;

// AppLogger：项目统一日志门面，按业务分类输出，便于运维过滤
import cn.itcast.demo.mylunarcore.common.AppLogger;
// LogCategory：日志分类枚举，本类使用 BUSINESS_GACHA 标识抽卡业务日志
import cn.itcast.demo.mylunarcore.common.LogCategory;
// GameSession：在线玩家会话对象，持有 Netty Channel 与玩家上下文
import cn.itcast.demo.mylunarcore.player.GameSession;
// GameSessionManager：全局会话管理器，提供在线玩家快照用于广播推送
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
// Channel：Netty 网络通道，用于向客户端推送卡池更新通知
import io.netty.channel.Channel;
// PostConstruct：Spring Bean 初始化完成后自动回调，用于启动轮询任务
import jakarta.annotation.PostConstruct;
// PreDestroy：Spring Bean 销毁前回调，用于优雅关闭定时调度器
import jakarta.annotation.PreDestroy;
// Logger：SLF4J 日志接口
import org.slf4j.Logger;
// Component：声明为 Spring 单例 Bean，随应用启动自动注册
import org.springframework.stereotype.Component;

// File：以本地文件路径访问 data/Banners.json，读取最后修改时间
import java.io.File;
// Executors：创建单线程定时调度器，避免多线程并发重载配置
import java.util.concurrent.Executors;
// ScheduledExecutorService：定时任务接口，按固定间隔轮询文件变更
import java.util.concurrent.ScheduledExecutorService;
// TimeUnit：时间单位枚举，指定轮询间隔为秒
import java.util.concurrent.TimeUnit;

/**
 * 卡池 Banner 配置文件热更新监听服务。
 * <p>
 * 该服务采用"低成本轮询 + 原子重载"的方式，不依赖操作系统文件系统事件（WatchService），
 * 这是为了兼容打包部署、Docker 容器挂载卷以及不同操作系统上文件通知语义不一致的问题。
 * 检测到 {@code data/Banners.json} 的 lastModified 发生变化后，先尝试通过 GachaConfigService 重新加载配置；
 * 只有加载成功，才向所有在线玩家推送卡池更新通知（CmdIds.GACHA_BANNER_UPDATE_SC_NOTIFY），
 * 避免半加载或空配置状态污染客户端卡池界面。
 * </p>
 * <p>
 * 推送策略为全量快照而非增量 diff：直接推送当前全部可见卡池列表。
 * 取舍是"实现简单、状态一致性高"，代价是热更时多发一点数据；
 * 由于卡池数量通常较少（4 类池各 0~1 个活跃 Banner），这个开销远低于维护复杂增量逻辑的成本。
 * </p>
 */
@Component
public class GachaBannerHotReloadService {

    /** 抽卡热更专用日志记录器，分类为 BUSINESS_GACHA */
    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_GACHA, GachaBannerHotReloadService.class);
    /** 外部可热更的卡池配置文件相对路径，运维可直接替换此文件 */
    private static final String BANNERS_PATH = "data/Banners.json";

    /** 卡池 Banner 配置加载服务，负责 reload() 原子替换内存索引 */
    private final GachaConfigService configService;
    /** 在线会话管理器，提供 snapshotSessions() 获取当前所有在线玩家 */
    private final GameSessionManager sessionManager;
    /** 抽卡 Netty 协议服务，负责组装并推送 GachaBannerUpdateScNotify */
    private final GachaNettyService gachaNettyService;
    /** 单线程定时调度器：保证 tick() 不会并发执行，避免重复重载 */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    /** 上次观测到的 Banners.json 最后修改时间戳（毫秒），volatile 保证多线程可见性 */
    private volatile long lastSeenModifiedMillis = -1L;

    /**
     * 构造器注入热更所需的三个依赖。
     *
     * @param configService      配置重载服务
     * @param sessionManager     在线会话管理器
     * @param gachaNettyService  卡池更新推送服务
     */
    public GachaBannerHotReloadService(GachaConfigService configService,
                                      GameSessionManager sessionManager,
                                      GachaNettyService gachaNettyService) {
        this.configService = configService;           // 配置原子重载
        this.sessionManager = sessionManager;         // 在线玩家枚举
        this.gachaNettyService = gachaNettyService;   // 协议推送
    }

    /**
     * Spring 初始化完成后启动热更轮询。
     * 先记录当前文件修改时间作为基线，避免服务刚启动就把"未变更"的配置误判为热更事件。
     */
    @PostConstruct
    public void start() {
        // 构造 Banners.json 文件对象，用于读取 lastModified
        File f = new File(BANNERS_PATH);
        // 记录启动时的文件修改时间，作为后续变更检测的基准
        lastSeenModifiedMillis = safeLastModified(f);
        // 仅当外部配置文件确实存在时才启动轮询，避免无文件时无意义空转
        if (f.isFile()) {
            // 延迟 2 秒首次执行，之后每 2 秒轮询一次；卡池配置非高频变更，无需更激进
            scheduler.scheduleAtFixedRate(this::tick, 2, 2, TimeUnit.SECONDS);
            log.info("Gacha banner hot-reload enabled, path={}", BANNERS_PATH);
        }
    }

    /**
     * 定时轮询回调：检测文件变更 → 重载配置 → 向在线玩家推送卡池更新。
     * 整个方法包裹在 try-catch 中，单次 tick 异常不影响后续轮询。
     */
    private void tick() {
        try {
            // 每次 tick 重新构造 File 对象，获取最新 lastModified
            File f = new File(BANNERS_PATH);
            // 安全读取文件修改时间；文件不存在或不可读时返回 0
            long modified = safeLastModified(f);
            // 文件不存在或不可访问，跳过本次 tick
            if (modified <= 0) return;
            // 读取上次已观测的时间戳
            long prev = lastSeenModifiedMillis;
            // 修改时间未变化（或首次启动 prev 无效），无需重载
            if (prev > 0 && modified <= prev) return;
            // 先更新"已观察到的时间戳"，再执行 reload；
            // 即使 reload 失败，下次文件再次变更时仍能继续触发（不会死锁在旧时间戳）
            lastSeenModifiedMillis = modified;
            // 尝试原子重载配置；失败时保留旧配置并跳过后续推送
            if (!configService.reload()) return;
            // 统计成功推送的在线会话数，用于运维日志
            int pushed = 0;
            // 遍历所有在线会话快照（线程安全拷贝，避免遍历时并发修改）
            for (GameSession s : sessionManager.snapshotSessions()) {
                // 从会话中取出 Netty Channel；会话为 null 时跳过
                Channel ch = s == null ? null : s.getChannel();
                // 仅向 Channel 存在且仍处于活跃连接状态的玩家推送
                if (ch != null && ch.isActive()) {
                    // 推送 GachaBannerUpdateScNotify，客户端收到后刷新卡池列表，无需重新登录或主动拉取
                    gachaNettyService.pushBannerUpdateNotify(ch);
                    pushed++;  // 推送成功计数
                }
            }
            // 记录热更成功日志：包含文件修改时间与推送会话数（508 为 GACHA_BANNER_UPDATE_SC_NOTIFY 命令号）
            log.info("Banners.json reloaded (mtime={}), pushed 508 to {} sessions", modified, pushed);
        } catch (Exception e) {
            // tick 异常仅记 debug 日志，不中断调度器，下次 tick 继续尝试
            log.debug("Gacha banner hot-reload tick failed", e);
        }
    }

    /**
     * 安全读取文件最后修改时间，文件不存在或 IO 异常时返回 0。
     *
     * @param f 目标文件对象
     * @return 最后修改时间戳（毫秒），不可读时返回 0L
     */
    private long safeLastModified(File f) {
        try {
            // 文件必须存在且为普通文件（非目录）
            if (!f.exists() || !f.isFile()) return 0L;
            // 返回 JVM 感知的最后修改时间
            return f.lastModified();
        } catch (Exception e) {
            // 权限不足、路径异常等情况下返回 0，调用方视为"文件不可用"
            return 0L;
        }
    }

    /**
     * Spring 销毁前优雅关闭定时调度器，释放线程资源。
     */
    @PreDestroy
    public void stop() {
        // shutdownNow：立即中断正在执行的 tick 并拒绝新任务
        scheduler.shutdownNow();
    }
}
