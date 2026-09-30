package cn.itcast.demo.mylunarcore.assist; // LLM 访问网关所在包

import cn.itcast.demo.mylunarcore.common.AppLogger; // 使用统一业务日志入口
import cn.itcast.demo.mylunarcore.common.LogCategory; // 选择助手机业务日志分类
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties; // 读取 LLM 相关配置
import com.fasterxml.jackson.databind.JsonNode; // 解析上游 JSON 响应
import com.fasterxml.jackson.databind.ObjectMapper; // 组装与解析 JSON
import com.fasterxml.jackson.databind.node.ArrayNode; // 构建 messages 数组
import com.fasterxml.jackson.databind.node.ObjectNode; // 构建请求根节点
import org.slf4j.Logger; // 记录调用结果
import org.springframework.stereotype.Component; // 注册为组件

import java.net.URI; // 构造请求地址
import java.net.http.HttpClient; // 发送 HTTP 请求
import java.net.http.HttpRequest; // 构造 HTTP 请求体
import java.net.http.HttpResponse; // 接收 HTTP 响应
import java.nio.charset.StandardCharsets; // 以 UTF-8 发送请求
import java.time.Duration; // 设置请求超时
import java.util.List; // 传入知识块列表
import java.util.Map; // 传入玩家上下文
import java.util.Optional; // 表示是否拿到 LLM 答案

/**
 * 将玩家问题、上下文和检索到的知识封装为 Chat Completions 请求，并解析模型回复。
 */
