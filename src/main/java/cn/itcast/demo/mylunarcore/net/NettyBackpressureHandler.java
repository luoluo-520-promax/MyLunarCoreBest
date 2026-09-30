// Netty 出站写缓冲高水位背压：慢客户端时暂停 autoRead
package cn.itcast.demo.mylunarcore.net;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.springframework.stereotype.Component;

/**
 * Netty 出站背压 Handler。
 * <p>
 * 监听 {@link io.netty.channel.Channel#isWritable()} 变化：
 * 当出站写缓冲超过 {@link NettyChannelOptions#WRITE_BUFFER_WATER_MARK} 高水位（64KB）时
 * 将 {@code autoRead=false}，停止从 socket 读取新入站数据；
 * 待客户端消化下行、缓冲降至低水位（32KB）以下后恢复 {@code autoRead=true}。
 * 防止慢客户端或网络拥塞导致服务端为每条连接无限堆积待发送 ByteBuf，引发 OOM。
 */
@ChannelHandler.Sharable
@Component
public class NettyBackpressureHandler extends ChannelInboundHandlerAdapter {

    /**
     * Channel 可写状态变化回调：writable=false 表示出站队列超过高水位。
     */
    @Override
    public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
        ctx.channel().config().setAutoRead(ctx.channel().isWritable());
        super.channelWritabilityChanged(ctx);
    }
}
