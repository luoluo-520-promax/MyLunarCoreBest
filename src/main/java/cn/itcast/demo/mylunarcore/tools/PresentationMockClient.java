package cn.itcast.demo.mylunarcore.tools;

import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.protocol.BattleSystemProto;
import cn.itcast.demo.mylunarcore.protocol.SceneSystemProto;
import cn.itcast.demo.mylunarcore.scene.ScenePreloadService;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 表现层 Mock 客户端：生成服务端下发的特效/切图/取消窗口/QTE 样本，
 * 供策划/美术在无完整客户端时预览参数（Unity Editor 可粘贴 Base64）。
 */
public final class PresentationMockClient {

    private PresentationMockClient() {
    }

    /** DISSOLVE 切图遮罩样本（预加载命中）。 */
    public static byte[] sceneLoadMaskDissolve(int planeId, int floorId) {
        ScenePreloadService.MaskInfo mask = ScenePreloadService.MaskInfo.forPlane(
                planeId, floorId, "DISSOLVE", "layout_mobile_default", 9200);
        return SceneSystemProto.SceneLoadMaskInfo.newBuilder()
                .setIllustrationId(mask.illustrationId())
                .setCharacterAnimId(mask.characterAnimId())
                .setTipText(mask.tipText())
                .setMaskStyle(mask.maskStyle())
                .setTransitionType(mask.transitionType())
                .setRecommendedLayoutId(mask.recommendedLayoutId())
                .setHitRateBp(mask.hitRateBp())
                .setDurationMs(mask.durationMs())
                .build()
                .toByteArray();
    }

    /** RIFT 切图遮罩样本。 */
    public static byte[] sceneLoadMaskRift(int planeId, int floorId) {
        ScenePreloadService.MaskInfo mask = ScenePreloadService.MaskInfo.forPlane(
                planeId, floorId, "RIFT", "layout_pc_wide", 8500);
        return SceneSystemProto.SceneLoadMaskInfo.newBuilder()
                .setIllustrationId(mask.illustrationId())
                .setCharacterAnimId(mask.characterAnimId())
                .setTipText(mask.tipText())
                .setMaskStyle(mask.maskStyle())
                .setTransitionType(mask.transitionType())
                .setRecommendedLayoutId(mask.recommendedLayoutId())
                .setHitRateBp(mask.hitRateBp())
                .setDurationMs(mask.durationMs())
                .build()
                .toByteArray();
    }

    /** 打击感 Notify：含震屏强度与 FOV 冲击。 */
    public static byte[] battleFxWithCameraImpact(long battleId, int skillId, boolean kill) {
        float fov = kill ? 8.0f : 3.5f;
        float intensity = kill ? 0.95f : 0.45f;
        int shake = kill ? 3 : 2;
        return BattleSystemProto.BattleFxScNotify.newBuilder()
                .setBattleId(battleId)
                .setSkillId(skillId)
                .setCasterId(1)
                .addFx(BattleSystemProto.BattleFxMeta.newBuilder()
                        .setCritical(true)
                        .setKill(kill)
                        .setCameraShake(shake)
                        .setCameraShakeIntensity(intensity)
                        .setCameraFovImpact(fov)
                        .setTimeScale(kill ? 0.35f : 0.55f)
                        .setDamagePopupStyle(kill ? "crit_burst" : "large")
                        .setDisplayDamage(kill ? 9999 : 1200)
                        .setTargetEntityId(100)
                        .build())
                .setHapticIntensity(kill ? 90 : 60)
                .setWaveformId(kill ? 3 : 2)
                .build()
                .toByteArray();
    }

    /** 取消窗口 JSON Notify（Cmd 1130）。 */
    public static byte[] cancelWindowNotify(long battleId, int fromTier, int windowMs) {
        String json = "{\"battleId\":" + battleId
                + ",\"fromTier\":" + fromTier
                + ",\"windowMs\":" + windowMs
                + ",\"allowTiers\":[20,30,40]"
                + ",\"cmdHint\":\"CANCEL_WINDOW_SC_NOTIFY\"}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    /** QTE 触发 JSON（Cmd 1131）。 */
    public static byte[] battleQteNotify(long battleId, String qteId, long deadlineMs) {
        String json = "{\"battleId\":" + battleId
                + ",\"qteId\":\"" + qteId + "\""
                + ",\"deadlineMs\":" + deadlineMs
                + ",\"bonusType\":\"ACTION_POINT\""
                + ",\"bonusValue\":1}";
        return json.getBytes(StandardCharsets.UTF_8);
    }

    /** 供 Editor/HTTP 调试的一览表。 */
    public static Map<String, Object> previewCatalog() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("dissolveB64", Base64.getEncoder().encodeToString(sceneLoadMaskDissolve(20101, 1)));
        out.put("riftB64", Base64.getEncoder().encodeToString(sceneLoadMaskRift(20101, 2)));
        out.put("battleFxKillB64", Base64.getEncoder().encodeToString(battleFxWithCameraImpact(1L, 3001, true)));
        out.put("cancelWindowB64", Base64.getEncoder().encodeToString(cancelWindowNotify(1L, 10, 100)));
        out.put("qteB64", Base64.getEncoder().encodeToString(battleQteNotify(1L, "break_shield", System.currentTimeMillis() + 2500)));
        out.put("cmdIds", List.of(
                Map.of("name", "BATTLE_FX_SC_NOTIFY", "id", CmdIds.BATTLE_FX_SC_NOTIFY),
                Map.of("name", "CANCEL_WINDOW_SC_NOTIFY", "id", CmdIds.CANCEL_WINDOW_SC_NOTIFY),
                Map.of("name", "BATTLE_QTE_SC_NOTIFY", "id", CmdIds.BATTLE_QTE_SC_NOTIFY),
                Map.of("name", "SCENE_PING_SC_NOTIFY", "id", CmdIds.SCENE_PING_SC_NOTIFY),
                Map.of("name", "RECONNECT_CS_REQ", "id", CmdIds.RECONNECT_CS_REQ)
        ));
        return out;
    }
}
