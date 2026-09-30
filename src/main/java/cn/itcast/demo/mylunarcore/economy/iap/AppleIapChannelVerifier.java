package cn.itcast.demo.mylunarcore.economy.iap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * App Store 收据验签：调用 Apple verifyReceipt（生产 / 沙盒自动回退 21007）。
 */
@Component
public class AppleIapChannelVerifier implements IapChannelVerifier {

    public static final String PROD_VERIFY_URL = "https://buy.itunes.apple.com/verifyReceipt";
    public static final String SANDBOX_VERIFY_URL = "https://sandbox.itunes.apple.com/verifyReceipt";

    private final String sharedSecret;
    private final String bundleId;
    private final boolean useSandbox;
    private final boolean enabled;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public AppleIapChannelVerifier(
            @Value("${mylunarcore.iap.apple.enabled:false}") boolean enabled,
            @Value("${mylunarcore.iap.apple.shared-secret:}") String sharedSecret,
            @Value("${mylunarcore.iap.apple.bundle-id:}") String bundleId,
            @Value("${mylunarcore.iap.apple.use-sandbox:true}") boolean useSandbox,
            ObjectMapper objectMapper) {
        this.enabled = enabled;
        this.sharedSecret = sharedSecret == null ? "" : sharedSecret.trim();
        this.bundleId = bundleId == null ? "" : bundleId.trim();
        this.useSandbox = useSandbox;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public String channel() {
        return "apple";
    }

    @Override
    public boolean isConfigured() {
        return enabled && !sharedSecret.isBlank();
    }

    /** 启动健康检查：对沙盒/生产验签端点发最小 POST，网络可达即通过。 */
    public boolean pingSandbox() {
        if (!isConfigured()) {
            return false;
        }
        try {
            String url = useSandbox ? SANDBOX_VERIFY_URL : PROD_VERIFY_URL;
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("receipt-data", Base64.getEncoder().encodeToString("health-ping".getBytes(StandardCharsets.UTF_8)));
            body.put("password", sharedSecret);
            body.put("exclude-old-transactions", true);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            // 21002/21003 等业务错误也说明端点可达；仅 HTTP/网络失败算不健康
            return resp.statusCode() >= 200 && resp.statusCode() < 500;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public IapVerifyGateway.VerifyResult verify(String orderId, String skuId, String receipt) {
        if (!isConfigured()) {
            return new IapVerifyGateway.VerifyResult(false, null, "apple_not_configured");
        }
        if (receipt == null || receipt.isBlank()) {
            return new IapVerifyGateway.VerifyResult(false, null, "empty_receipt");
        }
        try {
            String firstUrl = useSandbox ? SANDBOX_VERIFY_URL : PROD_VERIFY_URL;
            JsonNode root = postVerify(firstUrl, receipt);
            int status = root.path("status").asInt(-1);
            if (status == 21007 && !useSandbox) {
                root = postVerify(SANDBOX_VERIFY_URL, receipt);
                status = root.path("status").asInt(-1);
            } else if (status == 21008 && useSandbox) {
                root = postVerify(PROD_VERIFY_URL, receipt);
                status = root.path("status").asInt(-1);
            }
            if (status != 0) {
                return new IapVerifyGateway.VerifyResult(false, null, "apple_status_" + status);
            }
            JsonNode receiptNode = root.path("receipt");
            if (!bundleId.isBlank()) {
                String bid = receiptNode.path("bundle_id").asText("");
                if (!bundleId.equals(bid)) {
                    return new IapVerifyGateway.VerifyResult(false, null, "apple_bundle_mismatch");
                }
            }
            JsonNode inApps = receiptNode.path("in_app");
            String txId = null;
            boolean skuMatched = skuId == null || skuId.isBlank();
            if (inApps.isArray()) {
                for (JsonNode item : inApps) {
                    String pid = item.path("product_id").asText("");
                    String tid = item.path("transaction_id").asText("");
                    if (!tid.isBlank()) {
                        txId = tid;
                    }
                    if (skuId != null && !skuId.isBlank() && skuId.equals(pid)) {
                        skuMatched = true;
                        txId = tid;
                        break;
                    }
                }
            }
            if (!skuMatched) {
                return new IapVerifyGateway.VerifyResult(false, null, "apple_sku_mismatch");
            }
            if (txId == null || txId.isBlank()) {
                txId = "apple:" + orderId;
            }
            return new IapVerifyGateway.VerifyResult(true, txId, "ok");
        } catch (Exception e) {
            return new IapVerifyGateway.VerifyResult(false, null, "apple_verify_error");
        }
    }

    private JsonNode postVerify(String url, String receipt) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("receipt-data", receipt.trim());
        body.put("password", sharedSecret);
        body.put("exclude-old-transactions", true);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IllegalStateException("http_" + resp.statusCode());
        }
        return objectMapper.readTree(resp.body());
    }
}
