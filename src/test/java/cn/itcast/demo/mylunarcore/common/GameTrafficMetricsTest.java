package cn.itcast.demo.mylunarcore.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("GameTrafficMetrics 协议吞吐计数测试")
class GameTrafficMetricsTest {

    private static final Logger log = LoggerFactory.getLogger(GameTrafficMetricsTest.class);

    private GameTrafficMetrics metrics;

    @BeforeEach
    void setUp() {
        metrics = new GameTrafficMetrics();
        log.info("吞吐计数器初始化: initialCount={}", metrics.getPacketsHandled());
    }

    @Test
    @DisplayName("recordPacketHandled 应原子递增计数")
    void recordPacketHandledShouldIncrementCounter() {
        long before = metrics.getPacketsHandled();
        metrics.recordPacketHandled();
        metrics.recordPacketHandled();
        metrics.recordPacketHandled();
        long after = metrics.getPacketsHandled();

        log.info("包计数校验: countBefore={}, countAfter={}, delta={}", before, after, after - before);
        assertEquals(0L, before);
        assertEquals(3L, after);
    }
}
