package cn.itcast.demo.mylunarcore.net;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.util.AttributeKey;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 带外优先级出站整形：战斗包优先于场景移动包写出，弱网下降低操作延迟。
 * <p>
 * 非 {@link GamePacket} 原样透传；队列满时丢弃最低优先级包。
 */
public class PacketPriorityOutboundHandler extends ChannelOutboundHandlerAdapter {

    public static final AttributeKey<Boolean> ENABLED = AttributeKey.valueOf("packetPriorityEnabled");

    private final int highCap;
    private final int normalCap;
    private final int lowCap;
    private final Queue<Pending> high = new ArrayDeque<>();
    private final Queue<Pending> normal = new ArrayDeque<>();
    private final Queue<Pending> low = new ArrayDeque<>();
    private final AtomicBoolean flushing = new AtomicBoolean(false);

    public PacketPriorityOutboundHandler() {
        this(64, 128, 256);
    }

    public PacketPriorityOutboundHandler(int highCap, int normalCap, int lowCap) {
        this.highCap = Math.max(8, highCap);
        this.normalCap = Math.max(16, normalCap);
        this.lowCap = Math.max(32, lowCap);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (!(msg instanceof GamePacket packet)) {
            ctx.write(msg, promise);
            return;
        }
        Boolean enabled = ctx.channel().attr(ENABLED).get();
        if (enabled != null && !enabled) {
            ctx.write(msg, promise);
            return;
        }
        PacketPriority p = PacketPriority.ofCmdId(packet.getCmdId());
        Queue<Pending> q = switch (p) {
            case HIGH -> high;
            case NORMAL -> normal;
            case LOW -> low;
        };
        int cap = switch (p) {
            case HIGH -> highCap;
            case NORMAL -> normalCap;
            case LOW -> lowCap;
        };
        if (q.size() >= cap) {
            Pending dropped = q.poll();
            if (dropped != null) {
                dropped.promise.tryFailure(new IllegalStateException("priority_queue_overflow"));
            }
        }
        q.offer(new Pending(packet, promise));
        // 不在 write 时立即排空，等 flush 按 HIGH→NORMAL→LOW 统一写出
    }

    @Override
    public void flush(ChannelHandlerContext ctx) {
        flushQueues(ctx);
        ctx.flush();
    }

    private void flushQueues(ChannelHandlerContext ctx) {
        if (!flushing.compareAndSet(false, true)) {
            return;
        }
        try {
            drain(ctx, high);
            drain(ctx, normal);
            drain(ctx, low);
        } finally {
            flushing.set(false);
        }
    }

    private void drain(ChannelHandlerContext ctx, Queue<Pending> q) {
        Pending p;
        while ((p = q.poll()) != null) {
            ctx.write(p.packet, p.promise);
        }
    }

    private record Pending(GamePacket packet, ChannelPromise promise) {
    }
}