@Component
public class LlmAssistGateway { // LLM 辅助网关
    /**
     * 记录 LLM 链路日志
     */
    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_ASSIST, LlmAssistGateway.class);
    /**
     * 统一系统提示词模板
     */
    private static final String SYSTEM_PROMPT = AssistPromptTemplates.SYSTEM_PROMPT;
    /**
     * 读取 LLM 配置
     */
    private final LunarCoreProperties properties;
    /**
     * 复用 JSON 编解码器
     */
    private final ObjectMapper objectMapper = new ObjectMapper();
    /**
     * 禁止自动重定向
     */
    private final HttpClient httpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    /**
     * 构造注入配置
     */
    public LlmAssistGateway(LunarCoreProperties properties) {
        this.properties = properties; // 保存运行时参数来源
    } // 构造结束
    /**
     * 调用上游 LLM
     */
    public Optional<String> ask(String question, Map<String, Object> playerContext, List<RagKnowledgeService.KnowledgeChunk> knowledge) {
        return ask(question, playerContext, knowledge, null);
    }

    /**
     * 调用上游 LLM；systemPrompt 为空时使用默认中文模板。
     */
    public Optional<String> ask(String question, Map<String, Object> playerContext,
                                List<RagKnowledgeService.KnowledgeChunk> knowledge, String systemPrompt) {
        LunarCoreProperties.AiAssistProperties cfg = properties.getAiAssist();
        if (!cfg.isEnabled() || !cfg.isLlmEnabled()) {
            return Optional.empty();
        }
        String endpoint = cfg.getLlmEndpoint();
        if (endpoint == null || endpoint.isBlank()) {
            return Optional.empty();
        }
        try {
            String prompt = systemPrompt == null || systemPrompt.isBlank() ? SYSTEM_PROMPT : systemPrompt;
            String body = buildChatCompletionsBody(question, playerContext, knowledge, cfg.getLlmModel(), prompt);
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(endpoint))
                    .timeout(Duration.ofMillis(Math.max(500L, cfg.getLlmTimeoutMs())))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            if (cfg.getLlmApiKey() != null && !cfg.getLlmApiKey().isBlank()) {
                builder.header("Authorization", "Bearer " + cfg.getLlmApiKey());
            }
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("LLM HTTP status={}, bodySnippet={}", response.statusCode(), truncate(response.body()));
                return Optional.empty();
            }
            String answer = extractAnswer(response.body()); // 从响应体中提取模型回答
            if (answer == null || answer.isBlank()) { // 没有可用答案时降级
                return Optional.empty(); // 返回空结果
            } // 答案空值判断结束
            return Optional.of(answer.trim()); // 返回去除首尾空白的答案
        } catch (Exception e) { // 捕获任何调用失败
            log.warn("LLM call failed: {}", e.toString()); // 记录异常摘要
            return Optional.empty(); // 失败时交给其他降级链路
        } // 异常处理结束
    } // ask 结束
    /**
     * 构建上游请求体
     */
    private String buildChatCompletionsBody(String question, Map<String, Object> playerContext,
                                            List<RagKnowledgeService.KnowledgeChunk> knowledge,
                                            String model, String systemPrompt) throws Exception {
        StringBuilder user = new StringBuilder();
        user.append("玩家问题：").append(question == null ? "" : question).append('\n');
        user.append("玩家上下文：").append(objectMapper.writeValueAsString(playerContext == null ? Map.of() : playerContext)).append('\n');
        user.append("检索到的配置知识：\n");
        if (knowledge != null) {
            for (RagKnowledgeService.KnowledgeChunk chunk : knowledge) {
                user.append("- [").append(chunk.id()).append("] ").append(chunk.text()).append('\n');
            }
        }
        ObjectNode root = objectMapper.createObjectNode();
        root.put("model", model == null || model.isBlank() ? "gpt-4o-mini" : model);
        ArrayNode messages = root.putArray("messages");
        ObjectNode sys = messages.addObject();
        sys.put("role", "system");
        sys.put("content", systemPrompt == null || systemPrompt.isBlank() ? SYSTEM_PROMPT : systemPrompt);
        ObjectNode usr = messages.addObject();
        usr.put("role", "user");
        usr.put("content", user.toString());
        root.put("temperature", 0.2);
        int maxTokens = properties.getAiAssist().getLlmMaxTokens();
        if (maxTokens > 0) {
            root.put("max_tokens", maxTokens);
        }
        return objectMapper.writeValueAsString(root);
    }

    String extractAnswer(String body) throws Exception { // 从响应文本中提取回答
        if (body == null || body.isBlank()) { // 空响应没有答案
            return null; // 返回空
        } // 空响应判断结束
        String trimmed = body.trim(); // 先去掉首尾空白
        try { // 尝试按 JSON 协议解析
            JsonNode root = objectMapper.readTree(body); // 解析完整 JSON 响应
            JsonNode content = root.path("choices").path(0).path("message").path("content"); // 按 Chat Completions 协议取 content
            if (!content.isMissingNode() && !content.asText("").isBlank()) { // 命中标准 content 字段
                return content.asText(); // 返回模型正文
            } // content 判断结束
            if (root.hasNonNull("answer")) { // 兼容简化协议的 answer 字段
                return root.get("answer").asText(); // 返回 answer 字段文本
            } // answer 字段判断结束
            if (trimmed.startsWith("{")) { // 看起来是 JSON 但没有识别字段时不当作答案
                return null; // 返回空值触发降级
            } // JSON 外观判断结束
        } catch (Exception parseEx) { // JSON 解析失败时兜底判断
            if (trimmed.startsWith("{")) { // 如果仍然像 JSON 就不能直接当答案
                return null; // 返回空值避免误把协议体当正文
            } // JSON 外观判断结束
        } // 解析异常处理结束
        return trimmed; // 既不是可识别 JSON 协议也不是空内容时按纯文本返回
    } // extractAnswer 结束
    /**
     * 截断日志中的长响应体
     */
    private static String truncate(String s) {
        if (s == null) { // 空值直接返回空串
            return ""; // 避免日志输出 null
        } // 空值判断结束
        return s.length() <= 200 ? s : s.substring(0, 200); // 最多记录前 200 字符
    } // truncate 结束
}
