package cn.itcast.demo.mylunarcore.player.controller;

import cn.itcast.demo.mylunarcore.common.api.ApiResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@DisplayName("Player Profile/Session 中心化流程")
class PlayerControllerFlowTest {

    @Test
    void putAndGetProfileAndSession() {
        PlayerController controller = new PlayerController();
        ApiResponse<Map<String, Object>> put = controller.putProfile(55L, Map.of(
                "nickname", "星核", "level", 40, "avatarId", 9));
        assertFalse(Boolean.TRUE.equals(put.data().get("scaffoldOnly")));
        assertEquals("星核", put.data().get("nickname"));

        ApiResponse<Map<String, Object>> get = controller.getPlayer(55L);
        assertEquals("星核", get.data().get("nickname"));
        assertEquals(40, ((Number) get.data().get("level")).intValue());

        controller.putSession(55L, Map.of("sessionToken", "tok-abc", "nodeId", "gs-1"));
        ApiResponse<Map<String, Object>> session = controller.getSession(55L);
        assertEquals("tok-abc", session.data().get("sessionToken"));
        assertEquals("central-store", session.data().get("source"));
        assertNotNull(session.data().get("nodeId"));
    }
}
