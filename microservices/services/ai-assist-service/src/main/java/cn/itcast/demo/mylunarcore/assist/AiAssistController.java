package cn.itcast.demo.mylunarcore.assist;

import cn.itcast.demo.mylunarcore.common.api.ApiResponse;
import cn.itcast.demo.mylunarcore.common.api.assist.AssistAskRequest;
import cn.itcast.demo.mylunarcore.common.api.assist.AssistAskResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AI 助手微服务 HTTP 入口：健康检查、内部指标与 {@code /internal/ai/ask} 问答。
 * <p>
 * 需经网关/内网调用；敏感接口用请求头 {@code X-Internal-Token} 与配置 token
 * 经 {@link cn.itcast.demo.mylunarcore.common.api.security.InternalTokenVerifier} 校验。
 * 问答流程：规则引擎 {@link RemoteAssistEngine} 先出底案，再可选叠加 {@link RemoteLlmGateway} LLM 文案。
 */
@RestController
@RequestMapping
public class AiAssistController {

    private static final Logger log = LoggerFactory.getLogger(AiAssistController.class);

    // 含 internalToken 等运行配置
    private final AiAssistServiceProperties properties;
    // 规则/RAG 类本地评估与 sanitize
    private final RemoteAssistEngine engine;
    // 远程大模型网关；未命中或关闭时返回 empty
    private final RemoteLlmGateway llmGateway;

    // 累计提问次数（含失败前已自增）
    private final AtomicLong askCount = new AtomicLong();
    // LLM 成功覆盖规则答案的次数
    private final AtomicLong llmHit = new AtomicLong();
    // ask 接口捕获异常次数
    private final AtomicLong errorCount = new AtomicLong();

    public AiAssistController(AiAssistServiceProperties properties,
                              RemoteAssistEngine engine,
                              RemoteLlmGateway llmGateway) {
        this.properties = properties;
        this.engine = engine;
        this.llmGateway = llmGateway;
    }

    /**
     * 健康探测：返回服务名字符串，路径兼容 {@code /ai/health} 与 actuator 别名。
     */
    @GetMapping({"/ai/health", "/actuator/health-alias"})
    public ApiResponse<String> health() {
        return ApiResponse.ok("ai-assist-service");
    }

    /**
     * 内部指标：askCount / llmHit / errorCount；必须带合法 X-Internal-Token。
     */
    @GetMapping("/ai/metrics")
    public ApiResponse<Object> metrics(@RequestHeader(value = "X-Internal-Token", required = false) String token) {
        assertInternalToken(token);
        return ApiResponse.ok(java.util.Map.of(
                "askCount", askCount.get(),
                "llmHit", llmHit.get(),
                "errorCount", errorCount.get()
        ));
    }

    /**
     * 内部问答：校验 token → 可选写入 MDC traceId → 校验 schemaVersion →
     * 规则评估 → 尝试 LLM → 组装 {@link AssistAskResponse}。
     * <ul>
     *   <li>schema 不匹配：业务 fail code=3</li>
     *   <li>处理异常：fail code=5，并增加 errorCount</li>
     * </ul>
     */
    @PostMapping("/internal/ai/ask")
    public ApiResponse<AssistAskResponse> ask(@RequestHeader(value = "X-Internal-Token", required = false) String token,
                                              @RequestHeader(value = "X-Trace-Id", required = false) String traceId,
                                              @RequestBody AssistAskRequest request) {
        assertInternalToken(token);
        if (traceId != null && !traceId.isBlank()) {
            org.slf4j.MDC.put("traceId", traceId); // 关联网关/调用方链路
        }
        askCount.incrementAndGet();
        long started = System.currentTimeMillis(); // 用于 latencyMs 日志
        try {
            // schemaVersion>0 且与服务端 CURRENT 不一致则拒绝，避免字段语义漂移
            if (request.schemaVersion() > 0
                    && request.schemaVersion() != cn.itcast.demo.mylunarcore.common.api.assist.AssistSchemaVersions.CURRENT) {
                log.warn("ai_ask schema mismatch client={} server={} traceId={}",
                        request.schemaVersion(),
                        cn.itcast.demo.mylunarcore.common.api.assist.AssistSchemaVersions.CURRENT,
                        traceId);
                return ApiResponse.fail(3, "schemaVersion mismatch");
            }
            // 规则引擎：安全过滤、提示模板、相关配置引用等
            AssistAskResponse rule = engine.evaluate(request);
            // LLM 可选增强；empty 则沿用规则答案
            Optional<String> llm = llmGateway.ask(request);
            AssistAskResponse result;
            if (llm.isPresent()) {
                llmHit.incrementAndGet();
                // 保留规则侧 hints/citedConfigIds，正文换为 sanitize 后的 LLM 文本，source=llm
                result = new AssistAskResponse(
                        0,
                        engine.sanitize(llm.get()),
                        "llm",
                        rule.relatedHints(),
                        rule.citedConfigIds()
                );
            } else {
                result = rule;
            }
            // uid 仅打 hash，避免日志落明文玩家 ID
            log.info("ai_ask uidHash={} source={} latencyMs={} traceId={}",
                    Integer.toHexString(Long.hashCode(request.uid())),
                    result.source(),
                    System.currentTimeMillis() - started,
                    traceId);
            return ApiResponse.ok(result);
        } catch (Exception e) {
            errorCount.incrementAndGet();
            log.warn("ai_ask failed traceId={}: {}", traceId, e.toString());
            return ApiResponse.fail(5, "ai assist error");
        } finally {
            org.slf4j.MDC.remove("traceId"); // 防线程复用污染
        }
    }

    /**
     * 校验内部 token：配置为空或与请求不匹配均抛 401。
     */
    private void assertInternalToken(String token) {
        String expected = properties.getInternalToken();
        if (expected == null || expected.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "internal token not configured");
        }
        if (!cn.itcast.demo.mylunarcore.common.api.security.InternalTokenVerifier.matches(expected, token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid internal token");
        }
    }
}
