package cn.itcast.demo.mylunarcore.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Chat 削峰与离线缓存流程")
class ChatControllerFlowTest {

    @Test
    void worldMessageEntersBacklogAndCanDrain() {
        ChatController controller = new ChatController();
        Map<String, Object> pub = controller.world(Map.of("from", 1, "text", "hello world"));
        assertTrue((Boolean) pub.get("ok"));
        assertTrue(((Number) pub.get("backlog")).intValue() >= 1);

        Map<String, Object> drained = controller.drainWorld(10);
        assertTrue((Boolean) drained.get("ok"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> batch = (List<Map<String, Object>>) drained.get("batch");
        assertEquals(1, batch.size());
        assertEquals("hello world", batch.get(0).get("text"));
    }

    @Test
    void offlinePrivateFlushWithinSevenDays() {
        ChatController controller = new ChatController();
        Map<String, Object> pub = controller.privateMsg(Map.of(
                "from", 2, "to", 3, "text", "secret", "toOnline", "false"));
        assertTrue((Boolean) pub.get("ok"));

        Map<String, Object> offline = controller.flushOffline(3);
        assertTrue((Boolean) offline.get("ok"));
        assertEquals(7, offline.get("ttlDays"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> msgs = (List<Map<String, Object>>) offline.get("messages");
        assertEquals(1, msgs.size());
        assertEquals("secret", msgs.get(0).get("text"));
    }
}
