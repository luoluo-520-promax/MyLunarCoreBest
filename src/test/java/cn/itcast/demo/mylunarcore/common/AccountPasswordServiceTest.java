package cn.itcast.demo.mylunarcore.common;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AccountPasswordService 密码哈希统一测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code AccountPasswordServiceTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("AccountPasswordService 密码哈希统一测试")
class AccountPasswordServiceTest {

    private final PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();

    /**
     * 验证点：应校验 bcrypt 哈希；默认关闭明文兼容。
     * <p>测试方法 {@code shouldMatchBcryptAndRejectPlaintextByDefault}：
     * <ul>
     *   <li>{@code assertTrue(service.matches("secret", hashed));}</li>
     *   <li>{@code assertFalse(service.needsRehash(hashed));}</li>
     *   <li>{@code assertFalse(service.matches("plain", "plain"));}</li>
     *   <li>{@code assertTrue(service.needsRehash("plain"));}</li>
     * </ul>
     */
    @Test
    @DisplayName("应校验 bcrypt 哈希；默认关闭明文兼容")
    void shouldMatchBcryptAndRejectPlaintextByDefault() {
        LunarCoreProperties properties = new LunarCoreProperties();
        AccountPasswordService service = new AccountPasswordService(encoder, properties);

        String hashed = service.encode("secret");
        assertTrue(service.matches("secret", hashed));
        assertFalse(service.needsRehash(hashed));

        assertFalse(service.matches("plain", "plain"));
        assertTrue(service.needsRehash("plain"));
    }

    /**
     * 验证点：开启明文兼容开关后应允许无前缀比对。
     * <p>测试方法 {@code shouldAllowPlaintextWhenEnabled}：
     * <ul>
     *   <li>{@code assertTrue(service.matches("plain", "plain"));}</li>
     *   <li>{@code assertFalse(service.matches("plain", "other"));}</li>
     * </ul>
     */
    @Test
    @DisplayName("开启明文兼容开关后应允许无前缀比对")
    void shouldAllowPlaintextWhenEnabled() {
        LunarCoreProperties properties = new LunarCoreProperties();
        properties.getSecurity().setAllowPlaintextPasswordMatch(true);
        AccountPasswordService service = new AccountPasswordService(encoder, properties);

        assertTrue(service.matches("plain", "plain"));
        assertFalse(service.matches("plain", "other"));
    }
}
