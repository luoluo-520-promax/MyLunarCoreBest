package cn.itcast.demo.mylunarcore.player;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("SyncReason 同步原因枚举测试")
class SyncReasonTest {

    private static final Logger log = LoggerFactory.getLogger(SyncReasonTest.class);

    @Test
    @DisplayName("各枚举值应与协议码一致")
    void syncReasonCodesShouldMatchProtocol() {
        int loginCode = SyncReason.LOGIN.getCode();
        int dataChangeCode = SyncReason.DATA_CHANGE.getCode();
        int timerCode = SyncReason.TIMER.getCode();

        log.info("同步原因码校验: LOGIN={}, DATA_CHANGE={}, TIMER={}",
                loginCode, dataChangeCode, timerCode);
        assertEquals(0, loginCode);
        assertEquals(1, dataChangeCode);
        assertEquals(2, timerCode);
    }
}
