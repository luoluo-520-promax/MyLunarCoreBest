package cn.itcast.demo.mylunarcore.economy.iap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Google Play 收据验签：Android Publisher API purchases.products.get。
 * 服务账号 JSON 需含 client_email / private_key；包名与 SKU 必须匹配。
 */
@Component
public class GoogleIapChannelVerifier implements IapChannelVerifier {

    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String SCOPE = "https://www.googleapis.com/auth/androidpublisher";
    private static final String API_BASE = "https://androidpublisher.googleapis.com/androidpublisher/v3";

    private final boolean enabled;
    private final String packageName;
    private final String serviceAccountJson;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public GoogleIapChannelVerifier(
            @Value("${mylunarcore.iap.google.enabled:false}") boolean enabled,
            @Value("${mylunarcore.iap.google.package-name:}") String packageName,
            @Value("${mylunarcore.iap.google.service-account-json:}") String serviceAccountJson,
            ObjectMapper objectMapper) {
        this.enabled = enabled;
        this.packageName = packageName == null ? "" : packageName.trim();
        this.serviceAccountJson = serviceAccountJson == null ? "" : serviceAccountJson.trim();
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public String channel() {
        return "google";
    }

    @Override
    public boolean isConfigured() {
        return enabled && !packageName.isBlank() && !serviceAccountJson.isBlank() && parseSa() != null;
    }

    /** 启动健康检查：用服务账号换取 access_token（不调用计费接口）。 */
    public boolean pingSandbox() {
        if (!isConfigured()) {
            return false;
        }
        try {
            return fetchAccessToken() != null;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public IapVerifyGateway.VerifyResult verify(String orderId, String skuId, String receipt) {
        if (!isConfigured()) {
            return new IapVerifyGateway.VerifyResult(false, null, "google_not_configured");
        }
        if (receipt == null || receipt.isBlank()) {
            return new IapVerifyGateway.VerifyResult(false, null, "empty_receipt");
        }
        try {
            String purchaseToken;
            String productId = skuId;
            String trimmed = receipt.trim();
            if (trimmed.startsWith("{")) {
                JsonNode node = objectMapper.readTree(trimmed);
                purchaseToken = text(node, "purchaseToken", "token");
                String pid = text(node, "productId", "skuId");
                if (!pid.isBlank()) {
                    productId = pid;
                }
            } else {
                purchaseToken = trimmed;
            }
            if (purchaseToken.isBlank()) {
                return new IapVerifyGateway.VerifyResult(false, null, "google_missing_purchase_token");
            }
            if (productId == null || productId.isBlank()) {
                return new IapVerifyGateway.VerifyResult(false, null, "google_missing_sku");
            }
            String accessToken = fetchAccessToken();
            if (accessToken == null) {
                return new IapVerifyGateway.VerifyResult(false, null, "google_oauth_failed");
            }
            String url = API_BASE + "/applications/"
                    + URLEncoder.encode(packageName, StandardCharsets.UTF_8)
                    + "/purchases/products/"
                    + URLEncoder.encode(productId, StandardCharsets.UTF_8)
                    + "/tokens/"
                    + URLEncoder.encode(purchaseToken, StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + accessToken)
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 404) {
                return new IapVerifyGateway.VerifyResult(false, null, "google_purchase_not_found");
            }
            if (resp.statusCode() == 401 || resp.statusCode() == 403) {
                return new IapVerifyGateway.VerifyResult(false, null, "google_auth_forbidden");
            }
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                return new IapVerifyGateway.VerifyResult(false, null, "google_http_" + resp.statusCode());
            }
            JsonNode body = objectMapper.readTree(resp.body());
            int purchaseState = body.path("purchaseState").asInt(-1);
            // 0 = purchased
            if (purchaseState != 0) {
                return new IapVerifyGateway.VerifyResult(false, null, "google_purchase_state_" + purchaseState);
            }
            String orderIdGoogle = body.path("orderId").asText("");
            String tx = !orderIdGoogle.isBlank() ? orderIdGoogle : ("google:" + purchaseToken);
            return new IapVerifyGateway.VerifyResult(true, tx, "ok");
        } catch (Exception e) {
            return new IapVerifyGateway.VerifyResult(false, null, "google_verify_error");
        }
    }

    private String fetchAccessToken() throws Exception {
        JsonNode sa = parseSa();
        if (sa == null) {
            return null;
        }
        String email = sa.path("client_email").asText("");
        String pkPem = sa.path("private_key").asText("");
        if (email.isBlank() || pkPem.isBlank()) {
            return null;
        }
        long now = System.currentTimeMillis() / 1000L;
        String header = base64Url("{\"alg\":\"RS256\",\"typ\":\"JWT\"}");
        String claim = base64Url(objectMapper.writeValueAsString(Map.of(
                "iss", email,
                "scope", SCOPE,
                "aud", TOKEN_URL,
                "iat", now,
                "exp", now + 3600
        )));
        String unsigned = header + "." + claim;
        byte[] sig = signRs256(unsigned.getBytes(StandardCharsets.UTF_8), pkPem);
        String jwt = unsigned + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
        String form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8)
                + "&assertion=" + URLEncoder.encode(jwt, StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder(URI.create(TOKEN_URL))
                .timeout(Duration.ofSeconds(8))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            return null;
        }
        return objectMapper.readTree(resp.body()).path("access_token").asText(null);
    }

    private JsonNode parseSa() {
        try {
            if (serviceAccountJson.isBlank()) {
                return null;
            }
            return objectMapper.readTree(serviceAccountJson);
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node, String... keys) {
        for (String k : keys) {
            String v = node.path(k).asText("");
            if (!v.isBlank()) {
                return v;
            }
        }
        return "";
    }

    private static String base64Url(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] signRs256(byte[] data, String pem) throws Exception {
        String normalized = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        byte[] der = Base64.getDecoder().decode(normalized);
        PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(key);
        signature.update(data);
        return signature.sign();
    }
}
