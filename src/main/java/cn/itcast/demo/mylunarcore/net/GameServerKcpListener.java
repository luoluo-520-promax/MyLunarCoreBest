// KCP 收包与业务线程桥接
package cn.itcast.demo.mylunarcore.net;

// 与 TCP 共用的命令分发器
import cn.itcast.demo.mylunarcore.net.GamePacketDispatcher;
// 解码后的包
import cn.itcast.demo.mylunarcore.net.GamePacket;
// KCP 会话
import io.jpower.kcp.netty.Ukcp;
// KCP 通道
import io.jpower.kcp.netty.UkcpChannel;
// Netty 上下文
import io.netty.channel.ChannelHandlerContext;
// Channel 上存自定义属性
import io.netty.util.AttributeKey;
// 业务线程池（与 JDBC 等阻塞操作隔离）
import io.netty.util.concurrent.DefaultEventExecutorGroup;
// 注册为 Spring Bean
import org.springframework.stereotype.Component;

/**
 * 把 KCP 生命周期事件接到命令分发：收包仍在 EventLoop，业务在 {@link DefaultEventExecutorGroup} 执行，与 TCP 路径一致。
 */
@Component // 注册为 Spring Bean，供 KCP 管线引用
public class GameServerKcpListener implements KcpListener {

    // 挂在 Channel 上的 Ukcp 引用，避免重复反射
    static final AttributeKey<Ukcp> UKCP_KEY = AttributeKey.valueOf("kcpUkcp"); // AttributeKey 单例描述符

    // 按 cmdId 路由到各 PacketHandlers
    private final GamePacketDispatcher packetDispatcher; // 命令总线
    // 游戏业务专用线程池
    private final DefaultEventExecutorGroup gameBusinessExecutorGroup; // 阻塞业务隔离池

    /**
     * 构造器注入分发器与业务线程池。
     */
    public GameServerKcpListener(GamePacketDispatcher packetDispatcher,
                                 DefaultEventExecutorGroup gameBusinessExecutorGroup) {
        this.packetDispatcher = packetDispatcher; // 保存分发器
        this.gameBusinessExecutorGroup = gameBusinessExecutorGroup; // 保存线程池
    }

    /**
     * KCP 连接建立：缓存 {@link Ukcp} 实例到 Channel 属性。
     */
    @Override
    public void onConnected(UkcpChannel channel, Ukcp ukcp) {
        channel.attr(UKCP_KEY).set(ukcp); // 连接成功时缓存 Ukcp
    }

    /**
     * 收到解码后的包：异步投递到业务线程再分发。
     */
    @Override
    public void onReceive(ChannelHandlerContext ctx, Ukcp ukcp, GamePacket packet) {
        // 投递到业务线程池，避免在 I/O 线程里访问数据库
        gameBusinessExecutorGroup.execute(() -> packetDispatcher.dispatch(ctx, packet)); // Runnable 捕获当前 ctx/packet
    }

    /**
     * 连接断开：移除 Channel 上的 Ukcp 缓存。
     */
    @Override
    public void onDisconnected(UkcpChannel channel, Ukcp ukcp) {
        channel.attr(UKCP_KEY).remove(); // 清理属性，防止泄漏
    }
}
