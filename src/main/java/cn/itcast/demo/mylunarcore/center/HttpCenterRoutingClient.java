package cn.itcast.demo.mylunarcore.center;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * 通过 HTTP 查询远端中心服迁移计划。
 * <p>
 * 约定接口：{@code GET {baseUrl}/internal/center/plan?planeId=&floorId=}，
 * 响应 JSON：{@code zoneId,planeId,floorId,nodeId}。
 */
@Component
@ConditionalOnProperty(prefix = "lunarcore.center", name = "mode", havingValue = "remote")
public class HttpCenterRoutingClient implements CenterRoutingClient {

    private final String baseUrl;
    private final String internalToken;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public HttpCenterRoutingClient(LunarCoreProperties properties, ObjectMapper objectMapper) {
        String configured = properties.getCenter().getRemoteBaseUrl();
        this.baseUrl = configured == null ? "" : configured.trim().replaceAll("/+$", "");
        this.internalToken = properties.getInternalApiToken() == null ? "" : properties.getInternalApiToken();
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .build();
    }

    @Override
    public CenterServer.MigrationPlan planMigration(int targetPlaneId, int targetFloorId) {
        if (baseUrl.isBlank()) {
            throw new IllegalStateException("lunarcore.center.remote-base-url is required when mode=remote");
        }
        if (internalToken.isBlank()) {
            throw new IllegalStateException("lunarcore.internal-api-token is required when mode=remote");
        }
        try {
            URI uri = URI.create(baseUrl + "/internal/center/plan?planeId=" + targetPlaneId
                    + "&floorId=" + targetFloorId);
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(5))
                    .header("X-Internal-Token", internalToken)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("remote center HTTP " + response.statusCode() + ": " + response.body());
            }
            JsonNode root = objectMapper.readTree(response.body());
            return new CenterServer.MigrationPlan(
                    root.path("zoneId").asInt(),
                    root.path("planeId").asInt(targetPlaneId),
                    root.path("floorId").asInt(targetFloorId),
                    root.path("nodeId").asText("unknown"),
                    root.path("nodeHost").asText(""),
                    root.path("nodePort").asInt(0));
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("remote center planMigration failed: " + e.getMessage(), e);
        }
    }
}
