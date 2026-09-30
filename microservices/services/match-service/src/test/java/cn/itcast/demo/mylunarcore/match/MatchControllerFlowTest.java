package cn.itcast.demo.mylunarcore.match;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Match 全局评分队列流程")
class MatchControllerFlowTest {

    @Test
    void enqueueTwoPlayersShouldMatchAndReturnZoneId() {
        MatchController controller = new MatchController();
        Map<String, Object> a = controller.enqueueHttp(Map.of(
                "playerId", 1001, "mode", 1, "level", 10, "power", 1000));
        assertTrue((Boolean) a.get("ok"));
        assertFalse((Boolean) a.get("matched"));

        Map<String, Object> b = controller.enqueueHttp(Map.of(
                "playerId", 1002, "mode", 1, "level", 11, "power", 1100));
        assertTrue((Boolean) b.get("ok"));
        assertTrue((Boolean) b.get("matched"));
        assertTrue(((Number) b.get("zoneId")).intValue() > 0);
    }

    @Test
    void cancelRemovesFromQueue() {
        MatchController controller = new MatchController();
        controller.enqueueHttp(Map.of("playerId", 7, "mode", 2, "level", 1, "power", 1));
        assertTrue((Boolean) controller.cancelHttp(Map.of("playerId", 7)).get("ok"));
        assertFalse((Boolean) controller.cancelHttp(Map.of("playerId", 7)).get("ok"));
    }

    @Test
    void healthReportsEngine() {
        Map<String, Object> health = new MatchController().health();
        assertEquals("match-service", health.get("service"));
        assertEquals("sorted-score-queue", health.get("engine"));
    }
}
