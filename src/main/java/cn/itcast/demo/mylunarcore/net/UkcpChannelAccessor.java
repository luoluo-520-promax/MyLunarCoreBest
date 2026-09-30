// 通过反射读取 Ukcp 内部字段的工具
package cn.itcast.demo.mylunarcore.net;

// KCP 核心对象
import io.jpower.kcp.netty.Ukcp;
// Netty 通用通道接口
import io.netty.channel.Channel;

// 反射访问私有字段
import java.lang.reflect.Field;

/**
 * kcp-netty 把 {@link Ukcp} 存在 {@code UkcpServerChildChannel} 的私有字段里且无公开 getter；
 * 本类在每条通道上读一次，供 {@link cn.itcast.demo.mylunarcore.player.GameSession} 等使用。
 */
public final class UkcpChannelAccessor {

    // 缓存反射得到的 ukcp 字段，避免每次查找
    private static final Field UKCP_FIELD; // Ukcp 私有字段句柄

    // 类加载时一次性解析字段
    static {
        try {
            Class<?> clazz = Class.forName("io.jpower.kcp.netty.UkcpServerChildChannel");
            Field f = clazz.getDeclaredField("ukcp");
            f.setAccessible(true); // 突破 private
            UKCP_FIELD = f;
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e); // 库版本不兼容时快速失败
        }
    }

    /** 工具类禁止实例化 */
    private UkcpChannelAccessor() {
    }

    /**
     * 从 Netty Channel 取出底层 Ukcp；失败或 channel 为 null 时返回 null。
     */
    public static Ukcp ukcp(Channel channel) {
        if (channel == null) {
            return null; // 空输入直接返回
        }
        try {
            // 非 UkcpServerChildChannel（如 TCP / EmbeddedChannel）会抛 IllegalArgumentException
            return (Ukcp) UKCP_FIELD.get(channel);
        } catch (Exception e) {
            return null; // TCP 或测试通道无 Ukcp，视为不可用
        }
    }
}
