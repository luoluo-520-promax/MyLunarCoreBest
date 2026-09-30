// AI 推理线程池配置类所在包（gameBusiness 之外的独立执行环境）
package cn.itcast.demo.mylunarcore.config;

// 统一日志工厂：按 LogCategory.SYSTEM 分类输出
import cn.itcast.demo.mylunarcore.common.AppLogger;
// 日志分类枚举：SYSTEM 表示系统级日志
import cn.itcast.demo.mylunarcore.common.LogCategory;
// SLF4J 日志门面接口
import org.slf4j.Logger;
// 声明返回 ExecutorService Bean
import org.springframework.context.annotation.Bean;
// 声明本类是 Spring 配置类
import org.springframework.context.annotation.Configuration;

// 线程池执行器接口
import java.util.concurrent.ExecutorService;
// 有界任务队列：容量写死，防止无限堆积
import java.util.concurrent.LinkedBlockingQueue;
// 拒绝任务时抛出的异常类型
import java.util.concurrent.RejectedExecutionException;
// 线程工厂接口：自定义线程命名与守护状态
import java.util.concurrent.ThreadFactory;
// JDK 标准线程池实现
import java.util.concurrent.ThreadPoolExecutor;
// 时间单位：用于 keepAlive 超时参数
import java.util.concurrent.TimeUnit;
// 原子递增计数器：生成唯一线程序号（线程安全）
import java.util.concurrent.atomic.AtomicInteger;
// 原子长整型计数器：累计被拒绝任务数（线程安全）
import java.util.concurrent.atomic.AtomicLong;

/**
 * AI 推理专用线程池：与 gameBusiness 隔离，避免 LLM/远程 HTTP 占满玩法业务线程。
 * <p>
 * 背景：AI 助手（方案 B）的 LLM 调用与旁路 ai-assist-service 的远程 HTTP 请求
 * 属于慢 I/O 阻塞型任务，若直接跑在游戏业务线程池上，会挤占战斗、场景等核心玩法
 * 的响应资源。本配置类单独创建一套有界线程池，任务超限时直接拒绝并降级为规则教练答案。
 */
@Configuration // 声明为 Spring 配置类，由容器扫描并创建其中的 @Bean
public class AssistInferenceConfiguration {

    // 本类专用日志对象：归类到 SYSTEM 分类，便于日志系统按类别过滤
    private static final Logger log = AppLogger.logger(LogCategory.SYSTEM, AssistInferenceConfiguration.class);

    // 累计被拒绝的任务总数（线程安全），供监控/自愈判断是否出现推理过载
    private final AtomicLong rejectTotal = new AtomicLong();

    /**
     * 创建推理线程池 Bean。
     * <p>
     * 参数解释：
     * <ul>
     *   <li>{@code threads}（核心=最大线程数）：取配置 {@code lunarcore.ai-assist.inference-threads}，
     *       若 <=0 则按 max(2, CPU 核心数) 兜底，避免小机型上配置遗漏导致线程过少；</li>
     *   <li>{@code queueCapacity}：配置 {@code lunarcore.ai-assist.inference-queue-capacity}，
     *       下限 16，防止配成 0/负数导致任务全部被拒；</li>
     *   <li>{@code destroyMethod = "shutdown"}：容器销毁时自动调用 shutdown 平滑停止线程池。</li>
     * </ul>
     * 拒绝策略：任务满时抛出 {@link RejectedExecutionException}，由上层调用方捕获后降级
     * 为规则教练（方案 A）答案，而不是阻塞提交线程，保证请求方不被长时间挂起。
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService assistInferenceExecutor(LunarCoreProperties properties) {
        // 取出 yml 中 lunarcore.ai-assist.* 配置段（null 安全由 LunarCoreProperties 默认值兜底）
        LunarCoreProperties.AiAssistProperties cfg = properties.getAiAssist();
        int threads = cfg.getInferenceThreads();
        if (threads <= 0) {
            // 配置未填或非法时：至少 2 个线程，且不少于 CPU 核心数，兼顾低端机
            threads = Math.max(2, Runtime.getRuntime().availableProcessors());
        }
        int queueCapacity = Math.max(16, Math.min(100, cfg.getInferenceQueueCapacity()));
        ThreadFactory factory = new ThreadFactory() {
            // 线程序号从 1 递增，保证线程名唯一
            private final AtomicInteger seq = new AtomicInteger();

            @Override
            public Thread newThread(Runnable r) {
                // 线程名形如 assist-inference-1，方便 jstack/监控按前缀聚合定位
                Thread t = new Thread(r, "assist-inference-" + seq.incrementAndGet());
                // 设为守护线程：主进程退出时不会因推理线程阻塞停机
                t.setDaemon(true);
                return t;
            }
        };
        // corePoolSize == maxPoolSize：避免扩容/缩容抖动，线程数恒定
        // keepAliveTime 60s：空闲线程回收，降低空转成本
        // LinkedBlockingQueue 有界队列：防止任务无限堆积导致 OOM
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                threads,
                threads,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                factory,
                (r, ex) -> {
                    // 拒绝处理器：累加拒绝计数并告警，随后抛出异常让调用方降级
                    rejectTotal.incrementAndGet();
                    log.warn("assist-inference executor rejected, queueSize={}, rejectTotal={}",
                            ex.getQueue().size(), rejectTotal.get());
                    throw new RejectedExecutionException("assist-inference queue full");
                });
        log.info("Assist inference executor started: threads={}, queueCapacity={}", threads, queueCapacity);
        return executor;
    }

    /**
     * 对外暴露累计拒绝次数，供监控（如 Prometheus gauge）采集，判断推理链路是否过载。
     */
    public long getRejectTotal() {
        return rejectTotal.get();
    }
}
