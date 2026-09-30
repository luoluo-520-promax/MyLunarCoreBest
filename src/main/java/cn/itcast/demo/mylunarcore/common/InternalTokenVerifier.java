package cn.itcast.demo.mylunarcore.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 内部共享密钥恒定时间比较，避免 {@code String.equals} 时序侧信道。
 */
public final class InternalTokenVerifier {

    private InternalTokenVerifier() {
    }

    /**
     * @return true 当两边均非空且字节恒等
     */
    public static boolean matches(String expected, String provided) {
        if (expected == null || expected.isBlank() || provided == null) {
            return false;
        }
        byte[] a = expected.getBytes(StandardCharsets.UTF_8);
        byte[] b = provided.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }

    /**
     * 双密钥轮换：current 或 previous（宽限期）任一匹配即通过。
     */
    public static boolean matchesAny(String current, String previous, String provided) {
        return matches(current, provided) || matches(previous, provided);
    }
}
