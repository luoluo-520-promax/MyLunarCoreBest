package cn.itcast.demo.mylunarcore.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * SensitiveDataMaskerTest。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code SensitiveDataMaskerTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
class SensitiveDataMaskerTest {

    /**
     * 验证点：masksPasswordTokenAndIap。
     * <p>测试方法 {@code masksPasswordTokenAndIap}：
     * <ul>
     *   <li>{@code assertFalse(masked.contains("secret123"));}</li>
     *   <li>{@code assertFalse(masked.contains("abc.def"));}</li>
     *   <li>{@code assertFalse(masked.contains("TX-999"));}</li>
     *   <li>{@code assertFalse(masked.contains("eyJhbGciOiJIUzI1NiJ9"));}</li>
     *   <li>{@code assertEquals(true, masked.contains("***"));}</li>
     * </ul>
     */
    @Test
    void masksPasswordTokenAndIap() {
        String raw = "login password=secret123 token=abc.def receipt=TX-999 Bearer eyJhbGciOiJIUzI1NiJ9.xx";
        String masked = SensitiveDataMasker.mask(raw);
        assertFalse(masked.contains("secret123"));
        assertFalse(masked.contains("abc.def"));
        assertFalse(masked.contains("TX-999"));
        assertFalse(masked.contains("eyJhbGciOiJIUzI1NiJ9"));
        assertEquals(true, masked.contains("***"));
    }

    /**
     * 验证点：masksPiiEmailPhoneIdCard。
     * <p>测试方法 {@code masksPiiEmailPhoneIdCard}：
     * <ul>
     *   <li>{@code assertFalse(masked.contains("user@example.com"));}</li>
     *   <li>{@code assertFalse(masked.contains("13812345678"));}</li>
     *   <li>{@code assertFalse(masked.contains("110101199001011234"));}</li>
     * </ul>
     */
    @Test
    void masksPiiEmailPhoneIdCard() {
        String raw = "email=user@example.com phone=13812345678 id_card=110101199001011234";
        String masked = SensitiveDataMasker.mask(raw);
        assertFalse(masked.contains("user@example.com"));
        assertFalse(masked.contains("13812345678"));
        assertFalse(masked.contains("110101199001011234"));
    }
}
