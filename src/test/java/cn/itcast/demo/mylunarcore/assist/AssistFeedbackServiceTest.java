package cn.itcast.demo.mylunarcore.assist;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * {@link AssistFeedbackService}：玩家对助手回答点「有用/无用」后的计数与原因分布。
 */
@DisplayName("AssistFeedbackService 反馈闭环测试")
class AssistFeedbackServiceTest {

    private static final Logger log = LoggerFactory.getLogger(AssistFeedbackServiceTest.class);

    /**
     * 空 requestId → retcode=5；合法提交 → 0；uid=0 → 1。
     * 三次有效反馈（1 有用 + 2 无用）后 usefulRate 约 1/3；reason 统计 inaccurate/other 各 1。
     */
    @Test
    @DisplayName("有效反馈应累计有用率；非法 requestId 应失败")
    void shouldTrackUsefulRateAndRejectBadRequestId() {
        AssistFeedbackService service = new AssistFeedbackService(
                mock(cn.itcast.demo.mylunarcore.common.BusinessMetrics.class),
                mock(cn.itcast.demo.mylunarcore.analytics.AnalyticsEventPublisher.class));
        int emptyId = service.submit(1L, "", true, "other").retcode(); // 缺 requestId
        int useful = service.submit(1L, "req-1", true, "other").retcode();
        int inaccurate = service.submit(1L, "req-2", false, "inaccurate").retcode();
        int weird = service.submit(1L, "req-3", false, "weird").retcode(); // 未知原因仍记入无用，reason 归 other
        int badUid = service.submit(0L, "req-4", true, "other").retcode();

        log.info("反馈闭环校验: emptyId={}, useful={}, inaccurate={}, weird={}, badUid={}, "
                        + "feedbackCount={}, usefulCount={}, notUsefulCount={}, usefulRate={}, reasons={}",
                emptyId, useful, inaccurate, weird, badUid,
                service.getFeedbackCount(), service.getUsefulCount(), service.getNotUsefulCount(),
                service.usefulRate(), service.reasonSnapshot());

        assertEquals(5, emptyId);
        assertEquals(0, useful);
        assertEquals(0, inaccurate);
        assertEquals(0, weird);
        assertEquals(1, badUid);
        assertEquals(3, service.getFeedbackCount());
        assertEquals(1, service.getUsefulCount());
        assertEquals(2, service.getNotUsefulCount());
        assertTrue(service.usefulRate() > 0.3 && service.usefulRate() < 0.4); // ≈0.333
        assertEquals(1L, service.reasonSnapshot().get("inaccurate"));
        assertEquals(1L, service.reasonSnapshot().get("other"));
    }
}
