// 周期性输出 JVM 与游戏吞吐指标的守护线程调度器
package cn.itcast.demo.mylunarcore.common;

// Bean 生命周期：启动调度
// Bean 生命周期：启动调度
import jakarta.annotation.PostConstruct;

// Bean 生命周期：停止调度
// Bean 生命周期：停止调度
import jakarta.annotation.PreDestroy;

// 日志门面
// 日志门面
import cn.itcast.demo.mylunarcore.common.AppLogger;

// 日志分类
// 日志分类
import cn.itcast.demo.mylunarcore.common.LogCategory;

// SLF4J
// SLF4J
import org.slf4j.Logger;

// Spring 组件
// Spring 组件
import org.springframework.stereotype.Component;

// JVM 管理 Bean 工厂入口
// JVM 管理 Bean 工厂入口
import java.lang.management.ManagementFactory;

// 内存 MXBean
// 内存 MXBean
import java.lang.management.MemoryMXBean;

// 线程 MXBean
// 线程 MXBean
import java.lang.management.ThreadMXBean;

// 线程池工厂
// 线程池工厂
import java.util.concurrent.Executors;

// 调度线程池
// 调度线程池
import java.util.concurrent.ScheduledExecutorService;

// 时间单位
// 时间单位
import java.util.concurrent.TimeUnit;

/**

 * 独立定时线程周期性输出 JVM 与游戏包吞吐等指标，不占用 Netty I/O 与游戏主循环线程。

 */

@Component // 注册为 Spring Bean

public class ServerMetricsMonitor {

    private static final Logger log = AppLogger.logger(LogCategory.SYSTEM, ServerMetricsMonitor.class); // 本类日志

    private final GameTrafficMetrics gameTrafficMetrics; // 协议计数来源

    private ScheduledExecutorService scheduler; // 定时任务线程池（延迟创建）

    private long lastPacketCount; // 上一间隔结束时的累计包数，用于计算区间增量

    /**

     * @param gameTrafficMetrics 注入计数器

     */

    public ServerMetricsMonitor(GameTrafficMetrics gameTrafficMetrics) {

        this.gameTrafficMetrics = gameTrafficMetrics; // 保存注入的 gameTrafficMetrics 引用

    }

    /**

     * 启动后每分钟打印一行指标。

     */

    @PostConstruct

    public void start() {

        lastPacketCount = gameTrafficMetrics.getPacketsHandled(); // 基准值

        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {

            Thread t = new Thread(r, "server-metrics-monitor");

            t.setDaemon(true); // 不阻止 JVM 退出

            return t; // 返回 t

        });

        scheduler.scheduleAtFixedRate(this::logMetrics, 60, 60, TimeUnit.SECONDS); // 初始延迟 60s，周期 60s

    }

    /**

     * 进程退出：立即停止调度线程。

     */

    @PreDestroy

    public void stop() {

        if (scheduler != null) { // 条件分支

            scheduler.shutdownNow();

        }

    }

    /**

     * 汇总并打印 heap、线程数与游戏包区间增量。

     */

    private void logMetrics() {

        long total = gameTrafficMetrics.getPacketsHandled();

        long delta = total - lastPacketCount;

        lastPacketCount = total;

        MemoryMXBean mem = ManagementFactory.getMemoryMXBean();

        ThreadMXBean threads = ManagementFactory.getThreadMXBean();

        long heapUsed = mem.getHeapMemoryUsage().getUsed();

        log.info("server metrics: gamePacketsLastInterval={}, gamePacketsTotal={}, heapUsedBytes={}, threadCount={}", // 记录运行日志

                delta, total, heapUsed, threads.getThreadCount());

    }

}

