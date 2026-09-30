package cn.itcast.demo.mylunarcore.economy.iap;

import cn.itcast.demo.mylunarcore.common.AppLogger;
import cn.itcast.demo.mylunarcore.common.LogCategory;
import org.slf4j.Logger;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * prod：禁止 mock 验签；关闭 mock 时必须至少有一个已配置的真实渠道，否则 fail-fast。
 */
@Component
@Profile("prod")
public class IapProductionGuard {

    private static final Logger log = AppLogger.logger(LogCategory.SYSTEM, IapProductionGuard.class);

    public IapProductionGuard(IapVerifyGateway gateway) {
        if (gateway.isMockEnabled()) {
            String msg = "Production IAP refuse mock-verify=true; set mylunarcore.iap.mock-verify=false "
                    + "and configure apple/google channel credentials";
            log.error(msg);
            throw new IllegalStateException(msg);
        }
        if (!gateway.hasConfiguredRealChannel()) {
            String msg = "Production IAP requires at least one configured real channel "
                    + "(mylunarcore.iap.apple.* or mylunarcore.iap.google.*)";
            log.error(msg);
            throw new IllegalStateException(msg);
        }
        log.info("Production IAP guard passed (mock disabled, real channel configured)");
    }
}
