package cn.itcast.demo.mylunarcore.net.kcp;

import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * KCP 带宽探测：结合丢包率与 RTT 动态调整 fastack/nodelay 建议，并限制上行。
 */
@Component
public class KcpBandwidthProbe {

    public static final AttributeKey<Long> EST_BANDWIDTH_KBPS =
            AttributeKey.valueOf("mylunarcore.kcp.estBandwidthKbps");

    public record CongestionAdvice(int sendIntervalMs, boolean fastAck, boolean noDelay,
                                   boolean limitUplink, int positionUpdateHz) {
    }

    private final ConcurrentHashMap<String, LongAdder> egressBytes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> ingressBytes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> windowStartMs = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> lastEstKbps = new ConcurrentHashMap<>();
    private final AtomicLong limitDecisions = new AtomicLong();

    public KcpBandwidthProbe(MeterRegistry meterRegistry) {
        meterRegistry.gauge("lunarcore.kcp.bandwidth_limit_total", limitDecisions, AtomicLong::get);
    }

    public void recordEgress(Channel channel, int bytes) {
        if (channel == null || bytes <= 0) {
            return;
        }
        String id = channel.id().asShortText();
        ensureWindow(id);
        egressBytes.computeIfAbsent(id, k -> new LongAdder()).add(bytes);
        refreshEstimate(channel, id);
    }

    public void recordIngress(Channel channel, int bytes) {
        if (channel == null || bytes <= 0) {
            return;
        }
        String id = channel.id().asShortText();
        ensureWindow(id);
        ingressBytes.computeIfAbsent(id, k -> new LongAdder()).add(bytes);
        refreshEstimate(channel, id);
    }

    public long estimatedKbps(Channel channel) {
        if (channel == null) {
            return 0L;
        }
        Long v = channel.attr(EST_BANDWIDTH_KBPS).get();
        if (v != null) {
            return v;
        }
        AtomicLong cached = lastEstKbps.get(channel.id().asShortText());
        return cached == null ? 0L : cached.get();
    }

    /**
     * 结合 RTT / 丢包 / 带宽估计给出拥塞参数建议。
     */
    public CongestionAdvice advise(Channel channel, long rttMs, int lossPermille) {
        long kbps = estimatedKbps(channel);
        boolean weak = rttMs > 200 || lossPermille > 50 || (kbps > 0 && kbps < 64);
        boolean extreme = rttMs > 400 || lossPermille > 100 || (kbps > 0 && kbps < 32);
        int interval;
        boolean fastAck;
        boolean noDelay;
        boolean limitUplink;
        int posHz;
        if (extreme) {
            interval = 20;
            fastAck = false;
            noDelay = true;
            limitUplink = true;
            posHz = 5;
            limitDecisions.incrementAndGet();
        } else if (weak) {
            interval = 15;
            fastAck = false;
            noDelay = true;
            limitUplink = kbps > 0 && kbps < 96;
            posHz = 10;
            if (limitUplink) {
                limitDecisions.incrementAndGet();
            }
        } else if (rttMs < 50 && lossPermille < 10) {
            interval = 10;
            fastAck = true;
            noDelay = true;
            limitUplink = false;
            posHz = 20;
        } else {
            interval = 20;
            fastAck = true;
            noDelay = false;
            limitUplink = false;
            posHz = 15;
        }
        return new CongestionAdvice(interval, fastAck, noDelay, limitUplink, posHz);
    }

    public boolean shouldLimitUplink(Channel channel, long rttMs, int lossPermille) {
        return advise(channel, rttMs, lossPermille).limitUplink();
    }

    private void ensureWindow(String id) {
        windowStartMs.computeIfAbsent(id, k -> new AtomicLong(System.currentTimeMillis()));
    }

    private void refreshEstimate(Channel channel, String id) {
        AtomicLong start = windowStartMs.computeIfAbsent(id, k -> new AtomicLong(System.currentTimeMillis()));
        long now = System.currentTimeMillis();
        long elapsed = now - start.get();
        if (elapsed < 1000L) {
            return;
        }
        long out = egressBytes.getOrDefault(id, new LongAdder()).sumThenReset();
        long in = ingressBytes.getOrDefault(id, new LongAdder()).sumThenReset();
        start.set(now);
        long kbps = ((out + in) * 8L) / Math.max(1L, elapsed);
        lastEstKbps.computeIfAbsent(id, k -> new AtomicLong()).set(kbps);
        if (channel != null) {
            channel.attr(EST_BANDWIDTH_KBPS).set(kbps);
        }
    }
}
