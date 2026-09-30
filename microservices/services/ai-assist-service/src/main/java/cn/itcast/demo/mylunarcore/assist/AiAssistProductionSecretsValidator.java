// 声明当前包：AI 旁路微服务的生产安全校验
package cn.itcast.demo.mylunarcore.assist;

// 日志门面
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
// 仅在 prod 环境启用
import org.springframework.context.annotation.Profile;
// 环境读取配置项
import org.springframework.core.env.Environment;
// 组件扫描
import org.springframework.stereotype.Component;

// 错误集合
import java.util.ArrayList;
// 列表
import java.util.List;
// 区域无关大小写
import java.util.Locale;
// 弱 token 黑名单
import java.util.Set;

/**
 * ai-assist-service prod：禁止默认 internal-token。
 *
 * <p>该类在生产 Profile 下启动时执行，确保内部鉴权 token 不仍然停留在开发默认值。
 * 一旦命中弱 token 或完全缺失，将在容器启动阶段直接失败，阻止服务上线。</p>
 */
@Component
@Profile("prod")
public class AiAssistProductionSecretsValidator {

    // 日志记录器
    private static final Logger log = LoggerFactory.getLogger(AiAssistProductionSecretsValidator.class);

    /**
     * 禁止使用的开发/占位 token。
     */
    private static final Set<String> FORBIDDEN_TOKENS = Set.of(
            "dev-internal-token", "change-me", "changeme", "secret"
    );

    /**
     * 构造器：校验生产环境 token 配置，发现问题则拒绝启动。
     *
     * @param environment 环境对象
     * @param properties  AI 助手配置
     */
    public AiAssistProductionSecretsValidator(Environment environment, AiAssistServiceProperties properties) {
        List<String> errors = validate(environment, properties);
        if (!errors.isEmpty()) {
            String message = "ai-assist-service production secrets validation failed: " + String.join("; ", errors);
            log.error(message);
            throw new IllegalStateException(message);
        }
        log.info("ai-assist-service production secrets validation passed");
    }

    /**
     * 执行内部 token 校验。
     *
     * @param environment 环境对象
     * @param properties   配置对象
     * @return 错误集合
     */
    static List<String> validate(Environment environment, AiAssistServiceProperties properties) {
        List<String> errors = new ArrayList<>();
        String token = firstNonBlank(
                environment.getProperty("mylunarcore.ai-assist.internal-token"),
                environment.getProperty("AI_ASSIST_INTERNAL_TOKEN"),
                properties == null ? null : properties.getInternalToken());
        if (token == null || token.isBlank()) {
            errors.add("mylunarcore.ai-assist.internal-token / AI_ASSIST_INTERNAL_TOKEN is required");
        } else if (FORBIDDEN_TOKENS.contains(token.trim().toLowerCase(Locale.ROOT))) {
            errors.add("internal token must not use a default/dev value");
        }
        return errors;
    }

    /**
     * 取第一个非空白值。
     */
    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
