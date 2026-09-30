package cn.itcast.demo.mylunarcore.scene;

import cn.itcast.demo.mylunarcore.common.AppLogger;
import cn.itcast.demo.mylunarcore.common.LogCategory;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.springframework.stereotype.Component;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AOI 广播异步化：将场景移动同步从 ZoneTick / 业务线程剥离到独立工作队列，
 * 避免同屏人数升高时广播耗时拖垮战斗时钟。
 * <p>
 * 关闭 {@code lunarcore.zone.aoi-async-enabled} 时退化为同步执行，便于对照压测。
 */
@Component
public class AoiAsyncProcessor {

    private static final Logger log = AppLogger.logger(LogCategory.SYSTEM, AoiAsyncProcessor.class);

    private final boolean enabled;
    private final LinkedBlockingQueue<Runnable> queue;
    private final ThreadPoolExecutor executor;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong submitted = new AtomicLong();

    public AoiAsyncProcessor(LunarCoreProperties properties) {
        this.enabled = properties.getZone().isAoiAsyncEnabled();
        int workers = Math.max(1, properties.getZone().getAoiAsyncWorkers());
        int capacity = Math.max(256, properties.getZone().getAoiAsyncQueueCapacity());
        this.queue = new LinkedBlockingQueue<>(capacity);
        ThreadFactory factory = r -> {
            Thread t = new Thread(r, "aoi-async");
            t.setDaemon(true);
            return t;
        };
        this.executor = new ThreadPoolExecutor(
                workers, workers, 60L, TimeUnit.SECONDS, queue, factory,
                (task, pool) -> {
                    dropped.incrementAndGet();
                    if (log.isDebugEnabled()) {
                        log.debug("aoi async queue full, drop broadcast");
                    }
                });
        if (enabled) {
            log.info("AOI async enabled workers={} capacity={}", workers, capacity);
        }
    }

    /** 提交广播任务；未启用时直接在调用线程执行。 */
    public void submit(Runnable task) {
        if (task == null) {
            return;
        }
        if (!enabled || !running.get()) {
            task.run();
            return;
        }
        submitted.incrementAndGet();
        executor.execute(task);
    }

    public long droppedCount() {
        return dropped.get();
    }

    public long submittedCount() {
        return submitted.get();
    }

    public int queueSize() {
        return queue.size();
    }

    public boolean isEnabled() {
        return enabled;
    }

    @PreDestroy
    public void shutdown() {
        running.set(false);
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
