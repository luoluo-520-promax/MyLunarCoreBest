package cn.itcast.demo.mylunarcore.net;

import cn.itcast.demo.mylunarcore.net.kcp.KcpBandwidthProbe;
import cn.itcast.demo.mylunarcore.net.kcp.KcpRetransmitAlgo;
import io.micrometer.core.instrument.MeterRegistry;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 客户端 RTT 上报监控：配合 {@link KcpRetransmitAlgo} 与 {@link KcpBandwidthProbe} 做拥塞决策。
 */
@Component
public class KcpRttMonitor {

    public static final AttributeKey<Long> LAST_RTT_MS = AttributeKey.valueOf("mylunarcore.kcp.lastRttMs");

    private final KcpRetransmitAlgo retransmitAlgo;
    private final KcpBandwidthProbe bandwidthProbe;
    private final ConcurrentHashMap<String, AtomicLong> channelRtt = new ConcurrentHashMap<>();
    private final LongAdder samples = new LongAdder();
    private final LongAdder totalRtt = new LongAdder();
    private final AtomicLong throttleDecisions = new AtomicLong();

    public KcpRttMonitor(KcpRetransmitAlgo retransmitAlgo,
                         KcpBandwidthProbe bandwidthProbe,
                         MeterRegistry meterRegistry) {
        this.retransmitAlgo = retransmitAlgo;
        this.bandwidthProbe = bandwidthProbe;
        meterRegistry.gauge("lunarcore.kcp.rtt_avg_ms", this, KcpRttMonitor::averageRttMs);
        meterRegistry.gauge("lunarcore.kcp.throttle_total", throttleDecisions, AtomicLong::get);
    }

    /** 单测便捷：内建带宽探测。 */
    public KcpRttMonitor(KcpRetransmitAlgo retransmitAlgo, MeterRegistry meterRegistry) {
        this(retransmitAlgo, new KcpBandwidthProbe(meterRegistry), meterRegistry);
    }

    /** 客户端心跳 / Ping 包上报 RTT。 */
    public void reportRtt(Channel channel, long rttMs) {
        if (channel == null || rttMs < 0) {
            return;
        }
        long clamped = Math.min(rttMs, 60_000L);
        channel.attr(LAST_RTT_MS).set(clamped);
        channelRtt.computeIfAbsent(channel.id().asShortText(), k -> new AtomicLong()).set(clamped);
        samples.increment();
        totalRtt.add(clamped);
    }

    public long lastRttMs(Channel channel) {
        if (channel == null) {
            return 0L;
        }
        Long v = channel.attr(LAST_RTT_MS).get();
        return v == null ? 0L : v;
    }

    public int recommendedIntervalMs(Channel channel, int lossPermille) {
        int base = retransmitAlgo.nextSendIntervalMs(lastRttMs(channel), lossPermille);
        KcpBandwidthProbe.CongestionAdvice advice =
                bandwidthProbe.advise(channel, lastRttMs(channel), lossPermille);
        return Math.max(base, advice.sendIntervalMs());
    }

    public boolean shouldThrottle(Channel channel, int inflightPackets) {
        boolean throttle = retransmitAlgo.shouldThrottle(lastRttMs(channel), inflightPackets);
        if (!throttle) {
            throttle = bandwidthProbe.shouldLimitUplink(channel, lastRttMs(channel), 0);
        }
        if (throttle) {
            throttleDecisions.incrementAndGet();
        }
        return throttle;
    }

    /** 结合丢包的完整节流判定。 */
    public boolean shouldThrottle(Channel channel, int inflightPackets, int lossPermille) {
        boolean throttle = retransmitAlgo.shouldThrottle(lastRttMs(channel), inflightPackets);
        if (!throttle) {
            throttle = bandwidthProbe.shouldLimitUplink(channel, lastRttMs(channel), lossPermille);
        }
        if (throttle) {
            throttleDecisions.incrementAndGet();
        }
        return throttle;
    }

    public KcpBandwidthProbe bandwidthProbe() {
        return bandwidthProbe;
    }

    public double averageRttMs() {
        long n = samples.sum();
        return n == 0 ? 0.0 : (double) totalRtt.sum() / n;
    }

    public KcpRetransmitAlgo.Profile algoProfile() {
        return retransmitAlgo.profile();
    }
}
