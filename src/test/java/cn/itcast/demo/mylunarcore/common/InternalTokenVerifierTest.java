package cn.itcast.demo.mylunarcore.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * InternalTokenVerifier 恒定时间比较。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code InternalTokenVerifierTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("InternalTokenVerifier 恒定时间比较")
class InternalTokenVerifierTest {

    /**
     * 验证点：matchesEqualTokens。
     * <p>测试方法 {@code matchesEqualTokens}：
     * <ul>
     *   <li>{@code assertTrue(InternalTokenVerifier.matches("secret-token", "secret-token"));}</li>
     * </ul>
     */
    @Test
    void matchesEqualTokens() {
        assertTrue(InternalTokenVerifier.matches("secret-token", "secret-token"));
    }

    /**
     * 验证点：rejectsMismatchOrBlank。
     * <p>测试方法 {@code rejectsMismatchOrBlank}：
     * <ul>
     *   <li>{@code assertFalse(InternalTokenVerifier.matches("a", "b"));}</li>
     *   <li>{@code assertFalse(InternalTokenVerifier.matches("", "a"));}</li>
     *   <li>{@code assertFalse(InternalTokenVerifier.matches("a", null));}</li>
     *   <li>{@code assertFalse(InternalTokenVerifier.matches(null, "a"));}</li>
     * </ul>
     */
    @Test
    void rejectsMismatchOrBlank() {
        assertFalse(InternalTokenVerifier.matches("a", "b"));
        assertFalse(InternalTokenVerifier.matches("", "a"));
        assertFalse(InternalTokenVerifier.matches("a", null));
        assertFalse(InternalTokenVerifier.matches(null, "a"));
    }

    /**
     * 验证点：双密钥轮换：current 或 previous 均可。
     * <p>测试方法 {@code matchesAnySupportsRotationGrace}：
     * <ul>
     *   <li>{@code assertTrue(InternalTokenVerifier.matchesAny("current", "previous", "current"));}</li>
     *   <li>{@code assertTrue(InternalTokenVerifier.matchesAny("current", "previous", "previous"));}</li>
     *   <li>{@code assertFalse(InternalTokenVerifier.matchesAny("current", "previous", "other"));}</li>
     * </ul>
     */
    @Test
    @DisplayName("双密钥轮换：current 或 previous 均可")
    void matchesAnySupportsRotationGrace() {
        assertTrue(InternalTokenVerifier.matchesAny("current", "previous", "current"));
        assertTrue(InternalTokenVerifier.matchesAny("current", "previous", "previous"));
        assertFalse(InternalTokenVerifier.matchesAny("current", "previous", "other"));
    }
}
