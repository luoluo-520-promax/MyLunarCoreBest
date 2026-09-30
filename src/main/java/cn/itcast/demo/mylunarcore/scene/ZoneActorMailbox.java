package cn.itcast.demo.mylunarcore.scene;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 轻量 Zone Actor 邮箱：每个 Zone 独占单线程串行处理进出场/坐标更新，
 * 模拟 Actor 模型隔离，避免共享 Map 上的粗粒度锁放大。
 * <p>
 * 不引入 Akka/Quasar 依赖；关闭时调用方可直接同步执行。
 */
public final class ZoneActorMailbox implements AutoCloseable {

    private final ConcurrentHashMap<Integer, ExecutorService> mailboxes = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final boolean enabled;

    public ZoneActorMailbox(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void execute(int zoneId, Runnable task) {
        Objects.requireNonNull(task, "task");
        if (!enabled || closed.get()) {
            task.run();
            return;
        }
        ExecutorService mailbox = mailboxes.computeIfAbsent(zoneId, id ->
                Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "zone-actor-" + id);
                    t.setDaemon(true);
                    return t;
                }));
        try {
            mailbox.execute(task);
        } catch (RejectedExecutionException ex) {
            task.run();
        }
    }

    public void release(int zoneId) {
        ExecutorService mailbox = mailboxes.remove(zoneId);
        if (mailbox != null) {
            mailbox.shutdown();
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (ExecutorService mailbox : mailboxes.values()) {
            mailbox.shutdown();
        }
        for (ExecutorService mailbox : mailboxes.values()) {
            try {
                if (!mailbox.awaitTermination(1, TimeUnit.SECONDS)) {
                    mailbox.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                mailbox.shutdownNow();
            }
        }
        mailboxes.clear();
    }
}
