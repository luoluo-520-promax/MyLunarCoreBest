// Netty 游戏业务线程池配置类所在包
package cn.itcast.demo.mylunarcore.config;

// 统一日志工厂：按日志分类创建 Logger
import cn.itcast.demo.mylunarcore.common.AppLogger;
// 日志分类枚举：SYSTEM 表示系统级日志
import cn.itcast.demo.mylunarcore.common.LogCategory;
// Micrometer 指标注册表：用于暴露 Prometheus 等监控指标
import io.micrometer.core.instrument.MeterRegistry;
// Netty 事件执行器组：管理一组处理业务任务的线程
import io.netty.util.concurrent.DefaultEventExecutorGroup;
// Netty 线程工厂：自定义线程名与守护状态
import io.netty.util.concurrent.DefaultThreadFactory;
// Netty 拒绝处理器接口：任务队列满时的兜底回调
import io.netty.util.concurrent.RejectedExecutionHandler;
// Netty 单线程事件执行器：持有待处理任务队列
import io.netty.util.concurrent.SingleThreadEventExecutor;
// SLF4J 日志门面接口
import org.slf4j.Logger;
// 声明 Bean 的方法注解
import org.springframework.context.annotation.Bean;
// 声明本类是 Spring 配置类
import org.springframework.context.annotation.Configuration;

// 反射工具：动态调用 JDK 21 虚拟线程 API（保持源码兼容旧 JDK）
import java.lang.reflect.Method;
// JDK 标准线程池执行器接口
import java.util.concurrent.ExecutorService;
// JDK 线程池工厂方法（创建虚拟线程执行器）
import java.util.concurrent.Executors;
// 任务被拒绝时抛出的异常类型
import java.util.concurrent.RejectedExecutionException;
// 线程工厂接口
import java.util.concurrent.ThreadFactory;
// 时间单位（用于优雅停机超时）
import java.util.concurrent.TimeUnit;
// 原子长整型：线程安全地累计被拒绝任务数
import java.util.concurrent.atomic.AtomicLong;

/**
 * 游戏业务线程池：有界待处理任务 + 拒绝告警，避免无界堆积拖垮 JVM。
 * <p>
 * 可选 {@code lunarcore.netty.virtual-threads-enabled=true}：在 JDK 21+ 上通过反射
 * 创建虚拟线程执行器（源码保持 --release 17 可编译）；IO EventLoop 仍为平台线程。
 * <p>
 * 设计要点：
 * <ul>
 *   <li>与 {@code AssistInferenceConfiguration}（AI 推理池）隔离，业务包与 AI 调用互不挤占；</li>
 *   <li>默认线程数 = max(4, CPU×2)，兼顾低端机与高并发；</li>
 *   <li>任务队列有界（maxPending），超限直接拒绝并告警，避免 OOM。</li>
 * </ul>
 */
@Configuration // 声明为 Spring 配置类
public class NettyGameBusinessConfiguration {

    // 本类专用日志对象
    private static final Logger log = AppLogger.logger(LogCategory.SYSTEM, NettyGameBusinessConfiguration.class);

    // 平台线程业务执行器（volatile：可能被 shutdownBusinessExecutors 从其他线程访问）
    private volatile DefaultEventExecutorGroup gameBusinessExecutorGroup;

    // 可选虚拟线程执行器；未开启/不可用时为 null（volatile 同理）
    private volatile ExecutorService virtualBusinessExecutor;

    // 累计被拒绝的任务总数（线程安全），暴露为监控指标
    private final AtomicLong rejectTotal = new AtomicLong();

