package cn.itcast.demo.mylunarcore.net;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageCodec;
import io.netty.util.AttributeKey;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;

/**
 * KCP 应用层 AES-GCM 加密：在无法部署 DTLS 时，对帧 payload 做会话级对称加密。
 * 会话密钥应在 TCP/TLS 登录成功后经安全通道下发，写入 Channel Attribute。
 */
public class KcpSessionCryptoCodec extends MessageToMessageCodec<ByteBuf, ByteBuf> {

    public static final AttributeKey<byte[]> SESSION_KEY =
            AttributeKey.valueOf("mylunarcore.kcp.sessionKey");

    private static final int IV_LEN = 12;
    private static final int TAG_LEN = 128;

    private final boolean enabled;
    private final SecureRandom random = new SecureRandom();

    public KcpSessionCryptoCodec(LunarCoreProperties properties) {
        this.enabled = properties.getKcpCrypto().isEnabled();
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) throws Exception {
        if (!enabled) {
            out.add(msg.retain());
            return;
        }
        byte[] key = ctx.channel().attr(SESSION_KEY).get();
        if (key == null) {
            out.add(msg.retain());
            return;
        }
        byte[] plain = new byte[msg.readableBytes()];
        msg.getBytes(msg.readerIndex(), plain);
        byte[] iv = new byte[IV_LEN];
        random.nextBytes(iv);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_LEN, iv));
        byte[] cipherText = cipher.doFinal(plain);
        ByteBuf buf = ctx.alloc().buffer(IV_LEN + cipherText.length);
        buf.writeBytes(iv);
        buf.writeBytes(cipherText);
        out.add(buf);
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) throws Exception {
        if (!enabled) {
            out.add(msg.retain());
            return;
        }
        byte[] key = ctx.channel().attr(SESSION_KEY).get();
        if (key == null || msg.readableBytes() <= IV_LEN) {
            out.add(msg.retain());
            return;
        }
        byte[] all = new byte[msg.readableBytes()];
        msg.getBytes(msg.readerIndex(), all);
        byte[] iv = Arrays.copyOfRange(all, 0, IV_LEN);
        if (!KcpGcmNonceGuard.accept(ctx.channel(), iv)) {
            // 重复 IV：疑似重放，丢弃本帧
            return;
        }
        byte[] cipherText = Arrays.copyOfRange(all, IV_LEN, all.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_LEN, iv));
        byte[] plain = cipher.doFinal(cipherText);
        ByteBuf buf = ctx.alloc().buffer(plain.length);
        buf.writeBytes(plain);
        out.add(buf);
    }
}
