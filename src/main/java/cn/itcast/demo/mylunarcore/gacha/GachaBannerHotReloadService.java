// 抽卡卡池配置热更新服务所在包：监听 Banners.json 变更并通知在线玩家刷新卡池展示
package cn.itcast.demo.mylunarcore.gacha;

// GachaConfigService：负责实际 reload Banners.json 到内存索引
import cn.itcast.demo.mylunarcore.gacha.GachaConfigService;
// AppLogger：项目统一日志门面
import cn.itcast.demo.mylunarcore.common.AppLogger;
// LogCategory：抽卡业务日志分类
import cn.itcast.demo.mylunarcore.common.LogCategory;
// GameSession：单个在线玩家的会话对象，持有 Netty Channel 等信息
import cn.itcast.demo.mylunarcore.player.GameSession;
// GameSessionManager：管理全部在线会话，提供快照遍历能力
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
// Channel：Netty 网络连接通道，用于向客户端推送热更通知
import io.netty.channel.Channel;
// PostConstruct：Bean 初始化后启动轮询定时任务
import jakarta.annotation.PostConstruct;
// PreDestroy：Bean 销毁前关闭线程池，避免进程退出时线程泄漏
import jakarta.annotation.PreDestroy;
// Logger：SLF4J 日志接口
import org.slf4j.Logger;
// Component：注册为 Spring 单例 Bean
import org.springframework.stereotype.Component;

// File：检测 Banners.json 文件最后修改时间
import java.io.File;
// Executors：创建单线程定时调度器
import java.util.concurrent.Executors;
// ScheduledExecutorService：按固定间隔执行 tick 轮询
import java.util.concurrent.ScheduledExecutorService;
// TimeUnit：指定调度时间单位为秒
import java.util.concurrent.TimeUnit;

/**
 * 卡池 Banner 配置文件热更新监听服务。
 * <p>通过定时轮询 {@code data/Banners.json} 的文件修改时间（mtime），检测到变更后调用
 * {@link GachaConfigService#reload()} 重新加载配置，并向所有活跃在线玩家推送
 * {@link GachaNettyService#pushBannerUpdateNotify(Channel)}（协议号 508），
 * 使客户端无需重登即可刷新卡池列表与 UP 信息。</p>
 * <p>选用轮询而非 WatchService，是因为在 Windows/IDE 热保存、Docker 挂载等开发环境下
 * 文件监听行为不够稳定，轮询 2 秒一次的开销可接受。</p>
 */
