package cn.itcast.demo.mylunarcore.assist;

import cn.itcast.demo.mylunarcore.common.api.assist.AssistAskRequest;
import cn.itcast.demo.mylunarcore.common.api.assist.AssistAskResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 微服务侧 {@link RemoteAssistEngine} 规则评估单测：
 * 根据请求中的任务/活动快照生成 tipId 与答案文案，source 固定为 rule。
 */
@DisplayName("ai-assist-service 规则引擎")
class RemoteAssistEngineTest {

    /**
     * 场景：玩家有 status=2（可领奖）的任务「初探空间站」。
     * 期望：retcode=0、source=rule、hints 含 tipId=quest_claim，答案正文含任务名。
     */
    @Test
    @DisplayName("可提交任务应生成建议")
    void shouldSuggestQuestClaim() {
        RemoteAssistEngine engine = new RemoteAssistEngine();
        AssistAskRequest req = new AssistAskRequest(
                1001L,
                "下一步做什么",
                "quest", // 意图域：任务
                Map.of("level", 10),
                List.of(new AssistAskRequest.QuestSnapshot(10001, 2, "初探空间站")),
                List.of(),
                List.of()
        );
        AssistAskResponse rsp = engine.evaluate(req);
        assertEquals(0, rsp.retcode());
        assertEquals("rule", rsp.source());
        assertTrue(rsp.relatedHints().stream().anyMatch(h -> "quest_claim".equals(h.tipId())));
        assertTrue(rsp.answer().contains("初探空间站"));
    }

    /**
     * 场景：活动「夏日庆典」将在 1 小时内结束。
     * 期望：relatedHints 含 tipId=activity_ending，提示玩家抓紧参与。
     */
    @Test
    @DisplayName("临近结束活动应提示")
    void shouldSuggestEndingActivity() {
        RemoteAssistEngine engine = new RemoteAssistEngine();
        long end = Instant.now().getEpochSecond() + 3600; // 一小时后结束
        AssistAskRequest req = new AssistAskRequest(
                1001L,
                "有什么活动",
                "activity",
                Map.of(),
                List.of(),
                List.of(new AssistAskRequest.ActivitySnapshot(5000701, "夏日庆典", end, "desc")),
                List.of()
        );
        AssistAskResponse rsp = engine.evaluate(req);
        assertTrue(rsp.relatedHints().stream().anyMatch(h -> "activity_ending".equals(h.tipId())));
    }
}
