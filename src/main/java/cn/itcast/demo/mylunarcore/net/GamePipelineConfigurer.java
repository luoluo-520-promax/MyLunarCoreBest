package cn.itcast.demo.mylunarcore.net;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import io.netty.util.concurrent.EventExecutorGroup;

import java.time.Duration;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * TCP/KCP 共用的游戏协议 pipeline 装配策略表。
 * <p>
 * 固化顺序：帧解码 → 帧编码 → 背压 → 连接限流 → Trace → 幂等 → 终端业务。
 */
public final class GamePipelineConfigurer {

    private GamePipelineConfigurer() {
    }

    public static void configureCommonHandlers(ChannelPipeline pipeline,
                                               ChannelHandler backpressureHandler,
                                               ChannelHandler packetRateLimiterHandler,
                                               ChannelHandler traceContextHandler) {
        Objects.requireNonNull(pipeline, "pipeline");
        pipeline.addLast(new LunarFrameDecoder())
                .addLast(new LunarFrameEncoder())
                .addLast("packet-priority", new PacketPriorityOutboundHandler())
                .addLast("backpressure", backpressureHandler)
                .addLast("packet-rate-limit", packetRateLimiterHandler)
                .addLast("trace", traceContextHandler)
                .addLast("idempotency", new PacketIdempotencyHandler(Duration.ofSeconds(10), 2048));
    }

    public static void addBusinessHandler(ChannelPipeline pipeline,
                                          EventExecutorGroup businessGroup,
                                          ChannelHandler businessHandler) {
        if (businessGroup != null) {
            pipeline.addLast(businessGroup, "business", businessHandler);
        } else {
            pipeline.addLast("business", businessHandler);
        }
    }

    public static void addTerminal(ChannelPipeline pipeline, Supplier<ChannelHandler> terminalSupplier) {
        pipeline.addLast(terminalSupplier.get());
    }

    /** 供策略文档/测试断言使用的标准 handler 名称顺序（不含 ssl / terminal）。 */
    public static final String[] COMMON_HANDLER_NAMES = {
            "LunarFrameDecoder", "LunarFrameEncoder", "packet-priority", "backpressure", "packet-rate-limit", "trace", "idempotency"
    };
}
