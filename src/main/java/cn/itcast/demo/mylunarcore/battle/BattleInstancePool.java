package cn.itcast.demo.mylunarcore.battle;

import cn.itcast.demo.mylunarcore.common.AppLogger;
import cn.itcast.demo.mylunarcore.common.LogCategory;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import org.slf4j.Logger;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * 战斗实例配额与优先级调度池。
 * <p>
 * 限制同时活跃战局数；超出时排队或拒绝。每帧按优先级分配 CPU 时间片：
 * 玩家主动操作 &gt; 在线 Auto &gt; 掉线托管。
 */
@Component
public class BattleInstancePool {

    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_BATTLE, BattleInstancePool.class);

    public enum Priority {
        PLAYER_ACTIVE(0),
        ONLINE_AUTO(1),
        OFFLINE_HOSTED(2);

        private final int rank;

        Priority(int rank) {
            this.rank = rank;
        }

        public int rank() {
            return rank;
        }
    }

    public enum AdmitResult {
        ADMITTED,
        QUEUED,
        REJECTED
    }

    public record AdmitOutcome(AdmitResult result, int queueDepth) {
    }

    private final LunarCoreProperties properties;
    private final AtomicInteger activeSlots = new AtomicInteger();
    private final ConcurrentHashMap<Long, Priority> priorities = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> lastTickMs = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<Long> waitQueue = new ConcurrentLinkedQueue<>();
    private final LongAdder rejectedTotal = new LongAdder();
    private final LongAdder queuedTotal = new LongAdder();
    private final LongAdder throttledTicks = new LongAdder();

    public BattleInstancePool(LunarCoreProperties properties) {
        this.properties = properties;
    }

    public int maxActive() {
        return Math.max(1, properties.getGameLoop().getMaxActiveBattles());
    }

    public int tickBudgetMs() {
        return Math.max(5, properties.getGameLoop().getBattleTickBudgetMs());
    }

    /** 尝试占用一个活跃槽位；满则按策略排队或拒绝。 */
    public AdmitOutcome tryAdmit(long battleId, Priority priority) {
        Priority p = priority == null ? Priority.PLAYER_ACTIVE : priority;
        int max = maxActive();
        while (true) {
            int cur = activeSlots.get();
            if (cur < max) {
                if (activeSlots.compareAndSet(cur, cur + 1)) {
                    priorities.put(battleId, p);
                    lastTickMs.put(battleId, 0L);
                    drainQueueIfPossible();
                    return new AdmitOutcome(AdmitResult.ADMITTED, waitQueue.size());
                }
                continue;
            }
            break;
        }
        if (properties.getGameLoop().isBattleQueueEnabled()) {
            waitQueue.offer(battleId);
            priorities.put(battleId, p);
            queuedTotal.increment();
            log.info("battle_pool queued battleId={} depth={} max={}", battleId, waitQueue.size(), max);
            return new AdmitOutcome(AdmitResult.QUEUED, waitQueue.size());
        }
        rejectedTotal.increment();
        log.warn("battle_pool rejected battleId={} active={} max={}", battleId, activeSlots.get(), max);
        return new AdmitOutcome(AdmitResult.REJECTED, waitQueue.size());
    }

    /** 当前使用率 0–1，供 HPA / BusinessMetrics 即时读取。 */
    public double usageRatio() {
        return activeSlots.get() * 1.0 / Math.max(1, maxActive());
    }

    public void release(long battleId) {
        if (priorities.remove(battleId) != null) {
            activeSlots.updateAndGet(v -> Math.max(0, v - 1));
        }
        lastTickMs.remove(battleId);
        waitQueue.remove(battleId);
        drainQueueIfPossible();
    }

    public void updatePriority(long battleId, Priority priority) {
        if (priority != null && priorities.containsKey(battleId)) {
            priorities.put(battleId, priority);
        }
    }

    public Priority priorityOf(long battleId) {
        return priorities.getOrDefault(battleId, Priority.PLAYER_ACTIVE);
    }

    public boolean isQueued(long battleId) {
        return waitQueue.contains(battleId);
    }

    public boolean isAdmitted(long battleId) {
        return priorities.containsKey(battleId) && !isQueued(battleId);
    }

    /**
     * 按优先级挑选本帧应推进的战局，并在预算内回调。
     * 低优先级战局可被跳过（降频），玩家主动战局优先。
     */
    public void dispatchTick(List<BattleContext> candidates, long nowMs, Consumer<BattleContext> tickFn) {
        if (candidates == null || candidates.isEmpty() || tickFn == null) {
            return;
        }
        List<BattleContext> ordered = new ArrayList<>(candidates.size());
        for (BattleContext ctx : candidates) {
            if (ctx == null || ctx.isEnded()) {
                continue;
            }
            if (isQueued(ctx.getBattleId())) {
                continue;
            }
            ordered.add(ctx);
        }
        ordered.sort(Comparator
                .comparingInt((BattleContext c) -> priorityOf(c.getBattleId()).rank())
                .thenComparingLong(c -> lastTickMs.getOrDefault(c.getBattleId(), 0L)));

        long deadline = nowMs + tickBudgetMs();
        for (BattleContext ctx : ordered) {
            if (System.currentTimeMillis() >= deadline) {
                throttledTicks.increment();
                break;
            }
            Priority p = priorityOf(ctx.getBattleId());
            long last = lastTickMs.getOrDefault(ctx.getBattleId(), 0L);
            long minInterval = minTickIntervalMs(p);
            if (last > 0 && nowMs - last < minInterval) {
                continue;
            }
            try {
                tickFn.accept(ctx);
            } catch (Exception e) {
                log.warn("battle_pool tick failed battleId={}: {}", ctx.getBattleId(), e.toString());
            }
            lastTickMs.put(ctx.getBattleId(), nowMs);
        }
    }

    private long minTickIntervalMs(Priority p) {
        return switch (p) {
            case PLAYER_ACTIVE -> 0L;
            case ONLINE_AUTO -> properties.getGameLoop().getAutoBattleTickIntervalMs();
            case OFFLINE_HOSTED -> properties.getGameLoop().getHostedBattleTickIntervalMs();
        };
    }

    private void drainQueueIfPossible() {
        int max = maxActive();
        while (activeSlots.get() < max) {
            Long next = waitQueue.poll();
            if (next == null) {
                return;
            }
            int cur = activeSlots.get();
            if (cur >= max) {
                waitQueue.offer(next);
                return;
            }
            if (activeSlots.compareAndSet(cur, cur + 1)) {
                log.info("battle_pool dequeued battleId={} active={}", next, activeSlots.get());
            } else {
                waitQueue.offer(next);
                return;
            }
        }
    }

    public int activeCount() {
        return activeSlots.get();
    }

    public int queueDepth() {
        return waitQueue.size();
    }

    public long rejectedTotal() {
        return rejectedTotal.sum();
    }

    public long queuedTotal() {
        return queuedTotal.sum();
    }

    public long throttledTicks() {
        return throttledTicks.sum();
    }

    @PreDestroy
    public void shutdown() {
        waitQueue.clear();
        priorities.clear();
        lastTickMs.clear();
        activeSlots.set(0);
    }
}
