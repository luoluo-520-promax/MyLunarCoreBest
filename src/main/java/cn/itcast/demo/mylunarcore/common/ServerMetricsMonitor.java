// 服务器运行指标周期采集与日志输出
package cn.itcast.demo.mylunarcore.common;

// AI 辅助问答审计服务：统计成功/失败/耗时等
import cn.itcast.demo.mylunarcore.assist.AssistAuditService;
// AI 辅助反馈服务：统计用户反馈与采纳率
import cn.itcast.demo.mylunarcore.assist.AssistFeedbackService;
// 在线会话管理器：获取在线玩家数
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
// 玩家数据持久化指标：失败/重试/版本冲突等计数
import cn.itcast.demo.mylunarcore.player.PlayerDataMetrics;
// Bean 初始化后启动监控线程
import jakarta.annotation.PostConstruct;
// Bean 销毁前停止监控线程
import jakarta.annotation.PreDestroy;
// SLF4J 日志接口
import org.slf4j.Logger;
// 注册为 Spring 组件
import org.springframework.stereotype.Component;

// JVM 内存监控：堆使用量
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
// JVM 线程监控：线程总数
import java.lang.management.ThreadMXBean;
// 定时线程池工厂
import java.util.concurrent.Executors;
// 单线程定时调度器
import java.util.concurrent.ScheduledExecutorService;
// 调度周期时间单位
import java.util.concurrent.TimeUnit;

/**
 * 独立定时线程周期性输出 JVM 与游戏吞吐指标，不占用 Netty I/O 与游戏主循环线程。
 * <p>每 60 秒输出一条结构化日志，供日志采集系统聚合；同时包含 AI 辅助服务
 * 的错误率告警（错误率超过 5% 时单独 warn 一条）。</p>
 */
@Component
public class ServerMetricsMonitor {

    private static final Logger log = AppLogger.logger(LogCategory.SYSTEM, ServerMetricsMonitor.class);

    // 协议包处理计数（供吞吐量差分计算）
    private final GameTrafficMetrics gameTrafficMetrics;
    // 在线会话管理：在线玩家数
    private final GameSessionManager sessionManager;
    // 玩家数据落盘指标
    private final PlayerDataMetrics playerDataMetrics;
    // AI 辅助审计指标
    private final AssistAuditService assistAuditService;
    // AI 辅助反馈指标
    private final AssistFeedbackService feedbackService;

    // 定时调度器（60 秒周期）
    private ScheduledExecutorService scheduler;
    // 上次日志时的累计包数：用于计算 60 秒内增量
    private volatile long lastLoggedPacketCount;

    /**
     * 构造器注入各指标源。
     */
    public ServerMetricsMonitor(GameTrafficMetrics gameTrafficMetrics,
                                GameSessionManager sessionManager,
                                PlayerDataMetrics playerDataMetrics,
                                AssistAuditService assistAuditService,
                                AssistFeedbackService feedbackService) {
        this.gameTrafficMetrics = gameTrafficMetrics;
        this.sessionManager = sessionManager;
        this.playerDataMetrics = playerDataMetrics;
        this.assistAuditService = assistAuditService;
        this.feedbackService = feedbackService;
    }

    /**
     * 启动 60 秒周期的指标日志线程（守护线程，不阻止 JVM 退出）。
     */
    @PostConstruct
    public void start() {
        // 记录基准包数，保证首次输出时增量从 0 起算
        lastLoggedPacketCount = gameTrafficMetrics.getPacketsHandled();
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "server-metrics-monitor");
            t.setDaemon(true); // 守护线程：应用退出时自动终止
            return t;
        });
        // 固定 60 秒周期（initialDelay 也取 60 秒，避免启动瞬间采集不完整）
        scheduler.scheduleAtFixedRate(this::logMetrics, 60, 60, TimeUnit.SECONDS);
    }

    /**
     * 停止监控线程。
     */
    @PreDestroy
    public void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    /**
     * 采集并输出一轮指标：
     * <ul>
     *   <li>JVM：堆使用量、线程数</li>
     *   <li>吞吐：60 秒内处理的协议包增量、在线玩家数</li>
     *   <li>持久化：失败/重载失败/拒绝/版本冲突计数</li>
     *   <li>AI 辅助：问答总量、LLM/本地攻略/远端命中、缓存命中、配额拒绝、平均耗时、错误率、反馈采纳率</li>
     * </ul>
     */
    private void logMetrics() {
        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();
        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        long heapUsed = mem.getHeapMemoryUsage().getUsed(); // 当前堆已用字节
        // 计算本轮与上轮之间的包处理增量
        long total = gameTrafficMetrics.getPacketsHandled();
        long packetsDelta = Math.max(0L, total - lastLoggedPacketCount);
        lastLoggedPacketCount = total;
        // AI 辅助错误率：错误总数 / 问答总数（0 问答时为 0）
        long ask = assistAuditService.getAskCount();
        double errRatio = ask <= 0 ? 0.0 : assistAuditService.getErrorCount() * 1.0 / ask;
        if (errRatio > 0.05) {
            // 错误率超过 5% 触发独立告警日志，便于监控平台按关键词告警
            log.warn("assist alert: errorRatio={} askTotal={} errors={}",
                    String.format("%.3f", errRatio), ask, assistAuditService.getErrorCount());
        }
        // 结构化 kv 日志：各字段与监控面板/告警规则一一对应
        log.info("server metrics: heapUsedBytes={}, threadCount={}, packetsDelta60s={}, onlinePlayers={}, " +
                        "persistFail={}, reloadFail={}, persistReject={}, versionConflict={}, " +
                        "assistAsk={}, assistLlm={}, assistGuideLocal={}, assistRemote={}, assistCacheHit={}, " +
                        "assistQuotaReject={}, assistAvgLatencyMs={}, assistErrorRatio={}, " +
                        "assistFeedback={}, assistUsefulRate={}",
                heapUsed, threads.getThreadCount(), packetsDelta,
                sessionManager.getOnlinePlayerCount(),
                playerDataMetrics.getPersistFailTotal(),
                playerDataMetrics.getReloadFailTotal(),
                playerDataMetrics.getPersistRejectTotal(),
                playerDataMetrics.getVersionConflictTotal(),
                ask,
                assistAuditService.getLlmHitCount(),
                assistAuditService.getGuideLocalCount(),
                assistAuditService.getRemoteCount(),
                assistAuditService.getCacheHitCount(),
                assistAuditService.getQuotaRejectCount(),
                String.format("%.1f", assistAuditService.averageLatencyMs()),
                String.format("%.3f", errRatio),
                feedbackService.getFeedbackCount(),
                String.format("%.3f", feedbackService.usefulRate()));
    }
}
