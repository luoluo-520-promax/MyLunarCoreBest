package cn.itcast.demo.mylunarcore.net;

import cn.itcast.demo.mylunarcore.protocol.BattleSystemProto;
import cn.itcast.demo.mylunarcore.protocol.SceneSystemProto;
import cn.itcast.demo.mylunarcore.scene.ScenePreloadService;
import cn.itcast.demo.mylunarcore.tools.PresentationMockClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 客户端协议集成测试：模拟客户端回包解析，校验关键 Notify 字段（CI 可跑）。
 */
@DisplayName("客户端协议集成：表现层 Notify 解析")
class ClientProtocolIntegrationTest {

    @Test
    @DisplayName("SceneLoadMaskInfo.transition_type 应为 DISSOLVE/RIFT")
    void parseTransitionType() throws Exception {
        byte[] dissolve = PresentationMockClient.sceneLoadMaskDissolve(100, 1);
        SceneSystemProto.SceneLoadMaskInfo m1 = SceneSystemProto.SceneLoadMaskInfo.parseFrom(dissolve);
        assertEquals("DISSOLVE", m1.getTransitionType());
        assertEquals(ScenePreloadService.DISSOLVE_DURATION_MS, m1.getDurationMs());

        byte[] rift = PresentationMockClient.sceneLoadMaskRift(100, 2);
        SceneSystemProto.SceneLoadMaskInfo m2 = SceneSystemProto.SceneLoadMaskInfo.parseFrom(rift);
        assertEquals("RIFT", m2.getTransitionType());
        assertEquals(ScenePreloadService.RIFT_DURATION_MS, m2.getDurationMs());
    }

    @Test
    @DisplayName("BattleFx 应含 camera_fov_impact 与 shake_intensity")
    void parseBattleFxCamera() throws Exception {
        byte[] fx = PresentationMockClient.battleFxWithCameraImpact(9L, 3001, true);
        BattleSystemProto.BattleFxScNotify n = BattleSystemProto.BattleFxScNotify.parseFrom(fx);
        assertEquals(1, n.getFxCount());
        assertTrue(n.getFx(0).getCameraFovImpact() > 0);
        assertTrue(n.getFx(0).getCameraShakeIntensity() > 0);
        assertEquals(3, n.getFx(0).getCameraShake());
    }

    @Test
    @DisplayName("CancelWindow / QTE JSON Notify 可 UTF-8 解析")
    void parseJsonNotifies() {
        String cancel = new String(PresentationMockClient.cancelWindowNotify(1L, 10, 100), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(cancel.contains("windowMs"));
        assertTrue(cancel.contains("allowTiers"));
        String qte = new String(PresentationMockClient.battleQteNotify(1L, "kill", 1L), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(qte.contains("qteId"));
        // Base64 往返
        String b64 = Base64.getEncoder().encodeToString(PresentationMockClient.cancelWindowNotify(2L, 10, 100));
        assertTrue(Base64.getDecoder().decode(b64).length > 0);
    }

    @Test
    @DisplayName("CmdId 新号段唯一且落在期望区间")
    void newCmdIdsInRange() {
        assertEquals(1130, CmdIds.CANCEL_WINDOW_SC_NOTIFY);
        assertEquals(1131, CmdIds.BATTLE_QTE_SC_NOTIFY);
        assertEquals(1136, CmdIds.SCENE_PING_SC_NOTIFY);
        assertEquals(1138, CmdIds.RECONNECT_CS_REQ);
        assertEquals(1142, CmdIds.VERSION_THEME_SC_NOTIFY);
    }
}
