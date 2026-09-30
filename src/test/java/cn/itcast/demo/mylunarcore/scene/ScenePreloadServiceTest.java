package cn.itcast.demo.mylunarcore.scene;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("场景后台预加载")
class ScenePreloadServiceTest {

    @Test
    @DisplayName("beginPreload 后同 Plane 应就绪，切图可消费")
    void preloadThenConsume() {
        ScenePreloadService svc = new ScenePreloadService();
        ScenePreloadService.PreloadState state = svc.beginPreload(1001L, 201, 1, 3);
        assertNotNull(state);
        assertTrue(svc.isReady(1001L, 201, 1));
        assertFalse(svc.isReady(1001L, 202, 1));
        assertTrue(state.assetKeys().stream().anyMatch(k -> k.contains("lod0")));
        assertEquals("hybrid", state.mask().maskStyle());
        assertTrue(List.of("DISSOLVE", "RIFT", "FADE").contains(state.mask().transitionType())
                || "BLACK".equals(state.mask().transitionType())
                || state.mask().durationMs() > 0);
        assertNotNull(svc.consumeIfMatch(1001L, 201, 1));
        assertFalse(svc.isReady(1001L, 201, 1));
    }

    @Test
    @DisplayName("方向预判在扩大半径内朝向传送门时触发")
    void predictiveApproach() {
        ScenePreloadService svc = new ScenePreloadService();
        // 传送门在 (100,100) r=18；玩家在约 20m 外朝向门
        var hit = svc.detectApproach(1, 85f, 85f, 1f, 1f, true);
        assertNotNull(hit);
        assertEquals(2, hit.toPlaneId());
        assertNull(svc.detectApproach(1, 85f, 85f, -1f, -1f, true));
        assertEquals("RIFT", svc.resolveTransitionType(1L, true));
        assertEquals("BLACK", svc.resolveTransitionType(1L, false));
        for (int i = 0; i < 10; i++) {
            svc.recordOutcome(2L, true);
        }
        assertEquals("DISSOLVE", svc.resolveTransitionType(2L, true));
    }
}
