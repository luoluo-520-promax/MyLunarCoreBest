package cn.itcast.demo.mylunarcore.net;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AES-GCM IV/Nonce 防重放：同一会话内拒绝重复 IV。
 */
public final class KcpGcmNonceGuard {

    public static final AttributeKey<SeenIvWindow> SEEN_IVS =
            AttributeKey.valueOf("mylunarcore.kcp.seenIv");

    private static final int DEFAULT_WINDOW = 4096;

    private KcpGcmNonceGuard() {
    }

    /**
     * @return true 表示首次见到该 IV，可继续解密；false 表示重放，应丢弃
     */
    public static boolean accept(Channel channel, byte[] iv) {
        if (channel == null || iv == null || iv.length == 0) {
            return false;
        }
        SeenIvWindow window = channel.attr(SEEN_IVS).get();
        if (window == null) {
            window = new SeenIvWindow(DEFAULT_WINDOW);
            channel.attr(SEEN_IVS).set(window);
        }
        return window.add(Base64.getEncoder().encodeToString(iv));
    }

    static final class SeenIvWindow {
        private final int max;
        private final LinkedHashMap<String, Boolean> map;

        SeenIvWindow(int max) {
            this.max = Math.max(64, max);
            this.map = new LinkedHashMap<>(this.max, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > SeenIvWindow.this.max;
                }
            };
        }

        synchronized boolean add(String key) {
            if (map.containsKey(key)) {
                return false;
            }
            map.put(key, Boolean.TRUE);
            return true;
        }
    }
}
