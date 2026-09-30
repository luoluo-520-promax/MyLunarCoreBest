package cn.itcast.demo.mylunarcore.ops;

import cn.itcast.demo.mylunarcore.common.BusinessMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.YearMonth;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SLO 错误预算：月可用性目标 99.99%；预算消耗过快时降级非核心功能，保障战斗可用。
 */
@Service
public class SloErrorBudgetService {

    private static final Logger log = LoggerFactory.getLogger(SloErrorBudgetService.class);

    /** 99.99% → 每月允许不可用约 0.01% */
    public static final double MONTHLY_AVAILABILITY_TARGET = 0.9999;
    /** 预算消耗超过 50% 触发降级 */
    public static final double BUDGET_BURN_ALERT = 0.5;

    private final BusinessMetrics metrics;
    private final OpsAutoMitigationService mitigation;
    private final AtomicLong successSamples = new AtomicLong();
    private final AtomicLong failSamples = new AtomicLong();
    private final AtomicReference<String> budgetMonth = new AtomicReference<>(YearMonth.now().toString());
    private final AtomicBoolean nonCoreDegraded = new AtomicBoolean(false);

    public SloErrorBudgetService(BusinessMetrics metrics, OpsAutoMitigationService mitigation) {
        this.metrics = metrics;
        this.mitigation = mitigation;
    }

    public void recordCoreSuccess() {
        rollMonthIfNeeded();
        successSamples.incrementAndGet();
    }

    public void recordCoreFailure() {
        rollMonthIfNeeded();
        failSamples.incrementAndGet();
    }

    public double availability() {
        long ok = successSamples.get();
        long fail = failSamples.get();
        long total = ok + fail;
        return total <= 0 ? 1.0 : ok * 1.0 / total;
    }

    /** 已消耗错误预算比例：0=未消耗，1=预算耗尽。 */
    public double budgetConsumedRatio() {
        double allowedFailRate = 1.0 - MONTHLY_AVAILABILITY_TARGET;
        double actualFailRate = 1.0 - availability();
        if (allowedFailRate <= 0) {
            return actualFailRate > 0 ? 1.0 : 0.0;
        }
        return Math.min(1.0, actualFailRate / allowedFailRate);
    }

    public boolean isNonCoreDegraded() {
        return nonCoreDegraded.get();
    }

    @Scheduled(fixedDelay = 60_000L)
    public void evaluate() {
        rollMonthIfNeeded();
        double consumed = budgetConsumedRatio();
        metrics.setSlaBattleSuccessRate(availability());
        if (consumed >= BUDGET_BURN_ALERT) {
            if (nonCoreDegraded.compareAndSet(false, true)) {
                mitigation.forceMitigate("slo_error_budget_burn=" + consumed);
                log.error("slo_error_budget burn={} degrade_non_core ai_vision/log_level", consumed);
            }
        } else if (consumed < BUDGET_BURN_ALERT * 0.5) {
            nonCoreDegraded.compareAndSet(true, false);
        }
    }

    private void rollMonthIfNeeded() {
        String now = YearMonth.now().toString();
        String prev = budgetMonth.get();
        if (!now.equals(prev) && budgetMonth.compareAndSet(prev, now)) {
            successSamples.set(0);
            failSamples.set(0);
            nonCoreDegraded.set(false);
        }
    }

    public Map<String, Object> snapshot() {
        return Map.of(
                "month", budgetMonth.get(),
                "availability", availability(),
                "budgetConsumed", budgetConsumedRatio(),
                "target", MONTHLY_AVAILABILITY_TARGET,
                "nonCoreDegraded", nonCoreDegraded.get(),
                "success", successSamples.get(),
                "fail", failSamples.get());
    }
}
