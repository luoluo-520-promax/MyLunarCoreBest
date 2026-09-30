package cn.itcast.demo.mylunarcore.scene;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("SceneCollisionProxy 碰撞与阻挡")
class SceneCollisionProxyTest {

    @Test
    @DisplayName("进入阻挡区应纠正到合法点")
    void blockAndCorrect() {
        LunarCoreProperties props = new LunarCoreProperties();
        props.getAntiCheat().setCollisionCheckEnabled(true);
        SceneCollisionProxy proxy = new SceneCollisionProxy(props);
        proxy.registerPlaneBlockers(1, List.of(
                new SceneCollisionProxy.BlockerPolygon(List.of(
                        new float[]{-1f, -1f}, new float[]{1f, -1f},
                        new float[]{1f, 1f}, new float[]{-1f, 1f}))));
        SceneCollisionProxy.CollisionResult ok = proxy.validateMove(1, -5f, 0f, 0f, -3f, 0f, 0f);
        assertTrue(ok.legal());
        SceneCollisionProxy.CollisionResult blocked = proxy.validateMove(1, -5f, 0f, 0f, 0f, 0f, 0f);
        assertFalse(blocked.legal());
        assertTrue(blocked.penetrated());
        // 纠正点应远离阻挡中心
        assertTrue(Math.abs(blocked.correctedX()) > 0.5f || Math.abs(blocked.correctedZ()) > 0.5f
                || blocked.correctedX() < -0.5f);
    }
}
