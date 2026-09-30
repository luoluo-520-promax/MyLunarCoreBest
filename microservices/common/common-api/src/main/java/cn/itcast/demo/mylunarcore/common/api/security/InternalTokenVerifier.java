// 声明当前包：微服务间内部通信的安全工具
package cn.itcast.demo.mylunarcore.common.api.security;

// 字符集：将 Token 字符串编码为字节
import java.nio.charset.StandardCharsets;
// 消息摘要：提供常量时间比较（MessageDigest.isEqual）
import java.security.MessageDigest;

/**
 * 微服务侧内部 Token 恒定时间比较。
 *
 * <p>用于校验服务间调用携带的内部 Token（如 X-Internal-Token）。使用
 * {@link MessageDigest#isEqual} 进行常量时间比较，避免普通 equals 比较在
 * 前缀失败时产生可被时序攻击利用的时间差。</p>
 */
public final class InternalTokenVerifier {

    /**
     * 私有构造器：本类为纯静态工具类，禁止实例化。
     */
    private InternalTokenVerifier() {
    }

    /**
     * 常量时间比较：expected 与 provided 是否完全一致。
     *
     * @param expected 期望的内部 Token（配置值），为空或空白时直接判定不匹配
     * @param provided 调用方提供的 Token（请求头中的值），为空时直接判定不匹配
     * @return true 表示两者字节级完全相等；false 表示缺失、为空或不等
     */
    public static boolean matches(String expected, String provided) {
        // 任一为空则直接失败，避免与空白串比较
        if (expected == null || expected.isBlank() || provided == null) {
            return false;
        }
        // 统一按 UTF-8 转字节后做常量时间比较，防止时序侧信道
        byte[] a = expected.getBytes(StandardCharsets.UTF_8);
        byte[] b = provided.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(a, b);
    }
}
