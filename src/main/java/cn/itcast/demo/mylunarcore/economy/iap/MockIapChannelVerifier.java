package cn.itcast.demo.mylunarcore.economy.iap;

import org.springframework.stereotype.Component;

/**
 * 开发用 Mock 验签：非空收据即通过。仅当 {@code mylunarcore.iap.mock-verify=true} 时由网关启用。
 */
@Component
public class MockIapChannelVerifier implements IapChannelVerifier {

    @Override
    public String channel() {
        return "mock";
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public IapVerifyGateway.VerifyResult verify(String orderId, String skuId, String receipt) {
        if (receipt == null || receipt.isBlank()) {
            return new IapVerifyGateway.VerifyResult(false, null, "empty_receipt");
        }
        return new IapVerifyGateway.VerifyResult(true, "mock-tx:" + orderId, "mock_ok");
    }
}
