package cn.itcast.demo.mylunarcore.common;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import org.slf4j.Logger;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Scene / Battle 时钟隔离线程池：避免 Zone AOI 挤占 BattleTick。
 * <ul>
 *   <li>sceneTickExecutor：Scene-IO（Zone 刷新、AOI）</li>
 *   <li>battleTickExecutor：Battle-CPU（战局推进、TTL）</li>
 * </ul>
 */
@Configuration
public class TickSchedulerConfiguration {

    private static final Logger log = AppLogger.logger(LogCategory.SYSTEM, TickSchedulerConfiguration.class);

    private final AtomicLong battleRejectTotal = new AtomicLong();
    private final AtomicLong sceneRejectTotal = new AtomicLong();

    @Bean(destroyMethod = "shutdown")
    public ScheduledExecutorService sceneTickExecutor(LunarCoreProperties properties) {
        int threads = Math.max(2, properties.getGameLoop().getSceneTickThreads());
        ScheduledThreadPoolExecutor ex = new ScheduledThreadPoolExecutor(threads, namedFactory("scene-tick"));
        ex.setRemoveOnCancelPolicy(true);
        ex.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        log.info("Scene tick executor started: threads={}", threads);
        return ex;
    }

    @Bean(destroyMethod = "shutdown")
    public ScheduledExecutorService battleTickExecutor(LunarCoreProperties properties) {
        int threads = Math.max(2, properties.getGameLoop().getBattleTickThreads());
        // 提高 Battle 池优先级，减少与 Scene 抢核时的抖动
        ThreadFactory factory = r -> {
            Thread t = namedFactory("battle-tick").newThread(r);
            t.setPriority(Thread.NORM_PRIORITY + 1);
            return t;
        };
        ScheduledThreadPoolExecutor ex = new ScheduledThreadPoolExecutor(threads, factory);
        ex.setRemoveOnCancelPolicy(true);
        ex.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        log.info("Battle tick executor started: threads={}, priority=NORM+1", threads);
        return ex;
    }

    /**
     * Battle 任务投递队列（有界），用于观测积压时延；满则拒绝并计入指标。
     */
    @Bean(destroyMethod = "shutdown")
    public ExecutorService battleCpuWorkExecutor(LunarCoreProperties properties) {
        int threads = Math.max(2, properties.getGameLoop().getBattleTickThreads());
        int queue = Math.max(64, properties.getGameLoop().getBattleWorkQueueCapacity());
        ThreadPoolExecutor ex = new ThreadPoolExecutor(
                threads, threads, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queue),
                namedFactory("battle-cpu"),
                (r, e) -> {
                    battleRejectTotal.incrementAndGet();
                    throw new RejectedExecutionException("battle-cpu queue full");
                });
        return ex;
    }

    public long getBattleRejectTotal() {
        return battleRejectTotal.get();
    }

    public long getSceneRejectTotal() {
        return sceneRejectTotal.get();
    }

    private static ThreadFactory namedFactory(String prefix) {
        AtomicInteger seq = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + seq.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