@Component // Spring 单例，随应用启停自动 start/stop
public class GachaBannerHotReloadService {

    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_GACHA, GachaBannerHotReloadService.class); // 抽卡模块日志

    private static final String BANNERS_PATH = "data/Banners.json"; // 与 GachaConfigService 一致的配置文件路径

    private final GachaConfigService configService;       // 配置重载服务，tick 检测到变更时调用 reload()
    private final GameSessionManager sessionManager;      // 在线会话管理器，用于遍历全部玩家推送通知
    private final GachaNettyService gachaNettyService;    // 抽卡 Netty 业务，封装 pushBannerUpdateNotify 推送逻辑

    // 单线程定时调度器：保证 tick 串行执行，避免并发 reload 与重复推送
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    // 上次已知的文件 mtime（毫秒）；-1 表示尚未初始化；-1 与首次 tick 配合避免启动瞬间误触 reload
    private volatile long lastSeenModifiedMillis = -1L;

    /**
     * 构造器注入：Spring 自动装配三个依赖 Bean。
     *
     * @param configService      Banner 配置加载服务
     * @param sessionManager     在线玩家会话管理器
     * @param gachaNettyService  抽卡协议业务（含推送能力）
     */
    public GachaBannerHotReloadService(GachaConfigService configService,
                                      GameSessionManager sessionManager,
                                      GachaNettyService gachaNettyService) {
        this.configService = configService;           // 保存配置服务引用，tick 中调用 reload()
        this.sessionManager = sessionManager;         // 保存会话管理器，遍历在线玩家推送
        this.gachaNettyService = gachaNettyService;   // 保存抽卡服务，调用 pushBannerUpdateNotify
    }

    /**
     * Bean 初始化完成后启动热更轮询。
     * <p>先记录当前文件 mtime 作为基线，再每 2 秒执行一次 {@link #tick()}。</p>
     */
    @PostConstruct
    public void start() {
        File f = new File(BANNERS_PATH); // 构造配置文件 File 对象
        lastSeenModifiedMillis = safeLastModified(f); // 记录启动时刻的 mtime，避免首次 tick 误判为“刚变更”

        if (f.isFile()) {
            // 固定速率轮询：初始延迟 2 秒，之后每 2 秒执行 tick；跨平台比 WatchService 更可靠
            scheduler.scheduleAtFixedRate(this::tick, 2, 2, TimeUnit.SECONDS);
            log.info("Gacha banner hot-reload enabled, path={}", BANNERS_PATH); // 启动成功日志，便于确认热更已生效
        } else {
            log.info("Gacha banner hot-reload skipped (external file not found: {}), using classpath fallback", BANNERS_PATH);
        }
    }

    /**
     * 单次轮询逻辑：比较 mtime → reload 配置 → 向活跃会话推送卡池更新通知。
     * <p>任何异常均被捕获并以 debug 级别记录，不影响后续轮询周期。</p>
     */
    private void tick() {
        try {
            File f = new File(BANNERS_PATH); // 每次 tick 重新构造 File，获取最新 mtime
            long modified = safeLastModified(f); // 安全读取最后修改时间（毫秒）
            if (modified <= 0) { // 文件不存在或读取失败，跳过本轮
                return;
            }
            long prev = lastSeenModifiedMillis; // 读取 volatile 字段到局部变量，保证本轮比较的一致性
            if (prev > 0 && modified <= prev) { // mtime 未变化（或时钟回拨导致 modified <= prev），无需 reload
                return;
            }

            // 先更新 lastSeen，再执行可能耗时的 reload：防止 reload 期间文件再次变更导致连续多次 reload 风暴
            lastSeenModifiedMillis = modified;

            boolean ok = configService.reload(); // 重新解析 Banners.json 并替换内存索引
            if (!ok) { // JSON 解析失败等异常，保留旧配置，不向客户端推送可能不完整的数据
                return;
            }

            int pushed = 0; // 统计成功推送的会话数
            for (GameSession s : sessionManager.snapshotSessions()) { // 遍历当前在线会话快照（线程安全副本）
                Channel ch = s == null ? null : s.getChannel(); // 从会话取出 Netty Channel；空会话跳过
                if (ch != null && ch.isActive()) { // 仅向连接仍活跃的客户端推送
                    gachaNettyService.pushBannerUpdateNotify(ch); // 推送 GACHA_BANNER_UPDATE_SC_NOTIFY（508）
                    pushed++; // 推送计数 +1
                }
            }
            log.info("Banners.json reloaded (mtime={}), pushed 508 to {} sessions", modified, pushed); // 热更成功及推送规模
        } catch (Exception e) {
            log.debug("Gacha banner hot-reload tick failed", e); // 非预期异常仅 debug，不中断调度器
        }
    }

    /**
     * 安全读取文件最后修改时间，文件不存在或 IO 异常时返回 0。
     *
     * @param f 目标文件
     * @return 最后修改毫秒时间戳；不可读时返回 0L
     */
    private long safeLastModified(File f) {
        try {
            if (!f.exists() || !f.isFile()) { // 路径不存在或不是普通文件（如目录）
                return 0L; // 视为无效，tick 将跳过
            }
            return f.lastModified(); // 返回 JVM 感知的 mtime（毫秒）
        } catch (Exception e) {
            return 0L; // 任何 SecurityException 等异常均降级为 0，不向外抛出
        }
    }

    /**
     * Bean 销毁前关闭调度线程池，立即中断正在执行的 tick。
     */
    @PreDestroy
    public void stop() {
        scheduler.shutdownNow(); // 强制关闭单线程池，避免应用停止后仍有后台线程存活
    }
}
