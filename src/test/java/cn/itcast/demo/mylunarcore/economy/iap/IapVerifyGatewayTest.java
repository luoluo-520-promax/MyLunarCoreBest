package cn.itcast.demo.mylunarcore.economy.iap;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IapVerifyGateway 验签网关。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code IapVerifyGatewayTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("IapVerifyGateway 验签网关")
class IapVerifyGatewayTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * 验证点：mock=false 时任意收据必须失败（未配置真实渠道）。
     * <p>测试方法 {@code mockOffShouldRejectAnyReceipt}：
     * <ul>
     *   <li>{@code IapVerifyGateway.VerifyResult r = gateway.verify("apple", "oid", "sku", "fake-receipt");}</li>
     *   <li>{@code assertFalse(r.success());}</li>
     *   <li>{@code assertFalse(gateway.verify("mock", "oid", "sku", "fake").success());}</li>
     *   <li>{@code assertFalse(gateway.verify(null, "oid", "sku", "fake").success());}</li>
     * </ul>
     */
    @Test
    @DisplayName("mock=false 时任意收据必须失败（未配置真实渠道）")
    void mockOffShouldRejectAnyReceipt() {
        MockIapChannelVerifier mock = new MockIapChannelVerifier();
        AppleIapChannelVerifier apple = new AppleIapChannelVerifier(false, "", "", true, mapper);
        GoogleIapChannelVerifier google = new GoogleIapChannelVerifier(false, "", "", mapper);
        IapVerifyGateway gateway = new IapVerifyGateway(false, List.of(mock, apple, google), mock);

        IapVerifyGateway.VerifyResult r = gateway.verify("apple", "oid", "sku", "fake-receipt");
        assertFalse(r.success());
        assertFalse(gateway.verify("mock", "oid", "sku", "fake").success());
        assertFalse(gateway.verify(null, "oid", "sku", "fake").success());
    }

    /**
     * 验证点：mock=true 时非空收据可通过。
     * <p>测试方法 {@code mockOnShouldAcceptNonEmptyReceipt}：
     * <ul>
     *   <li>{@code assertTrue(gateway.verify("anything", "oid", "sku", "receipt").success());}</li>
     *   <li>{@code assertFalse(gateway.verify("anything", "oid", "sku", " ").success());}</li>
     * </ul>
     */
    @Test
    @DisplayName("mock=true 时非空收据可通过")
    void mockOnShouldAcceptNonEmptyReceipt() {
        MockIapChannelVerifier mock = new MockIapChannelVerifier();
        IapVerifyGateway gateway = new IapVerifyGateway(true, List.of(mock), mock);
        assertTrue(gateway.verify("anything", "oid", "sku", "receipt").success());
        assertFalse(gateway.verify("anything", "oid", "sku", " ").success());
    }
}
