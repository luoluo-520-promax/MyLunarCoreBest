package cn.itcast.demo.mylunarcore.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ProductionSecretsValidator 生产密钥校验测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code ProductionSecretsValidatorTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("ProductionSecretsValidator 生产密钥校验测试")
class ProductionSecretsValidatorTest {

    private static final String STRONG_TOKEN = "Prod-Internal-Token-9f3a!SecureKey01";
    private static final String STRONG_DB = "S3cure-Db-Pass!Word-Length32xxxx";

    /**
     * 验证点：默认 changeit / 123456 / dev-internal-token 应被拒绝。
     * <p>测试方法 {@code shouldRejectDefaultSecrets}：
     * <ul>
     *   <li>{@code assertFalse(errors.isEmpty());}</li>
     *   <li>{@code assertTrue(errors.stream().anyMatch(e -> e.contains("database password")));}</li>
     *   <li>{@code assertTrue(errors.stream().anyMatch(e -> e.contains("key-store-password")));}</li>
     *   <li>{@code assertTrue(errors.stream().anyMatch(e -> e.contains("internal api token")));}</li>
     * </ul>
     */
    @Test
    @DisplayName("默认 changeit / 123456 / dev-internal-token 应被拒绝")
    void shouldRejectDefaultSecrets() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.datasource.password", "123456");
        env.setProperty("server.ssl.key-store-password", "changeit");
        env.setProperty("lunarcore.tls.key-store-password", "changeit");
        env.setProperty("lunarcore.tls.key-password", "changeit");
        env.setProperty("lunarcore.internal-api-token", "dev-internal-token");

        List<String> errors = ProductionSecretsValidator.validate(env);
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(e -> e.contains("database password")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("key-store-password")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("internal api token")));
    }

    /**
     * 验证点：缺少环境变量形态密钥或 KCP 加密时应失败。
     * <p>测试方法 {@code shouldRequireEnvAndKcpCrypto}：
     * <ul>
     *   <li>{@code assertTrue(errors.stream().anyMatch(e -> e.contains("INTERNAL_API_TOKEN")));}</li>
     *   <li>{@code assertTrue(errors.stream().anyMatch(e -> e.contains("DB_PASSWORD")));}</li>
     *   <li>{@code assertTrue(errors.stream().anyMatch(e -> e.contains("kcp-crypto")));}</li>
     * </ul>
     */
    @Test
    @DisplayName("缺少环境变量形态密钥或 KCP 加密时应失败")
    void shouldRequireEnvAndKcpCrypto() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.datasource.password", STRONG_DB);
        env.setProperty("lunarcore.internal-api-token", STRONG_TOKEN);
        env.setProperty("lunarcore.kcp-crypto.enabled", "false");

        List<String> errors = ProductionSecretsValidator.validate(env);
        assertTrue(errors.stream().anyMatch(e -> e.contains("INTERNAL_API_TOKEN")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("DB_PASSWORD")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("kcp-crypto")));
    }

    /**
     * 验证点：弱强度密钥（过短/无特殊字符）应失败。
     * <p>测试方法 {@code shouldRejectWeakStrength}：
     * <ul>
     *   <li>{@code assertTrue(errors.stream().anyMatch(e -> e.contains("32 characters") || e.contains("special")));}</li>
     * </ul>
     */
    @Test
    @DisplayName("弱强度密钥（过短/无特殊字符）应失败")
    void shouldRejectWeakStrength() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.datasource.password", "short");
        env.setProperty("DB_PASSWORD", "short");
        env.setProperty("lunarcore.internal-api-token", "abcdefghijklmnopqrstuvwxyz012345");
        env.setProperty("INTERNAL_API_TOKEN", "abcdefghijklmnopqrstuvwxyz012345");
        env.setProperty("lunarcore.kcp-crypto.enabled", "true");
        env.setProperty("lunarcore.protocol-hmac.enabled", "true");
        env.setProperty("mylunarcore.iap.mock-verify", "false");
        env.setProperty("lunarcore.admin.ip-whitelist", "10.0.0.0/8");

        List<String> errors = ProductionSecretsValidator.validate(env);
        assertTrue(errors.stream().anyMatch(e -> e.contains("32 characters") || e.contains("special")));
    }

    /**
     * 验证点：强密钥 + 环境变量 + KCP/HMAC + 白名单应通过校验。
     * <p>测试方法 {@code shouldAcceptStrongSecrets}：
     * <ul>
     *   <li>{@code assertTrue(errors.isEmpty(), () -> String.join("; ", errors));}</li>
     * </ul>
     */
    @Test
    @DisplayName("强密钥 + 环境变量 + KCP/HMAC + 白名单应通过校验")
    void shouldAcceptStrongSecrets() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.datasource.password", STRONG_DB);
        env.setProperty("DB_PASSWORD", STRONG_DB);
        env.setProperty("server.ssl.key-store-password", "S3cure-Ks!PassWord-Length32");
        env.setProperty("lunarcore.tls.key-store-password", "S3cure-Tls!PassWord-Length32");
        env.setProperty("lunarcore.tls.key-password", "S3cure-Key!PassWord-Length32");
        env.setProperty("lunarcore.internal-api-token", STRONG_TOKEN);
        env.setProperty("INTERNAL_API_TOKEN", STRONG_TOKEN);
        env.setProperty("lunarcore.kcp-crypto.enabled", "true");
        env.setProperty("lunarcore.protocol-hmac.enabled", "true");
        env.setProperty("mylunarcore.iap.mock-verify", "false");
        env.setProperty("lunarcore.admin.ip-whitelist", "10.0.0.0/8,127.0.0.1");

        List<String> errors = ProductionSecretsValidator.validate(env);
        assertTrue(errors.isEmpty(), () -> String.join("; ", errors));
    }

    /**
     * 验证点：prod 拒绝 IAP mock 与缺失 HMAC/白名单。
     * <p>测试方法 {@code shouldRejectMockIapAndMissingHmacWhitelist}：
     * <ul>
     *   <li>{@code assertTrue(errors.stream().anyMatch(e -> e.contains("mock-verify")));}</li>
     *   <li>{@code assertTrue(errors.stream().anyMatch(e -> e.contains("protocol-hmac")));}</li>
     *   <li>{@code assertTrue(errors.stream().anyMatch(e -> e.contains("ip-whitelist")));}</li>
     * </ul>
     */
    @Test
    @DisplayName("prod 拒绝 IAP mock 与缺失 HMAC/白名单")
    void shouldRejectMockIapAndMissingHmacWhitelist() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.datasource.password", STRONG_DB);
        env.setProperty("DB_PASSWORD", STRONG_DB);
        env.setProperty("lunarcore.internal-api-token", STRONG_TOKEN);
        env.setProperty("INTERNAL_API_TOKEN", STRONG_TOKEN);
        env.setProperty("lunarcore.kcp-crypto.enabled", "true");
        env.setProperty("mylunarcore.iap.mock-verify", "true");

        List<String> errors = ProductionSecretsValidator.validate(env);
        assertTrue(errors.stream().anyMatch(e -> e.contains("mock-verify")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("protocol-hmac")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("ip-whitelist")));
    }
}
