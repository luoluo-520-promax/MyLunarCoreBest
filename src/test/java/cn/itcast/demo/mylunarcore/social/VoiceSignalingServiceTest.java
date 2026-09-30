package cn.itcast.demo.mylunarcore.social;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 组队语音信令。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code VoiceSignalingServiceTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("组队语音信令")
class VoiceSignalingServiceTest {

    /**
     * 验证点：加入房间应签发票据且可校验。
     * <p>测试方法 {@code joinIssuesValidTicket}：
     * <ul>
     *   <li>{@code assertTrue(r.success());}</li>
     *   <li>{@code assertTrue(svc.validate(r.ticket().roomId(), r.ticket().token()));}</li>
     *   <li>{@code assertEquals(false, svc.validate(r.ticket().roomId(), "bad"));}</li>
     * </ul>
     */
    @Test
    @DisplayName("加入房间应签发票据且可校验")
    void joinIssuesValidTicket() {
        VoiceSignalingService svc = new VoiceSignalingService();
        VoiceSignalingService.SignalResult r = svc.joinOrCreate(1001, "party-9");
        assertTrue(r.success());
        assertTrue(svc.validate(r.ticket().roomId(), r.ticket().token()));
        assertEquals(false, svc.validate(r.ticket().roomId(), "bad"));
    }
}
