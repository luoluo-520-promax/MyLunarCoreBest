// TCP Pipeline 尾部：把 GamePacket 交给分发器
package cn.itcast.demo.mylunarcore.net;

// 解码后的业务包
import cn.itcast.demo.mylunarcore.net.GamePacket;
// 连接断开时的会话清理
import cn.itcast.demo.mylunarcore.player.ConnectionLifecycleService;
// Handler 可共享注解（同一实例挂到多条连接）
import io.netty.channel.ChannelHandler;
// 通道上下文
import io.netty.channel.ChannelHandlerContext;
// 只处理 GamePacket 类型入站消息的基类
import io.netty.channel.SimpleChannelInboundHandler;
// 统一日志门面
import cn.itcast.demo.mylunarcore.common.AppLogger;
// 日志分类
import cn.itcast.demo.mylunarcore.common.LogCategory;
// SLF4J Logger
import org.slf4j.Logger;
// Spring 组件注解
import org.springframework.stereotype.Component;

/**
 * TCP 管线末端：在 {@link io.netty.util.concurrent.DefaultEventExecutorGroup} 上执行，
 * 将协议解析后的业务与 JDBC、NIO I/O 线程分离。
 */
@ChannelHandler.Sharable // 无 per-channel 状态，可复用同一实例
@Component // 注册为 Spring Bean，供 Pipeline 注入
public class GameServerChannelHandler extends SimpleChannelInboundHandler<GamePacket> {

    private static final Logger log = AppLogger.logger(LogCategory.SYSTEM, GameServerChannelHandler.class); // 本类日志

    // 命令分发总线
    private final GamePacketDispatcher packetDispatcher;
    // 断连清理：落盘 + 注销会话
    private final ConnectionLifecycleService connectionLifecycleService;

    /**
     * 构造器注入全局唯一的 {@link GamePacketDispatcher}。
     */
    public GameServerChannelHandler(GamePacketDispatcher packetDispatcher,
                                    ConnectionLifecycleService connectionLifecycleService) {
        this.packetDispatcher = packetDispatcher; // 保存分发器引用
        this.connectionLifecycleService = connectionLifecycleService;
    }

    /**
     * 入站读到一帧 {@link GamePacket} 后交给分发器。
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, GamePacket packet) {
        packetDispatcher.dispatch(ctx, packet); // 每条入站包进入分发
    }

    /**
     * 连接断开：触发落盘与会话清理，避免幽灵在线。
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        connectionLifecycleService.onDisconnect(ctx.channel());
        super.channelInactive(ctx);
    }

    /**
     * 未捕获异常：记录日志并关闭连接。
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.warn("exceptionCaught, remote={}", ctx.channel().remoteAddress(), cause);
        ctx.close(); // 未捕获异常则关闭 TCP 连接
    }
}
