// 声明当前包：AI 旁路微服务的外部 LLM 调用网关
package cn.itcast.demo.mylunarcore.assist;

// 提问契约
import cn.itcast.demo.mylunarcore.common.api.assist.AssistAskRequest;
// 提示词模板
import cn.itcast.demo.mylunarcore.common.api.assist.AssistPromptTemplates;
// Jackson 树模型
import com.fasterxml.jackson.databind.JsonNode;
// Jackson 对象映射器
import com.fasterxml.jackson.databind.ObjectMapper;
// Jackson 数组节点
import com.fasterxml.jackson.databind.node.ArrayNode;
// Jackson 对象节点
import com.fasterxml.jackson.databind.node.ObjectNode;
// 日志
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
// Spring 组件
import org.springframework.stereotype.Component;

// URI
import java.net.URI;
// JDK HttpClient
import java.net.http.HttpClient;
// HTTP 请求构建器
import java.net.http.HttpRequest;
// HTTP 响应
import java.net.http.HttpResponse;
// 字符集
import java.nio.charset.StandardCharsets;
// 超时时间
import java.time.Duration;
// 可选返回值
import java.util.Optional;

/**
 * 旁路服务唯一 LLM 出口（与游戏服 Prompt 模板同源）。
 *
 * <p>该网关负责把已归一化的玩家上下文转换为 OpenAI 兼容的 chat/completions 请求，
 * 并将返回的 message.content 提取为纯文本答案。若外部服务不可用或返回非 2xx，
 * 方法会退化为 Optional.empty()，由上层规则引擎继续输出保守答案。</p>
 */
@Component
public class RemoteLlmGateway {

    // 日志记录器：记录调用失败、HTTP 状态码等信息
    private static final Logger log = LoggerFactory.getLogger(RemoteLlmGateway.class);

    // 配置属性：控制 endpoint、apiKey、模型、超时等
    private final AiAssistServiceProperties properties;
    // JSON 编解码工具
    private final ObjectMapper objectMapper;
    // 复用的 HttpClient：关闭自动重定向，避免把鉴权头带到意外地址
    private final HttpClient httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    /**
     * 构造器：注入配置与 JSON 工具。
     */
    public RemoteLlmGateway(AiAssistServiceProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * 发起一次外部 LLM 请求。
     *
     * @param request 归一化后的提问请求
     * @return 返回模型回答文本；当未启用 LLM、配置缺失、请求失败或响应异常时返回空
     */
    public Optional<String> ask(AssistAskRequest request) {
        if (!properties.isLlmEnabled()) {
            return Optional.empty();
        }
        String endpoint = properties.getLlmEndpoint();
        if (endpoint == null || endpoint.isBlank()) {
            return Optional.empty();
        }
        try {
            // 构造 OpenAI 兼容请求体
            ObjectNode root = objectMapper.createObjectNode();
            root.put("model", properties.getLlmModel());
            root.put("temperature", 0.2);
            root.put("max_tokens", Math.max(64, properties.getLlmMaxTokens()));
            ArrayNode messages = root.putArray("messages");
            messages.addObject()
                    .put("role", "system")
                    .put("content", AssistPromptTemplates.SYSTEM_PROMPT);
            messages.addObject()
                    .put("role", "user")
                    .put("content", "问题：" + request.question()
                            + "\n上下文：" + objectMapper.writeValueAsString(request.playerContext())
                            + "\n知识：" + objectMapper.writeValueAsString(request.knowledgeHints()));
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofMillis(Math.max(500L, properties.getLlmTimeoutMs())))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(root), StandardCharsets.UTF_8));
            if (properties.getLlmApiKey() != null && !properties.getLlmApiKey().isBlank()) {
                builder.header("Authorization", "Bearer " + properties.getLlmApiKey());
            }
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                log.warn("remote llm http={}", response.statusCode());
                return Optional.empty();
            }
            JsonNode content = objectMapper.readTree(response.body()).path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.asText("").isBlank()) {
                return Optional.empty();
            }
            return Optional.of(content.asText().trim());
        } catch (Exception e) {
            log.warn("remote llm failed: {}", e.toString());
            return Optional.empty();
        }
    }
}
