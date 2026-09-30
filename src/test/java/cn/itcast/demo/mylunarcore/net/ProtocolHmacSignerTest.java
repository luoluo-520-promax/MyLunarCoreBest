package cn.itcast.demo.mylunarcore.net;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ProtocolHmacSigner 命令号动态盐签名。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code ProtocolHmacSignerTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("ProtocolHmacSigner 命令号动态盐签名")
class ProtocolHmacSignerTest {

    /**
     * 验证点：同密钥同命令应验签通过，改命令或改 body 应失败。
     * <p>测试方法 {@code signAndVerifyShouldBindCmdAndBody}：
     * <ul>
     *   <li>{@code assertTrue(ProtocolHmacSigner.verify(key, 37, 1L, body, mac));}</li>
     *   <li>{@code assertFalse(ProtocolHmacSigner.verify(key, 38, 1L, body, mac));}</li>
     *   <li>{@code assertFalse(ProtocolHmacSigner.verify(key, 37, 1L, "tampered".getBytes(StandardCharsets.UTF_8), mac));}</li>
     *   <li>{@code assertFalse(ProtocolHmacSigner.verify(key, 37, 2L, body, mac));}</li>
     * </ul>
     */
    @Test
    @DisplayName("同密钥同命令应验签通过，改命令或改 body 应失败")
    void signAndVerifyShouldBindCmdAndBody() {
        byte[] key = "session-key-16b!".getBytes(StandardCharsets.UTF_8);
        byte[] body = "login-payload".getBytes(StandardCharsets.UTF_8);
        byte[] mac = ProtocolHmacSigner.sign(key, 37, 1L, body);

        assertTrue(ProtocolHmacSigner.verify(key, 37, 1L, body, mac));
        assertFalse(ProtocolHmacSigner.verify(key, 38, 1L, body, mac));
        assertFalse(ProtocolHmacSigner.verify(key, 37, 1L, "tampered".getBytes(StandardCharsets.UTF_8), mac));
        assertFalse(ProtocolHmacSigner.verify(key, 37, 2L, body, mac));
    }
}
