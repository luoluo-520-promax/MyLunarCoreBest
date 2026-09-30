package cn.itcast.demo.mylunarcore.net;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import io.netty.util.AttributeKey;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * 基于命令号的动态盐值 HMAC：在明文帧魔数之外防篡改 / 防重放登录包中间人攻击。
 * <p>
 * 签名材料：{@code cmdId | seq | body}，密钥 = sessionKey ⊕ cmdSalt(cmdId)。
 * 与 {@link KcpSessionCryptoCodec} 正交：加密保护机密性，HMAC 保护完整性与命令绑定。
 */
public final class ProtocolHmacSigner {

    public static final AttributeKey<byte[]> SESSION_HMAC_KEY =
            AttributeKey.valueOf("mylunarcore.protocol.hmacKey");

    public static final int HMAC_LEN = 16;

    private ProtocolHmacSigner() {
    }

    public static boolean isEnabled(LunarCoreProperties properties) {
        return properties != null && properties.getProtocolHmac().isEnabled();
    }

    public static byte[] sign(byte[] sessionKey, int cmdId, long seq, byte[] body) {
        byte[] key = deriveCmdKey(sessionKey, cmdId);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            ByteBuffer buf = ByteBuffer.allocate(2 + 8 + (body == null ? 0 : body.length))
                    .order(ByteOrder.LITTLE_ENDIAN);
            buf.putShort((short) (cmdId & 0xFFFF));
            buf.putLong(seq);
            if (body != null && body.length > 0) {
                buf.put(body);
            }
            byte[] full = mac.doFinal(buf.array());
            return Arrays.copyOf(full, HMAC_LEN);
        } catch (Exception e) {
            throw new IllegalStateException("hmac sign failed", e);
        }
    }

    public static boolean verify(byte[] sessionKey, int cmdId, long seq, byte[] body, byte[] expected) {
        if (expected == null || expected.length != HMAC_LEN) {
            return false;
        }
        byte[] actual = sign(sessionKey, cmdId, seq, body);
        return MessageDigest.isEqual(actual, expected);
    }

    /** 命令号派生盐：避免同一会话密钥跨命令重放。 */
    static byte[] deriveCmdKey(byte[] sessionKey, int cmdId) {
        byte[] base = sessionKey == null ? new byte[16] : sessionKey;
        byte[] salt = ("cmd:" + (cmdId & 0xFFFF)).getBytes(StandardCharsets.UTF_8);
        byte[] out = Arrays.copyOf(base, Math.max(16, base.length));
        for (int i = 0; i < salt.length; i++) {
            out[i % out.length] ^= salt[i];
        }
        return out;
    }
}
