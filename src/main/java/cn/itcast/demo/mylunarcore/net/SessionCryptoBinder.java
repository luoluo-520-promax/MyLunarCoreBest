package cn.itcast.demo.mylunarcore.net;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import io.netty.channel.Channel;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * 登录成功后生成会话密钥，写入 Channel Attribute，供 KCP AES-GCM 与 Protocol HMAC 共用。
 */
@Component
public class SessionCryptoBinder {

    private final LunarCoreProperties properties;
    private final SecureRandom random = new SecureRandom();

    public SessionCryptoBinder(LunarCoreProperties properties) {
        this.properties = properties;
    }

    /**
     * @return Base64 编码的会话密钥；若加密与 HMAC 均未启用则返回空串
     */
    public String bindNewSessionKey(Channel channel) {
        boolean needKey = properties.getKcpCrypto().isEnabled()
                || properties.getProtocolHmac().isEnabled();
        if (!needKey || channel == null) {
            return "";
        }
        int keyLen = Math.max(16, properties.getKcpCrypto().getKeyBytes());
        if (keyLen != 16 && keyLen != 24 && keyLen != 32) {
            keyLen = 16;
        }
        byte[] key = new byte[keyLen];
        random.nextBytes(key);
        channel.attr(KcpSessionCryptoCodec.SESSION_KEY).set(key);
        channel.attr(ProtocolHmacSigner.SESSION_HMAC_KEY).set(key);
        return Base64.getEncoder().encodeToString(key);
    }
}
