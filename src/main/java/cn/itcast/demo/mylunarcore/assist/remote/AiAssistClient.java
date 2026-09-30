package cn.itcast.demo.mylunarcore.assist.remote; // 远程 AI 辅助客户端所在包

import cn.itcast.demo.mylunarcore.common.AppLogger; // 使用统一业务日志入口
import cn.itcast.demo.mylunarcore.common.LogCategory; // 选择助手机业务日志分类
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties; // 读取远程 AI 配置
import com.fasterxml.jackson.databind.JsonNode; // 解析响应 JSON
import com.fasterxml.jackson.databind.ObjectMapper; // 编解码远程请求和响应
import org.slf4j.Logger; // 记录远程调用状态
import org.springframework.stereotype.Component; // 注册为组件

import java.net.URI; // 构造远程接口地址
import java.net.http.HttpClient; // 发送 HTTP 请求
import java.net.http.HttpRequest; // 构造请求体
import java.net.http.HttpResponse; // 接收响应体
import java.nio.charset.StandardCharsets; // 以 UTF-8 发送 JSON
import java.time.Duration; // 配置网络超时
import java.util.Optional; // 表示是否拿到有效响应
import java.util.concurrent.atomic.AtomicLong; // 统计成功、失败和延迟

/**
 * 远程 AI 辅助客户端（含超时快速失败 + 本地熔断）。
 */
@Component
public class AiAssistClient {
    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_ASSIST, AiAssistClient.class);
    private final LunarCoreProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final AssistRemoteCircuitBreaker circuitBreaker;
    private final AtomicLong successCount = new AtomicLong();
    private final AtomicLong errorCount = new AtomicLong();
    private final AtomicLong circuitRejectCount = new AtomicLong();
    private final AtomicLong totalLatencyMs = new AtomicLong();

    public AiAssistClient(LunarCoreProperties properties) {
        this.properties = properties;
        this.circuitBreaker = new AssistRemoteCircuitBreaker(properties);
    }

    public boolean isRemoteEnabled() {
        LunarCoreProperties.AiAssistProperties cfg = properties.getAiAssist();
        return cfg.isRemoteEnabled() && cfg.getRemoteBaseUrl() != null && !cfg.getRemoteBaseUrl().isBlank();
    }

    public Optional<RemoteAssistAskResponse> ask(RemoteAssistAskRequest request) {
        if (!isRemoteEnabled()) {
            return Optional.empty();
        }
        if (!circuitBreaker.allowRequest()) {
            circuitRejectCount.incrementAndGet();
            log.debug("AiAssistClient circuit OPEN, skip remote");
            return Optional.empty();
        }
        String base = properties.getAiAssist().getRemoteBaseUrl().trim().replaceAll("/+$", "");
        long started = System.currentTimeMillis();
        try {
            String body = objectMapper.writeValueAsString(request);
            long timeoutMs = Math.max(500L, Math.min(3000L, properties.getAiAssist().getRemoteTimeoutMs()));
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/internal/ai/ask"))
                    .timeout(Duration.ofMillis(timeoutMs))
                    .header("Content-Type", "application/json")
                    .header("X-Internal-Token", nullToEmpty(properties.getAiAssist().getRemoteInternalToken()))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            String traceId = cn.itcast.demo.mylunarcore.net.NetTraceContext.current();
            if (!traceId.isBlank()) {
                builder.header(cn.itcast.demo.mylunarcore.net.NetTraceContext.HTTP_HEADER, traceId);
            }
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long latency = System.currentTimeMillis() - started;
            totalLatencyMs.addAndGet(latency);
            if (response.statusCode() / 100 != 2) {
                errorCount.incrementAndGet();
                circuitBreaker.onFailure();
                log.warn("AiAssistClient HTTP {}, latencyMs={} traceId={}", response.statusCode(), latency, traceId);
                return Optional.empty();
            }
            JsonNode root = objectMapper.readTree(response.body());
            int code = root.path("code").asInt(0);
            if (code != 0) {
                errorCount.incrementAndGet();
                circuitBreaker.onFailure();
                log.warn("AiAssistClient business code={}, msg={} traceId={}", code, root.path("message").asText(), traceId);
                return Optional.empty();
            }
            RemoteAssistAskResponse data = objectMapper.treeToValue(root.path("data"), RemoteAssistAskResponse.class);
            if (data == null) {
                errorCount.incrementAndGet();
                circuitBreaker.onFailure();
                return Optional.empty();
            }
            successCount.incrementAndGet();
            circuitBreaker.onSuccess();
            log.debug("AiAssistClient ok source={} latencyMs={} traceId={}", data.source(), latency, traceId);
            return Optional.of(data);
        } catch (Exception e) {
            errorCount.incrementAndGet();
            circuitBreaker.onFailure();
            totalLatencyMs.addAndGet(System.currentTimeMillis() - started);
            log.warn("AiAssistClient failed: {}", e.toString());
            return Optional.empty();
        }
    }

    public long getSuccessCount() {
        return successCount.get();
    }

    public long getErrorCount() {
        return errorCount.get();
    }

    public long getCircuitRejectCount() {
        return circuitRejectCount.get();
    }

    public AssistRemoteCircuitBreaker.State circuitState() {
        return circuitBreaker.state();
    }

    public long getAverageLatencyMs() {
        long ok = successCount.get() + errorCount.get();
        return ok == 0 ? 0 : totalLatencyMs.get() / ok;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
