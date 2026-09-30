package cn.itcast.demo.mylunarcore.net.kcp;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 自适应重传 + fastack：强网（RTT&lt;50）降重传倍数提吞吐；弱网（RTT&gt;200）强制 nodelay 保战斗指令实时性。
 */
@Component
@ConditionalOnProperty(prefix = "lunarcore.kcp.retransmit", name = "algo", havingValue = "adaptive", matchIfMissing = true)
public class AdaptiveKcpRetransmitAlgo implements KcpRetransmitAlgo {

    private final int baseIntervalMs;
    private final long rttThrottleMs;
    private final int maxInflight;
    private final boolean fastAck;
    private final long strongRttMs;
    private final long weakRttMs;

    private volatile KcpCongestionProfile lastProfile = KcpCongestionProfile.normal();

    public AdaptiveKcpRetransmitAlgo(
            @org.springframework.beans.factory.annotation.Value("${lunarcore.kcp.interval:20}") int baseIntervalMs,
            @org.springframework.beans.factory.annotation.Value("${lunarcore.kcp.retransmit.rtt-throttle-ms:250}") long rttThrottleMs,
            @org.springframework.beans.factory.annotation.Value("${lunarcore.kcp.retransmit.max-inflight:256}") int maxInflight,
            @org.springframework.beans.factory.annotation.Value("${lunarcore.kcp.retransmit.fast-ack:true}") boolean fastAck,
            @org.springframework.beans.factory.annotation.Value("${lunarcore.kcp.retransmit.strong-rtt-ms:50}") long strongRttMs,
            @org.springframework.beans.factory.annotation.Value("${lunarcore.kcp.retransmit.weak-rtt-ms:200}") long weakRttMs) {
        this.baseIntervalMs = Math.max(10, baseIntervalMs);
        this.rttThrottleMs = Math.max(50L, rttThrottleMs);
        this.maxInflight = Math.max(32, maxInflight);
        this.fastAck = fastAck;
        this.strongRttMs = Math.max(20L, strongRttMs);
        this.weakRttMs = Math.max(strongRttMs + 1, weakRttMs);
    }

    @Override
    public int nextSendIntervalMs(long rttMs, int lossPermille) {
        KcpCongestionProfile profile = resolveProfile(rttMs);
        lastProfile = profile;
        int interval = profile.intervalMs();
        // 丢包率 > 5%：加倍发送间隔（封顶 300ms）
        if (lossPermille > 50) {
            interval = Math.min(300, interval * 2);
        }
        // 强网 + fastack：进一步压低间隔换吞吐
        if (fastAck && profile.fastAck() && rttMs < strongRttMs) {
            interval = Math.max(5, (interval * profile.rtoMultiplierBp()) / 100);
        }
        // 弱网：保持短间隔（nodelay 语义），不因 RTT 再拉长到卡手
        if (rttMs > weakRttMs) {
            interval = Math.min(interval, 15);
        } else if (rttMs > rttThrottleMs) {
            interval = (int) Math.min(200, interval + (rttMs - rttThrottleMs) / 4);
        }
        return interval;
    }

    @Override
    public boolean shouldThrottle(long rttMs, int inflightPackets) {
        if (inflightPackets >= maxInflight) {
            return true;
        }
        // 弱网宁可多发战斗包也不要整体掐死；仅极端 RTT 节流
        if (rttMs > weakRttMs) {
            return inflightPackets >= maxInflight / 2;
        }
        return rttMs > rttThrottleMs * 2;
    }

    @Override
    public Profile profile() {
        return Profile.ADAPTIVE;
    }

    public KcpCongestionProfile currentCongestionProfile() {
        return lastProfile;
    }

    public KcpCongestionProfile resolveProfile(long rttMs) {
        if (rttMs < strongRttMs) {
            return KcpCongestionProfile.strong();
        }
        if (rttMs > weakRttMs) {
            return KcpCongestionProfile.weak();
        }
        return KcpCongestionProfile.normal();
    }
}
