// Pipeline 末尾：把 Netty 事件转给 KcpListener
package cn.itcast.demo.mylunarcore.net;

// 解码后的业务包
import cn.itcast.demo.mylunarcore.net.GamePacket;
// KCP 核心对象
import io.jpower.kcp.netty.Ukcp;
// KCP 通道
import io.jpower.kcp.netty.UkcpChannel;
// 通道上下文
import io.netty.channel.ChannelHandlerContext;
// 入站消息只处理 GamePacket 的基类
import io.netty.channel.SimpleChannelInboundHandler;
// 统一日志门面
import cn.itcast.demo.mylunarcore.common.AppLogger;
// 日志分类
import cn.itcast.demo.mylunarcore.common.LogCategory;
// SLF4J Logger
import org.slf4j.Logger;

/**
 * Netty Handler：在 {@link UkcpChannel} 的 EventLoop 上把连接/收包/断开事件转发给 {@link KcpListener}。
 */
public class KcpListenerBridgeHandler extends SimpleChannelInboundHandler<GamePacket> {

    private static final Logger log = AppLogger.logger(LogCategory.SYSTEM, KcpListenerBridgeHandler.class); // 本类日志

    // 实际业务回调（通常为 GameServerKcpListener）
    private final KcpListener listener; // 委托目标

    /**
     * 构造器指定要转发的 {@link KcpListener} 实现。
     */
    public KcpListenerBridgeHandler(KcpListener listener) {
        this.listener = listener; // 保存回调
    }

    /**
     * 通道激活：解析 {@link Ukcp} 并通知连接建立。
     */
    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        super.channelActive(ctx); // 先走父类默认逻辑
        UkcpChannel ch = (UkcpChannel) ctx.channel(); // 当前通道必然是 UkcpChannel
        Ukcp ukcp = UkcpChannelAccessor.ukcp(ch); // 反射取 Ukcp
        if (ukcp == null) { // 无法取得则视为异常环境
            log.warn("Ukcp not found on channel, closing {}", ch.remoteAddress()); // 告警对端地址
            ctx.close(); // 关闭连接
            return; // 不再通知 listener
        }
        listener.onConnected(ch, ukcp); // 回调：已连接
    }

    /**
     * 读入一帧 {@link GamePacket} 后转给监听器。
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, GamePacket packet) {
        UkcpChannel ch = (UkcpChannel) ctx.channel(); // 强转通道类型
        Ukcp ukcp = ch.attr(GameServerKcpListener.UKCP_KEY).get(); // 优先从 Channel 属性取缓存
        if (ukcp == null) {
            ukcp = UkcpChannelAccessor.ukcp(ch); // 属性缺失时再反射一次
        }
        listener.onReceive(ctx, ukcp, packet); // 回调：收到业务包
    }

    /**
     * 通道失活：通知断开并调用父类。
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        UkcpChannel ch = (UkcpChannel) ctx.channel(); // 当前 KCP 通道
        Ukcp ukcp = ch.attr(GameServerKcpListener.UKCP_KEY).get(); // 取出缓存 Ukcp
        if (ukcp == null) {
            ukcp = UkcpChannelAccessor.ukcp(ch); // 兜底反射
        }
        listener.onDisconnected(ch, ukcp); // 回调：已断开
        super.channelInactive(ctx); // 继续父类清理
    }

    /**
     * 异常捕获：记录日志并关闭会话。
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("KCP connection exception, remote={}", ctx.channel().remoteAddress(), cause);
        ctx.close(); // 异常后关闭 UDP/KCP 会话
    }
}
