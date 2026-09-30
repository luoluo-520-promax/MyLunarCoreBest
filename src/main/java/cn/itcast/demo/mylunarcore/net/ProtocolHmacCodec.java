package cn.itcast.demo.mylunarcore.net;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageCodec;
import io.netty.util.AttributeKey;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 协议 HMAC 编解码：在 Lunar 帧 payload 末尾附加/校验 {@link ProtocolHmacSigner#HMAC_LEN} 字节签名。
 * 默认关闭；生产可与 KCP AES-GCM 同时开启。
 */
public class ProtocolHmacCodec extends MessageToMessageCodec<ByteBuf, ByteBuf> {

    public static final AttributeKey<AtomicLong> SEND_SEQ =
            AttributeKey.valueOf("mylunarcore.protocol.hmacSendSeq");
    public static final AttributeKey<Integer> LAST_CMD =
            AttributeKey.valueOf("mylunarcore.protocol.lastCmd");

    private final boolean enabled;

    public ProtocolHmacCodec(LunarCoreProperties properties) {
        this.enabled = ProtocolHmacSigner.isEnabled(properties);
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) throws Exception {
        if (!enabled) {
            out.add(msg.retain());
            return;
        }
        byte[] key = ctx.channel().attr(ProtocolHmacSigner.SESSION_HMAC_KEY).get();
        if (key == null) {
            out.add(msg.retain());
            return;
        }
        Integer cmd = ctx.channel().attr(LAST_CMD).get();
        int cmdId = cmd == null ? 0 : cmd;
        AtomicLong seqAttr = ctx.channel().attr(SEND_SEQ).get();
        if (seqAttr == null) {
            seqAttr = new AtomicLong();
            ctx.channel().attr(SEND_SEQ).set(seqAttr);
        }
        long seq = seqAttr.incrementAndGet();
        byte[] body = new byte[msg.readableBytes()];
        msg.getBytes(msg.readerIndex(), body);
        byte[] mac = ProtocolHmacSigner.sign(key, cmdId, seq, body);
        ByteBuf buf = ctx.alloc().buffer(body.length + ProtocolHmacSigner.HMAC_LEN + 8);
        buf.writeLongLE(seq);
        buf.writeBytes(body);
        buf.writeBytes(mac);
        out.add(buf);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) throws Exception {
        if (!enabled) {
            out.add(msg.retain());
            return;
        }
        byte[] key = ctx.channel().attr(ProtocolHmacSigner.SESSION_HMAC_KEY).get();
        if (key == null || msg.readableBytes() < 8 + ProtocolHmacSigner.HMAC_LEN) {
            out.add(msg.retain());
            return;
        }
        long seq = msg.readLongLE();
        int bodyLen = msg.readableBytes() - ProtocolHmacSigner.HMAC_LEN;
        if (bodyLen < 0) {
            ctx.close();
            return;
        }
        byte[] body = new byte[bodyLen];
        msg.readBytes(body);
        byte[] mac = new byte[ProtocolHmacSigner.HMAC_LEN];
        msg.readBytes(mac);
        Integer cmd = ctx.channel().attr(LAST_CMD).get();
        int cmdId = cmd == null ? 0 : cmd;
        if (!ProtocolHmacSigner.verify(key, cmdId, seq, body, mac)) {
            ctx.close();
            return;
        }
        ByteBuf buf = ctx.alloc().buffer(body.length);
        buf.writeBytes(body);
        out.add(buf);
    }
}
