package cn.itcast.demo.mylunarcore.economy.iap;

import cn.itcast.demo.mylunarcore.common.AppLogger;
import cn.itcast.demo.mylunarcore.common.LogCategory;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动时 ping 已配置的 IAP 渠道沙盒/OAuth；失败则 fail-fast（可关）。
 */
@Component
@Order(50)
public class IapChannelHealthChecker implements ApplicationRunner {

    private static final Logger log = AppLogger.logger(LogCategory.SYSTEM, IapChannelHealthChecker.class);

    private final boolean mockEnabled;
    private final boolean healthCheckEnabled;
    private final boolean failFast;
    private final AppleIapChannelVerifier apple;
    private final GoogleIapChannelVerifier google;

    public IapChannelHealthChecker(
            @Value("${mylunarcore.iap.mock-verify:true}") boolean mockEnabled,
            @Value("${mylunarcore.iap.channel-health-check.enabled:true}") boolean healthCheckEnabled,
            @Value("${mylunarcore.iap.channel-health-check.fail-fast:true}") boolean failFast,
            AppleIapChannelVerifier apple,
            GoogleIapChannelVerifier google) {
        this.mockEnabled = mockEnabled;
        this.healthCheckEnabled = healthCheckEnabled;
        this.failFast = failFast;
        this.apple = apple;
        this.google = google;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (mockEnabled || !healthCheckEnabled) {
            log.info("IAP channel health check skipped (mock={} enabled={})", mockEnabled, healthCheckEnabled);
            return;
        }
        boolean anyConfigured = false;
        boolean anyOk = false;
        if (apple.isConfigured()) {
            anyConfigured = true;
            boolean ok = apple.pingSandbox();
            anyOk = anyOk || ok;
            log.info("IAP apple channel health ping={}", ok ? "ok" : "fail");
            if (!ok && failFast) {
                throw new IllegalStateException("IAP Apple sandbox/prod verify endpoint health check failed");
            }
        }
        if (google.isConfigured()) {
            anyConfigured = true;
            boolean ok = google.pingSandbox();
            anyOk = anyOk || ok;
            log.info("IAP google channel health ping={}", ok ? "ok" : "fail");
            if (!ok && failFast) {
                throw new IllegalStateException("IAP Google OAuth health check failed");
            }
        }
        if (!anyConfigured) {
            log.warn("IAP channel health: no real channel configured (ok for non-prod)");
        } else if (anyOk) {
            log.info("IAP channel health check passed");
        }
    }
}
