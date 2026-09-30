// 热修复版本文件（hotfix.json）变更监听与自动重载服务
package cn.itcast.demo.mylunarcore.common;

// 全局配置：hotfix.resource 路径与 dataDir 等
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
// 在线会话管理器：广播时需要遍历会话快照（此处间接通过 UpdateNotifyBroadcaster）
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
// Bean 初始化完成回调（启动文件监听线程）
import jakarta.annotation.PostConstruct;
// Bean 销毁前回调（停止监听线程）
import jakarta.annotation.PreDestroy;
// SLF4J 日志接口
import org.slf4j.Logger;
// 注册为 Spring 组件
import org.springframework.stereotype.Component;

// 文件对象：读取 lastModified 时间戳
import java.io.File;
// 定时线程池工厂
import java.util.concurrent.Executors;
// 单线程定时调度器（轮询文件 mtime）
import java.util.concurrent.ScheduledExecutorService;
// 调度周期时间单位
import java.util.concurrent.TimeUnit;

/**
 * 监听 hotfix.json 变更，重载版本配置并向在线玩家推送 VERSION_UPDATE_SC_NOTIFY。
 * <p>实现方式：每 2 秒轮询一次文件 lastModified 时间戳，仅当 mtime 前进时才触发重载，
 * 避免反复读取未变化的文件。</p>
 */
@Component
public class VersionHotReloadService {

    private static final Logger log = AppLogger.logger(LogCategory.SYSTEM, VersionHotReloadService.class);

    // 热修复数据服务：负责实际重新解析 hotfix.json
    private final HotfixDataService hotfixDataService;
    // 广播器：向全部在线会话推送版本更新通知
    private final UpdateNotifyBroadcaster updateNotifyBroadcaster;
    // 全局配置（读取 hotfix.resource / dataDir）
    private final LunarCoreProperties properties;

    // 专用单线程调度器：避免与游戏主循环、Netty 业务线程混用
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    // 上一次观察到的文件修改时间（毫秒）；-1 表示尚未初始化
    private volatile long lastSeenModifiedMillis = -1L;
    // 当前监听的文件绝对路径（volatile：start 在构造后调用，tick 在独立线程读取）
    private volatile String watchedPath = "";

    /**
     * 构造器注入依赖。
     */
    public VersionHotReloadService(HotfixDataService hotfixDataService,
                                   UpdateNotifyBroadcaster updateNotifyBroadcaster,
                                   LunarCoreProperties properties) {
        this.hotfixDataService = hotfixDataService;
        this.updateNotifyBroadcaster = updateNotifyBroadcaster;
        this.properties = properties;
    }

    /**
     * 启动监听：解析目标文件路径，记录初始 mtime；文件存在则启动 2 秒周期轮询。
     */
    @PostConstruct
    public void start() {
        watchedPath = resolveHotfixPath();
        File file = new File(watchedPath);
        // 记录启动时 mtime，避免把启动前已存在的旧文件误判为「变更」
        lastSeenModifiedMillis = safeLastModified(file);
        if (file.isFile()) {
            scheduler.scheduleAtFixedRate(this::tick, 2, 2, TimeUnit.SECONDS);
            log.info("Version hot-reload enabled, path={}", watchedPath);
        } else {
            // 文件不存在则跳过监听：首次运营上线前可能还没有 hotfix.json
            log.info("Version hot-reload skipped (file not found: {})", watchedPath);
        }
    }

    /**
     * 周期轮询回调：mtime 前进 → 重载 hotfix 数据 → 向在线玩家广播版本更新通知。
     */
    private void tick() {
        try {
            File file = new File(watchedPath);
            long modified = safeLastModified(file);
            if (modified <= 0) {
                // 文件被删除或读取失败：保持现状等待恢复
                return;
            }
            long prev = lastSeenModifiedMillis;
            if (prev > 0 && modified <= prev) {
                // mtime 未前进：文件未变化，跳过
                return;
            }
            lastSeenModifiedMillis = modified;
            // 重新解析 hotfix.json；失败时保留上一份数据（HotfixDataService 内部处理）
            if (!hotfixDataService.reload()) {
                return;
            }
            // 通知所有在线客户端：版本更新协议（VERSION_UPDATE_SC_NOTIFY）
            int pushed = updateNotifyBroadcaster.broadcastVersionUpdate();
            log.info("hotfix.json reloaded (mtime={}), pushed 78 to {} sessions", modified, pushed);
        } catch (Exception e) {
            // 单次轮询失败仅记 debug：不中断后续轮询
            log.debug("Version hot-reload tick failed", e);
        }
    }

    /**
     * 解析 hotfix.json 的绝对路径：优先取配置 {@code lunarcore.hotfix.resource}（支持 file: 前缀），
     * 否则回退到 {@code dataDir/hotfix.json}。
     */
    private String resolveHotfixPath() {
        String resource = properties.getHotfix().getResource();
        if (resource != null && resource.startsWith("file:")) {
            return resource.substring("file:".length());
        }
        return properties.getDataDir() + "/hotfix.json";
    }

    /**
     * 安全读取文件 mtime：文件不存在/非普通文件/读取异常均返回 0。
     */
    private long safeLastModified(File file) {
        try {
            if (!file.exists() || !file.isFile()) {
                return 0L;
            }
            return file.lastModified();
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * 停止监听线程，释放调度器资源。
     */
    @PreDestroy
    public void stop() {
        scheduler.shutdownNow();
    }
}
