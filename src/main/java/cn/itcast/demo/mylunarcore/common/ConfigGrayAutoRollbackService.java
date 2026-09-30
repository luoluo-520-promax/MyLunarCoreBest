package cn.itcast.demo.mylunarcore.common;

import cn.itcast.demo.mylunarcore.common.ConfigGrayRelease;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 配置灰度自动回滚：推送后监控错误率，飙升则切回稳定策略。
 */
@Service
public class ConfigGrayAutoRollbackService {

    public static final double ERROR_RATE_THRESHOLD = 0.08;

    private final ConfigGrayRelease grayRelease;
    private final BusinessMetrics metrics;
    private final AtomicLong grayErrors = new AtomicLong();
    private final AtomicLong grayTotal = new AtomicLong();
    private volatile ConfigGrayRelease.GrayPolicy lastStable = ConfigGrayRelease.GrayPolicy.disabled();
    private volatile boolean rolledBack;

    public ConfigGrayAutoRollbackService(ConfigGrayRelease grayRelease,
                                         ObjectProvider<BusinessMetrics> metricsProvider) {
        this.grayRelease = grayRelease;
        this.metrics = metricsProvider == null ? null : metricsProvider.getIfAvailable();
        this.lastStable = grayRelease.current();
    }

    public void snapshotStable() {
        lastStable = grayRelease.current();
        rolledBack = false;
        grayErrors.set(0);
        grayTotal.set(0);
    }

    public void recordRequest(boolean error) {
        grayTotal.incrementAndGet();
        if (error) {
            grayErrors.incrementAndGet();
        }
        evaluate();
    }

    public boolean evaluate() {
        long total = grayTotal.get();
        if (total < 50) {
            return false;
        }
        double rate = grayErrors.get() / (double) total;
        if (rate >= ERROR_RATE_THRESHOLD && grayRelease.current().enabled()) {
            grayRelease.updatePolicy(lastStable);
            rolledBack = true;
            return true;
        }
        // 亦可结合 BusinessMetrics 中 gacha 失败计数启发式
        if (metrics != null && grayRelease.current().enabled() && grayErrors.get() > 20) {
            grayRelease.updatePolicy(lastStable);
            rolledBack = true;
            return true;
        }
        return false;
    }

    public boolean isRolledBack() {
        return rolledBack;
    }

    public double currentErrorRate() {
        long total = grayTotal.get();
        return total == 0 ? 0 : grayErrors.get() / (double) total;
    }
}
