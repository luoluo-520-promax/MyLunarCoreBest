package cn.itcast.demo.mylunarcore.economy.iap;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 渠道验签网关：按 channel 分发到 {@link IapChannelVerifier}；
 * mock-verify=true 时走 Mock；关闭后必须命中已配置的真实渠道。
 */
@Component
public class IapVerifyGateway {

    public record VerifyResult(boolean success, String channelTxId, String message) {
    }

    private final boolean mockEnabled;
    private final Map<String, IapChannelVerifier> verifiersByChannel;
    private final MockIapChannelVerifier mockVerifier;
    private final ObjectProvider<cn.itcast.demo.mylunarcore.common.BusinessMetrics> metricsProvider;

    public IapVerifyGateway(
            @Value("${mylunarcore.iap.mock-verify:true}") boolean mockEnabled,
            List<IapChannelVerifier> verifiers,
            MockIapChannelVerifier mockVerifier,
            ObjectProvider<cn.itcast.demo.mylunarcore.common.BusinessMetrics> metricsProvider) {
        this.mockEnabled = mockEnabled;
        this.mockVerifier = mockVerifier;
        this.metricsProvider = metricsProvider;
        this.verifiersByChannel = verifiers.stream()
                .collect(Collectors.toMap(
                        v -> v.channel().toLowerCase(Locale.ROOT),
                        Function.identity(),
                        (a, b) -> a));
    }

    /** 单测兼容：无指标注入。 */
    public IapVerifyGateway(boolean mockEnabled, List<IapChannelVerifier> verifiers,
                            MockIapChannelVerifier mockVerifier) {
        this(mockEnabled, verifiers, mockVerifier, null);
    }

    public boolean isMockEnabled() {
        return mockEnabled;
    }

    /** 是否存在至少一个已配置的非 mock 渠道。 */
    public boolean hasConfiguredRealChannel() {
        return verifiersByChannel.values().stream()
                .filter(v -> !"mock".equalsIgnoreCase(v.channel()))
                .anyMatch(IapChannelVerifier::isConfigured);
    }

    public VerifyResult verify(String channel, String orderId, String skuId, String receipt) {
        VerifyResult result = doVerify(channel, orderId, skuId, receipt);
        cn.itcast.demo.mylunarcore.common.BusinessMetrics metrics =
                metricsProvider == null ? null : metricsProvider.getIfAvailable();
        if (metrics != null) {
            if (result.success()) {
                metrics.recordIapVerifySuccess();
            } else {
                metrics.recordIapVerifyFailure();
            }
        }
        return result;
    }

    private VerifyResult doVerify(String channel, String orderId, String skuId, String receipt) {
        if (receipt == null || receipt.isBlank()) {
            return new VerifyResult(false, null, "empty_receipt");
        }
        if (mockEnabled) {
            return mockVerifier.verify(orderId, skuId, receipt);
        }
        String key = channel == null || channel.isBlank()
                ? ""
                : channel.trim().toLowerCase(Locale.ROOT);
        if (key.isEmpty() || "mock".equals(key)) {
            return new VerifyResult(false, null, "real_channel_required");
        }
        IapChannelVerifier verifier = verifiersByChannel.get(key);
        if (verifier == null) {
            return new VerifyResult(false, null, "unknown_channel");
        }
        if (!verifier.isConfigured()) {
            return new VerifyResult(false, null, key + "_not_configured");
        }
        return verifier.verify(orderId, skuId, receipt);
    }
}
