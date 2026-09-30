// 声明当前包：API 网关的安全校验组件
package cn.itcast.demo.mylunarcore.gateway.security;

// 日志门面：输出生产环境启动校验结果
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
// 环境 Profile 限定：仅在 prod 生效
import org.springframework.context.annotation.Profile;
// Spring 环境抽象：按 key 读取配置项（application.yml、环境变量等）
import org.springframework.core.env.Environment;
// 组件标记：由 Spring 自动实例化
import org.springframework.stereotype.Component;

// 可变列表：收集所有校验错误
import java.util.ArrayList;
// 列表：校验错误集合
import java.util.List;
// 区域无关的大小写转换（避免土耳其 i 问题）
import java.util.Locale;
// 不可变集合：预设的弱口令黑名单
import java.util.Set;

/**
 * api-gateway prod：拒绝默认 JWT 密钥与 changeit 证书口令。
 *
 * <p>仅在生产环境（prod Profile）实例化的启动期校验器。构造时读取 JWT 密钥与
 * SSL 证书口令相关配置，一旦命中默认/弱口令集合或长度/复杂度不达标，立即抛异常
 * 阻止网关启动，从源头杜绝使用占位密钥上线。</p>
 */
@Component
@Profile("prod")
public class GatewayProductionSecretsValidator {

    // 日志记录器：记录校验失败与通过信息
    private static final Logger log = LoggerFactory.getLogger(GatewayProductionSecretsValidator.class);

    /**
     * JWT 密钥黑名单：仓库默认值或常见的占位/通用弱密钥。
     */
    private static final Set<String> FORBIDDEN_SECRETS = Set.of(
            "change-me-to-a-secure-32-byte-secret!",
            "change-me-to-a-secure-32-byte-secret",
            "change-me",
            "changeme",
            "secret"
    );

    /**
     * 口令黑名单：SSL 密钥库/密钥口令及证书常见的默认弱口令。
     */
    private static final Set<String> FORBIDDEN_PASSWORDS = Set.of(
            "changeit", "123456", "password", "admin"
    );

    /**
     * 构造器：执行全部生产安全校验，存在任一错误则抛异常阻断启动。
     *
     * @param environment    Spring 环境，用于读取配置项与环境变量
     * @param authProperties 网关鉴权配置（含 JWT 密钥），可为 null
     * @throws IllegalStateException 存在弱密钥/弱口令等生产安全问题时抛出
     */
    public GatewayProductionSecretsValidator(Environment environment, GatewayAuthProperties authProperties) {
        // 汇总所有校验错误
        List<String> errors = validate(environment, authProperties);
        if (!errors.isEmpty()) {
            // 存在错误：记录错误日志并拒绝启动
            String message = "api-gateway production secrets validation failed: " + String.join("; ", errors);
            log.error(message);
            throw new IllegalStateException(message);
        }
        // 全部通过：记录成功日志
        log.info("api-gateway production secrets validation passed");
    }

    /**
     * 校验核心：检查 JWT 密钥与各 SSL 口令。
     *
     * @param environment    Spring 环境（包内可见，便于测试直接调用）
     * @param authProperties 网关鉴权配置
     * @return 错误信息列表；为空表示全部通过
     */
    static List<String> validate(Environment environment, GatewayAuthProperties authProperties) {
        // 错误集合
        List<String> errors = new ArrayList<>();
        // 按优先级取 JWT 密钥：优先配置项，其次环境变量，最后回退配置对象中的默认值
        String secret = firstNonBlank(
                environment.getProperty("mylunarcore.gateway.auth.jwt-secret"),
                environment.getProperty("GATEWAY_JWT_SECRET"),
                authProperties == null ? null : authProperties.getJwtSecret());
        // 生产环境必须显式提供环境变量 GATEWAY_JWT_SECRET，不能只依赖 application.yml
        if (isBlank(environment.getProperty("GATEWAY_JWT_SECRET")) && isBlank(System.getenv("GATEWAY_JWT_SECRET"))) {
            errors.add("GATEWAY_JWT_SECRET env var is required in prod");
        }
        if (secret == null || secret.isBlank()) {
            // 完全缺失
            errors.add("mylunarcore.gateway.auth.jwt-secret / GATEWAY_JWT_SECRET is required");
        } else if (FORBIDDEN_SECRETS.contains(secret.trim().toLowerCase(Locale.ROOT))
                || secret.toLowerCase(Locale.ROOT).contains("change-me")) {
            // 命中黑名单或包含占位前缀
            errors.add("jwt secret must not use a default/weak value");
        } else if (secret.getBytes().length < 32) {
            // 长度不满足 HMAC 安全强度
            errors.add("jwt secret must be at least 32 bytes");
        } else {
            // 复杂度校验：必须同时包含字母、数字与特殊字符
            boolean hasLetter = secret.chars().anyMatch(Character::isLetter);
            boolean hasDigit = secret.chars().anyMatch(Character::isDigit);
            boolean hasSpecial = secret.chars().anyMatch(ch -> !Character.isLetterOrDigit(ch));
            if (!hasLetter || !hasDigit || !hasSpecial) {
                errors.add("jwt secret must contain letters, digits and a special character");
            }
        }

        // 校验 SSL 相关口令是否命中弱口令黑名单
        rejectWeak(environment, "server.ssl.key-store-password", errors);
        rejectWeak(environment, "GATEWAY_SSL_STORE_PASSWORD", errors);
        rejectWeak(environment, "GATEWAY_SSL_KEY_PASSWORD", errors);
        return errors;
    }

    /**
     * 检查单个配置项是否命中弱口令黑名单，命中则追加错误。
     *
     * @param environment Spring 环境
     * @param key         配置项 key
     * @param errors      错误集合（有则追加）
     */
    private static void rejectWeak(Environment environment, String key, List<String> errors) {
        String value = environment.getProperty(key);
        if (value != null && FORBIDDEN_PASSWORDS.contains(value.trim().toLowerCase(Locale.ROOT))) {
            errors.add(key + " must not use default/weak value");
        }
    }

    /**
     * 判空工具：null 或空白均视为空。
     */
    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * 取第一个非空白值（配置优先级回退工具）。
     *
     * @param values 按优先级排列的候选值
     * @return 第一个非 null 非空白的值；全部为空时返回 null
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
