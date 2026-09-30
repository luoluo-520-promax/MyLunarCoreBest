package cn.itcast.demo.mylunarcore.net;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.net.kcp.AdaptiveKcpRetransmitAlgo;
import cn.itcast.demo.mylunarcore.net.kcp.FixedIntervalKcpRetransmitAlgo;
import cn.itcast.demo.mylunarcore.net.kcp.KcpRetransmitAlgo;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * KCP 重传策略 + RTT 上报监控。
 */
@DisplayName("KCP 拥塞与 RTT 监控流程")
class KcpRetransmitAndRttFlowTest {

    @Test
    @DisplayName("fixed 策略间隔恒定，高 inflight 才节流")
    void fixedAlgoBehavior() {
        FixedIntervalKcpRetransmitAlgo algo = new FixedIntervalKcpRetransmitAlgo(20);
        assertEquals(KcpRetransmitAlgo.Profile.FIXED, algo.profile());
        assertEquals(20, algo.nextSendIntervalMs(500, 100));
        assertFalse(algo.shouldThrottle(500, 10));
        assertTrue(algo.shouldThrottle(10, 600));
    }

    @Test
    @DisplayName("adaptive：强网压低间隔，弱网保短间隔；高 inflight 才节流")
    void adaptiveAlgoThrottlesOnBadNetwork() {
        AdaptiveKcpRetransmitAlgo algo = new AdaptiveKcpRetransmitAlgo(20, 250, 256, true, 50, 200);
        assertEquals(KcpRetransmitAlgo.Profile.ADAPTIVE, algo.profile());
        int strong = algo.nextSendIntervalMs(30, 0);
        int mid = algo.nextSendIntervalMs(100, 0);
        int weak = algo.nextSendIntervalMs(250, 0);
        assertTrue(strong <= mid);
        assertTrue(weak <= 15);
        // 弱网不因单纯高 RTT 掐死，需高 inflight
        assertFalse(algo.shouldThrottle(600, 10));
        assertTrue(algo.shouldThrottle(100, 300));
        assertTrue(algo.shouldThrottle(600, 200));
    }

    @Test
    @DisplayName("RTT 上报后监控应给出推荐间隔与平均值")
    void rttMonitorReportsAndAverages() {
        KcpRetransmitAlgo algo = new AdaptiveKcpRetransmitAlgo(20, 250, 256, true, 50, 200);
        KcpRttMonitor monitor = new KcpRttMonitor(algo, new SimpleMeterRegistry());
        EmbeddedChannel ch = new EmbeddedChannel();

        monitor.reportRtt(ch, 100);
        monitor.reportRtt(ch, 200);
        // samples=2, total=300 → avg 150；channel 最近一次 RTT=200
        assertEquals(150.0, monitor.averageRttMs(), 0.01);
        assertEquals(200L, monitor.lastRttMs(ch));
        assertTrue(monitor.recommendedIntervalMs(ch, 0) >= 20);
        assertEquals(KcpRetransmitAlgo.Profile.ADAPTIVE, monitor.algoProfile());
    }

    @Test
    @DisplayName("协议 HMAC 属性默认关闭，开启后签名可验")
    void protocolHmacPropertyGate() {
        LunarCoreProperties props = new LunarCoreProperties();
        assertFalse(ProtocolHmacSigner.isEnabled(props));
        props.getProtocolHmac().setEnabled(true);
        assertTrue(ProtocolHmacSigner.isEnabled(props));
    }
}
