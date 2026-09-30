package cn.itcast.demo.mylunarcore.net.kcp;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 固定间隔重传策略实现（默认）：发送间隔恒定为配置值，不随 RTT / 丢包退避，
 * 行为简单可预期，联调和压测基准场景友好。
 * <p>
 * 装配条件：配置键 {@code lunarcore.kcp.retransmit.algo=fixed} 或未配置
 * （matchIfMissing = true，即默认策略）。
 */
@Component
@ConditionalOnProperty(prefix = "lunarcore.kcp.retransmit", name = "algo", havingValue = "fixed")
public class FixedIntervalKcpRetransmitAlgo implements KcpRetransmitAlgo {

    /** 固定发送间隔（毫秒），配置键 lunarcore.kcp.interval，默认 20ms；下限 10ms。 */
    private final int intervalMs;

    /**
     * 构造器：读取间隔配置并做下限钳制，防止配置过小（<10ms）导致发送过于密集。
     */
    public FixedIntervalKcpRetransmitAlgo(
            @org.springframework.beans.factory.annotation.Value("${lunarcore.kcp.interval:20}") int intervalMs) {
        this.intervalMs = Math.max(10, intervalMs);
    }

    /**
     * 固定策略：无论 RTT / 丢包如何变化，始终返回固定的发送间隔。
     */
    @Override
    public int nextSendIntervalMs(long rttMs, int lossPermille) {
        return intervalMs;
    }

    /**
     * 固定策略的节流判断：仅当在途未确认包数超过 512 时才暂缓发送，
     * 阈值设置较宽松，保证固定策略下发送吞吐优先。
     */
    @Override
    public boolean shouldThrottle(long rttMs, int inflightPackets) {
        return inflightPackets > 512;
    }

    /** @return 本实现标识为 FIXED。 */
    @Override
    public Profile profile() {
        return Profile.FIXED;
    }
}
