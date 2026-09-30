package cn.itcast.demo.mylunarcore.scene;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 每玩家下行带宽背压：超限时建议缩小 AOI / 降低同步频率。
 */
@Component
public class PlayerBandwidthLimiter {

    public record Advice(boolean backpressure, float aoiScale, int syncHzCap) {
        public static Advice normal() {
            return new Advice(false, 1.0f, 20);
        }
    }

    private final LunarCoreProperties properties;
    private final ConcurrentHashMap<Long, LongAdder> bytesWindow = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Long> windowStartMs = new ConcurrentHashMap<>();

    public PlayerBandwidthLimiter(LunarCoreProperties properties) {
        this.properties = properties;
    }

    public void recordEgress(long playerUid, int bytes) {
        if (playerUid <= 0 || bytes <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        windowStartMs.compute(playerUid, (k, start) -> {
            if (start == null || now - start >= 1000L) {
                bytesWindow.put(playerUid, new LongAdder());
                return now;
            }
            return start;
        });
        bytesWindow.computeIfAbsent(playerUid, k -> new LongAdder()).add(bytes);
    }

    public Advice advise(long playerUid) {
        long limit = Math.max(32L, properties.getZone().getPlayerBandwidthLimitKBps()) * 1024L;
        LongAdder adder = bytesWindow.get(playerUid);
        long used = adder == null ? 0L : adder.sum();
        if (used <= limit) {
            return Advice.normal();
        }
        double ratio = (double) used / (double) limit;
        if (ratio > 2.0) {
            return new Advice(true, 0.5f, 5);
        }
        if (ratio > 1.5) {
            return new Advice(true, 0.7f, 8);
        }
        return new Advice(true, 0.85f, 12);
    }

    public boolean shouldThrottle(long playerUid) {
        return advise(playerUid).backpressure();
    }
}
