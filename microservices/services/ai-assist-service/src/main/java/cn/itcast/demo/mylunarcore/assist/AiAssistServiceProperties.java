// 声明当前包：AI 旁路微服务的配置属性定义
package cn.itcast.demo.mylunarcore.assist;

// 生命周期回调：用于启动时校验配置
import jakarta.annotation.PostConstruct;
// Spring Boot 配置属性绑定
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 助手服务运行配置。
 *
 * <p>绑定前缀 {@code mylunarcore.ai-assist} 下的配置项，控制内部鉴权 token、是否
 * 启用外部 LLM、LLM 接口地址、API Key、模型名及超时/最大 token 限制。该类既服务于
 * 本地规则引擎，也服务于远程 LLM 转发逻辑。</p>
 */
@ConfigurationProperties(prefix = "mylunarcore.ai-assist")
public class AiAssistServiceProperties {

    /**
     * 服务间调用的内部鉴权 Token。
     *
     * <p>游戏服或网关调用 /internal/ai/ask 与 /ai/metrics 时必须携带该 Token。
     * 默认值仅适合本地开发，生产环境应通过环境变量 AI_ASSIST_INTERNAL_TOKEN 覆盖。</p>
     */
    private String internalToken = "dev-internal-token";

    /**
     * 是否启用外部 LLM 转发。
     *
     * <p>关闭时仅使用本地规则引擎生成回答；开启后会在规则结果基础上尝试调用外部
     * Chat Completions 风格接口，增强文案自然度。</p>
     */
    private boolean llmEnabled = false;

    /**
     * 外部 LLM 的 HTTP 接口地址（例如 OpenAI 兼容服务的 /v1/chat/completions）。
     */
    private String llmEndpoint = "";

    /**
     * 外部 LLM 的 API Key；为空表示不附带 Authorization 头。
     */
    private String llmApiKey = "";

    /**
     * 外部 LLM 使用的模型名称（请求体中的 model 字段）。
     */
    private String llmModel = "gpt-4o-mini";

    /**
     * 调用外部 LLM 的超时时间（毫秒）。
     */
    private long llmTimeoutMs = 3000L;

    /**
     * 外部 LLM 请求允许的最大 token 数。
     */
    private int llmMaxTokens = 512;

    /**
     * 启动后校验内部 Token 是否非空。
     *
     * <p>该校验只保证「不为空」，真正是否为生产可用值由生产环境校验器
     * {@link AiAssistProductionSecretsValidator} 进一步检查。</p>
     */
    @PostConstruct
    void validateToken() {
        if (internalToken == null || internalToken.isBlank()) {
            throw new IllegalStateException(
                    "mylunarcore.ai-assist.internal-token must be non-blank (set AI_ASSIST_INTERNAL_TOKEN)");
        }
    }

    /** @return 服务间调用内部 Token */
    public String getInternalToken() {
        return internalToken;
    }

    /** @param internalToken 设置内部 Token */
    public void setInternalToken(String internalToken) {
        this.internalToken = internalToken;
    }

    /** @return 是否启用外部 LLM */
    public boolean isLlmEnabled() {
        return llmEnabled;
    }

    /** @param llmEnabled 设置是否启用外部 LLM */
    public void setLlmEnabled(boolean llmEnabled) {
        this.llmEnabled = llmEnabled;
    }

    /** @return 外部 LLM 接口地址 */
    public String getLlmEndpoint() {
        return llmEndpoint;
    }

    /** @param llmEndpoint 设置外部 LLM 接口地址 */
    public void setLlmEndpoint(String llmEndpoint) {
        this.llmEndpoint = llmEndpoint;
    }

    /** @return 外部 LLM API Key */
    public String getLlmApiKey() {
        return llmApiKey;
    }

    /** @param llmApiKey 设置外部 LLM API Key */
    public void setLlmApiKey(String llmApiKey) {
        this.llmApiKey = llmApiKey;
    }

    /** @return 外部 LLM 模型名称 */
    public String getLlmModel() {
        return llmModel;
    }

    /** @param llmModel 设置外部 LLM 模型名称 */
    public void setLlmModel(String llmModel) {
        this.llmModel = llmModel;
    }

    /** @return LLM 调用超时（毫秒） */
    public long getLlmTimeoutMs() {
        return llmTimeoutMs;
    }

    /** @param llmTimeoutMs 设置 LLM 调用超时时间（毫秒） */
    public void setLlmTimeoutMs(long llmTimeoutMs) {
        this.llmTimeoutMs = llmTimeoutMs;
    }

    /** @return 请求允许的最大 token 数 */
    public int getLlmMaxTokens() {
        return llmMaxTokens;
    }

    /** @param llmMaxTokens 设置请求允许的最大 token 数 */
    public void setLlmMaxTokens(int llmMaxTokens) {
        this.llmMaxTokens = llmMaxTokens;
    }
}
