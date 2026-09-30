// 入站/出站时将 traceId 写入 SLF4J MDC 的 Duplex Handler
package cn.itcast.demo.mylunarcore.net;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

/**
 * 网络链路追踪 Handler。
 * <p>
 * 继承 {@link ChannelDuplexHandler} 同时拦截入站 read 与出站 write：
 * <ul>
 *   <li>入站 GamePacket：调用 {@link NetTraceContext#ensure} 生成 traceId 并写入 MDC</li>
 *   <li>出站任意消息：复用 Channel 上已缓存的 traceId 写入 MDC</li>
 * </ul>
 * finally 块中 MDC.remove 防止线程池复用时 traceId 泄漏到其他请求日志。
 */
@ChannelHandler.Sharable
@Component
public class NetTraceContextHandler extends ChannelDuplexHandler {

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof GamePacket packet) {
            MDC.put(NetTraceContext.MDC_KEY, NetTraceContext.next(ctx.channel(), packet));
        }
        try {
            super.channelRead(ctx, msg);
        } finally {
            MDC.remove(NetTraceContext.MDC_KEY);
        }
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        String traceId = ctx.channel().attr(NetTraceContext.TRACE_ID).get();
        if (traceId != null) {
            MDC.put(NetTraceContext.MDC_KEY, traceId);
        }
        try {
            super.write(ctx, msg, promise);
        } finally {
            MDC.remove(NetTraceContext.MDC_KEY);
        }
    }
}