    /**
     * 创建游戏业务线程池 Bean。
     * <p>
     * 参数推导规则：
     * <ul>
     *   <li>{@code threads}：取 {@code lunarcore.netty.business-threads}，<=0 时按
     *       max(4, CPU 核心数×2) 自动推导；</li>
     *   <li>{@code maxPending}：待处理任务上限 = max(256, threads×256)，有界队列防 OOM；</li>
     *   <li>{@code destroyMethod = ""}：关闭动作交给 {@link #shutdownBusinessExecutors()}
     *       显式控制（同时收尾虚拟线程池），避免 Spring 只关平台线程池导致虚拟线程泄漏。</li>
     * </ul>
     * 同时把拒绝计数注册为 Micrometer gauge（{@code lunarcore.netty.business_reject_total}），
     * 供 Prometheus 采集判断线程池是否过载。
     */
    @Bean(destroyMethod = "") // 手动管理销毁（见 shutdownBusinessExecutors），避免默认 destroy 冲突
    public DefaultEventExecutorGroup gameBusinessExecutorGroup(LunarCoreProperties properties,
                                                               MeterRegistry meterRegistry) {
        // 读取配置的业务线程数；<=0 表示未配置/使用默认推导值
        int threads = properties.getNetty().getBusinessThreads();
        if (threads <= 0) {
            // 默认：至少 4 个线程，并按 CPU 核心数×2 扩容（常见 IO 密集业务经验值）
            threads = Math.max(4, Runtime.getRuntime().availableProcessors() * 2);
        }
        // 有界队列容量：单线程约 256 个待处理任务的缓冲，总容量随线程数线性增长
        int maxPending = Math.max(256, threads * 256);
        // 暴露拒绝计数指标：每次 rejected() 累加时 gauge 自动反映最新值
        meterRegistry.gauge("lunarcore.netty.business_reject_total", rejectTotal, AtomicLong::get);

        // 自定义拒绝处理器：队列满时累加计数、输出告警，并抛异常让调用方感知过载
        RejectedExecutionHandler rejectedHandler = new RejectedExecutionHandler() {
            @Override
            public void rejected(Runnable task, SingleThreadEventExecutor executor) {
                // 线程安全地累加拒绝次数
                rejectTotal.incrementAndGet();
                // 告警：带出当前待处理任务量与上限，方便判断是否需要扩容
                log.warn("game-business executor rejected task, pendingTasksApprox={}, maxPending={}",
                        executor.pendingTasks(), maxPending);
                // 抛异常中断提交方，避免静默丢任务
                throw new RejectedExecutionException("game-business queue full");
            }
        };

        // 若配置开启虚拟线程且运行环境支持，则额外创建一个虚拟线程执行器
        if (properties.getNetty().isVirtualThreadsEnabled()) {
            // 尝试通过反射创建 JDK 21 虚拟线程执行器（源码仍可按 17 编译）
            virtualBusinessExecutor = tryCreateVirtualThreadExecutor();
            if (virtualBusinessExecutor != null) {
                // 创建成功：旁路执行器用于阻塞型任务，管线分组仍用平台线程
                log.info("Virtual threads enabled for game business (sidecar executor); pipeline group remains platform threads");
            } else {
                // 创建失败：运行环境低于 JDK 21，回退平台线程并告警
                log.warn("lunarcore.netty.virtual-threads-enabled=true but runtime lacks VirtualThreads (need JDK 21+)");
            }
        }

        // 创建平台线程业务执行器组：
        // threads 同时作为核心/最大线程数（不自动扩容）；线程名前缀 game-business
        DefaultEventExecutorGroup group = new DefaultEventExecutorGroup(
                threads,
                new DefaultThreadFactory("game-business", true), // daemon=true：不阻塞 JVM 退出
                maxPending,
                rejectedHandler);
        // 保存引用供 shutdownBusinessExecutors 使用
        this.gameBusinessExecutorGroup = group;
        // 输出启动信息（线程数、队列上限、是否附带虚拟线程执行器）
        log.info("Game business executor started: threads={}, maxPendingTasks={}, virtualThreads={}",
                threads, maxPending, virtualBusinessExecutor != null);
        return group;
    }

    /**
     * 可选虚拟线程执行器；未开启或 JDK&lt;21 时返回 null。
     * <p>
     * 供业务层判断：若返回值非 null，可将 JDBC 等阻塞型任务提交到此执行器，
     * 减少对平台线程池的占用。
     *
     * @return 虚拟线程执行器，或 null（未启用/不支持）
     */
    public ExecutorService virtualBusinessExecutorOrNull() {
        return virtualBusinessExecutor;
    }

    /**
     * 返回累计拒绝任务数，供监控使用。
     *
     * @return 拒绝计数
     */
    public long getRejectTotal() {
        return rejectTotal.get();
    }

    /**
     * 优雅停机：依次关闭平台线程池与虚拟线程池。
     * <p>
     * 单独提供此方法而非依赖 {@code @Bean(destroyMethod)}，是为了同时收尾两个
     * 执行器（虚拟线程池若由 Spring 默认销毁会漏关）。
     */
    public void shutdownBusinessExecutors() {
        // 取本地引用，避免并发下字段被重赋值
        DefaultEventExecutorGroup g = gameBusinessExecutorGroup;
        if (g != null) {
            // shutdownGracefully(0, 5s)：立即停止接收新任务，最多等 5 秒执行完在途任务
            g.shutdownGracefully(0, 5, TimeUnit.SECONDS).syncUninterruptibly();
        }
        // 若创建过虚拟线程执行器则一并关闭
        ExecutorService vt = virtualBusinessExecutor;
        if (vt != null) {
            vt.shutdown();
            try {
                // 最多等待 5 秒让在途任务完成
                vt.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                // 停机线程被中断：恢复中断标志，交由上层决定是否继续等待
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 反射创建 VirtualThreadPerTaskExecutor，避免 --release 17 编译失败。 */
    @SuppressWarnings("unchecked") // factory() 返回 Object，需强转 ThreadFactory（已用 ClassCastException 兜底）
    private static ExecutorService tryCreateVirtualThreadExecutor() {
        try {
            // 1. 获取 Thread.ofVirtual() 静态方法并调用，得到虚拟线程构建器
            Method ofVirtual = Thread.class.getMethod("ofVirtual");
            Object builder = ofVirtual.invoke(null);
            // 2. 调用 builder.name("game-business-vt-", 0L)：给虚拟线程统一命名（起始序号 0）
            Method name = builder.getClass().getMethod("name", String.class, long.class);
            Object named = name.invoke(builder, "game-business-vt-", 0L);
            // 3. 从命名构建器获取线程工厂
            Method factory = named.getClass().getMethod("factory");
            ThreadFactory threadFactory = (ThreadFactory) factory.invoke(named);
            // 4. 用线程工厂创建"每任务一线程"执行器（VirtualThreadPerTaskExecutor）
            Method newExecutor = Executors.class.getMethod("newThreadPerTaskExecutor", ThreadFactory.class);
            return (ExecutorService) newExecutor.invoke(null, threadFactory);
        } catch (ReflectiveOperationException | ClassCastException e) {
            // 反射失败（如 JDK 低于 21、API 变动）时返回 null，由调用方回退平台线程
            return null;
        }
    }
}
