// KCP 连接生命周期回调接口
package cn.itcast.demo.mylunarcore.net;

// 已解码业务包
import cn.itcast.demo.mylunarcore.net.GamePacket;
// KCP 会话对象
import io.jpower.kcp.netty.Ukcp;
// KCP 通道类型
import io.jpower.kcp.netty.UkcpChannel;
// Netty 上下文
import io.netty.channel.ChannelHandlerContext;

/**
 * 单条 KCP 连接的回调：在 {@link UkcpChannel} 所属 {@link io.netty.channel.EventLoop} 上串行调用（同连接收包/断开/错误同线程）。
 */
public interface KcpListener {

    /** 连接建立且拿到 Ukcp 实例时调用 */
    void onConnected(UkcpChannel channel, Ukcp ukcp);

    /** 收到一帧并已解码为 GamePacket 时调用 */
    void onReceive(ChannelHandlerContext ctx, Ukcp ukcp, GamePacket packet);

    /** 连接断开时调用 */
    void onDisconnected(UkcpChannel channel, Ukcp ukcp);
}
