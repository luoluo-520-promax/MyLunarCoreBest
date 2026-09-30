// 单连接入站包频率限制 Handler
package cn.itcast.demo.mylunarcore.net;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.AttributeKey;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 连接级入站包频率限制。
 * <p>
 * 在 Pipeline 中位于帧解码之后、业务 Handler 之前，对每个 Channel 维护独立令牌桶，
 * 限制每秒可进入业务层的 GamePacket 数量，防止恶意客户端刷包占满
 * {@link io.netty.util.concurrent.DefaultEventExecutorGroup} 业务线程池。
 * 超限时直接关闭连接（比静默丢弃更能识别攻击源）。
 */
@ChannelHandler.Sharable
@Component
public class ConnectionPacketRateLimiterHandler extends ChannelInboundHandlerAdapter {

    /** Channel 属性键：存储该连接专属的 Bucket4j 令牌桶实例。 */
    static final AttributeKey<Bucket> BUCKET_KEY = AttributeKey.valueOf("packetRateBucket");

    private final LunarCoreProperties properties;

    public ConnectionPacketRateLimiterHandler(LunarCoreProperties properties) {
        this.properties = properties;
    }

    /**
     * 每条入站消息（已解码的 GamePacket 或原始 ByteBuf）消耗 1 个令牌。
     * 令牌耗尽时关闭 Channel，不再向下游传递。
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (properties.getRateLimit().isPacketEnabled()) {
            Bucket bucket = ctx.channel().attr(BUCKET_KEY).get();
            if (bucket == null) {
                bucket = createBucket(properties.getRateLimit().getPacketsPerSecond());
                ctx.channel().attr(BUCKET_KEY).set(bucket);
            }
            if (!bucket.tryConsume(1)) {
                ctx.close();
                return;
            }
        }
        super.channelRead(ctx, msg);
    }

    /**
     * 构造每秒补充 packetsPerSecond 个令牌的桶，容量等于速率（允许 1 秒突发）。
     */
    private static Bucket createBucket(int packetsPerSecond) {
        int capacity = Math.max(1, packetsPerSecond);
        Bandwidth limit = Bandwidth.classic(capacity, Refill.greedy(capacity, Duration.ofSeconds(1)));
        return Bucket.builder().addLimit(limit).build();
    }
}
