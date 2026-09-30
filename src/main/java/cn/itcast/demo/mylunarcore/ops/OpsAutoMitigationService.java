package cn.itcast.demo.mylunarcore.ops;

import cn.itcast.demo.mylunarcore.common.BusinessMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 业务异常自动止血：IAP 失败率 / 战斗审计失败率 / AI fallback / 钱包负余额 / SLO 错误预算。
 */
@Service
public class OpsAutoMitigationService {

    private static final Logger log = LoggerFactory.getLogger(OpsAutoMitigationService.class);

    public static final double IAP_FAIL_RATE_CRITICAL = 0.05;
    public static final double AUDIT_FAIL_RATE_CRITICAL = 0.05;
    public static final double AI_FALLBACK_RATE_WARN = 0.05;

    private final BusinessMetrics metrics;
    private final AtomicBoolean highValuePurchaseDisabled = new AtomicBoolean(false);
    private final AtomicBoolean readOnlyMode = new AtomicBoolean(false);
    private final AtomicBoolean aiVisionDisabled = new AtomicBoolean(false);
    private final AtomicBoolean logLevelReduced = new AtomicBoolean(false);

    public OpsAutoMitigationService(BusinessMetrics metrics) {
        this.metrics = metrics;
    }

    public boolean isHighValuePurchaseDisabled() {
        return highValuePurchaseDisabled.get();
    }

    public boolean isReadOnlyMode() {
        return readOnlyMode.get();
    }

    public boolean isAiVisionDisabled() {
        return aiVisionDisabled.get();
    }

    public boolean isLogLevelReduced() {
        return logLevelReduced.get();
    }

    public void forceMitigate(String reason) {
        highValuePurchaseDisabled.set(true);
        aiVisionDisabled.set(true);
        logLevelReduced.set(true);
        log.error("ops_auto_mitigation enabled reason={}", reason);
    }

    public void clearMitigation() {
        highValuePurchaseDisabled.set(false);
        readOnlyMode.set(false);
        aiVisionDisabled.set(false);
        logLevelReduced.set(false);
        log.info("ops_auto_mitigation cleared");
    }

    @Scheduled(fixedDelay = 30_000L)
    public void evaluate() {
        double iapFail = metrics.iapVerifyFailureRate();
        double auditFail = metrics.battleAuditMismatchRate();
        double aiFallback = metrics.aiFallbackRate();
        if (iapFail > IAP_FAIL_RATE_CRITICAL) {
            if (highValuePurchaseDisabled.compareAndSet(false, true)) {
                log.error("ops_auto_mitigation iap_fail_rate={} disable_high_value_purchase", iapFail);
            }
        }
        if (auditFail > AUDIT_FAIL_RATE_CRITICAL) {
            if (readOnlyMode.compareAndSet(false, true)) {
                log.error("ops_auto_mitigation battle_audit_mismatch_rate={} enable_readonly", auditFail);
            }
        }
        // 低级别：AI fallback > 5% → 关闭视觉、降低日志、回滚灰度策略侧效果
        if (aiFallback > AI_FALLBACK_RATE_WARN) {
            if (aiVisionDisabled.compareAndSet(false, true)) {
                log.warn("ops_auto_mitigation ai_fallback_rate={} disable_ai_vision", aiFallback);
            }
            logLevelReduced.set(true);
        }
    }
}
