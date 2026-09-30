package cn.itcast.demo.mylunarcore.player;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 玩家数据加载/落盘失败与拒绝指标，供日志与 Prometheus 采集。
 */
@Component
public class PlayerDataMetrics {

    private final AtomicLong persistFailTotal = new AtomicLong();
    private final AtomicLong persistRejectTotal = new AtomicLong();
    private final AtomicLong reloadFailTotal = new AtomicLong();
    private final AtomicLong reloadRejectTotal = new AtomicLong();
    private final AtomicLong versionConflictTotal = new AtomicLong();

    public PlayerDataMetrics(MeterRegistry meterRegistry) {
        bind(meterRegistry, "lunarcore.player.persist_fail_total", persistFailTotal);
        bind(meterRegistry, "lunarcore.player.persist_reject_total", persistRejectTotal);
        bind(meterRegistry, "lunarcore.player.reload_fail_total", reloadFailTotal);
        bind(meterRegistry, "lunarcore.player.reload_reject_total", reloadRejectTotal);
        bind(meterRegistry, "lunarcore.player.version_conflict_total", versionConflictTotal);
    }

    private static void bind(MeterRegistry registry, String name, AtomicLong value) {
        registry.gauge(name, value, AtomicLong::get);
    }

    public void recordPersistFail() {
        persistFailTotal.incrementAndGet();
    }

    public void recordPersistReject() {
        persistRejectTotal.incrementAndGet();
    }

    public void recordReloadFail() {
        reloadFailTotal.incrementAndGet();
    }

    public void recordReloadReject() {
        reloadRejectTotal.incrementAndGet();
    }

    public void recordVersionConflict() {
        versionConflictTotal.incrementAndGet();
    }

    public long getPersistFailTotal() {
        return persistFailTotal.get();
    }

    public long getPersistRejectTotal() {
        return persistRejectTotal.get();
    }

    public long getReloadFailTotal() {
        return reloadFailTotal.get();
    }

    public long getReloadRejectTotal() {
        return reloadRejectTotal.get();
    }

    public long getVersionConflictTotal() {
        return versionConflictTotal.get();
    }
}
