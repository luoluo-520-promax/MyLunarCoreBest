package cn.itcast.demo.mylunarcore.settings;

import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 云端 UI 缩放 + 触摸热区配置：按设备分辨率下发，客户端保存生效。
 */
@Service
public class UiAdaptConfigService {

    public record UiScaleConfig(float uiScale, int designWidth, int designHeight, String deviceClass) {}

    public record TouchHeatmapConfig(int hotZoneExpandPx, boolean vibrateOnMiss, float amplifyScale,
                                     float joystickDeadzone, float skillPressThresholdMs,
                                     boolean swipeCancelSkill) {
        public TouchHeatmapConfig(int hotZoneExpandPx, boolean vibrateOnMiss, float amplifyScale) {
            this(hotZoneExpandPx, vibrateOnMiss, amplifyScale, 0.12f, 80f, true);
        }
    }

    private final Map<Integer, UiScaleConfig> scaleByPlayer = new ConcurrentHashMap<>();
    private final Map<Integer, TouchHeatmapConfig> heatmapByPlayer = new ConcurrentHashMap<>();
    private final GameSessionManager sessionManager;

    public UiAdaptConfigService(ObjectProvider<GameSessionManager> sessionProvider) {
        this.sessionManager = sessionProvider == null ? null : sessionProvider.getIfAvailable();
    }

    public UiScaleConfig resolveAndPush(int playerId, int screenWidth, int screenHeight, String inputMethod) {
        float scale = 1.0f;
        String deviceClass = "mobile";
        if (screenWidth >= 1920 || "pc_km".equals(inputMethod)) {
            scale = 1.15f;
            deviceClass = "pc";
        } else if (screenWidth >= 1200 || "console_gamepad".equals(inputMethod)) {
            scale = 1.05f;
            deviceClass = "console";
        } else if (screenWidth > 0 && screenWidth < 720) {
            scale = 0.92f;
            deviceClass = "mobile_narrow";
        }
        UiScaleConfig cfg = new UiScaleConfig(scale, Math.max(1, screenWidth), Math.max(1, screenHeight), deviceClass);
        scaleByPlayer.put(playerId, cfg);
        pushUiScale(playerId, cfg);

        boolean mobile = "mobile".equals(deviceClass) || "mobile_narrow".equals(deviceClass);
        TouchHeatmapConfig heat = new TouchHeatmapConfig(
                mobile ? 24 : 12,
                true,
                "mobile_narrow".equals(deviceClass) ? 1.25f : 1.1f,
                mobile ? 0.18f : 0.10f,
                mobile ? 100f : 60f,
                mobile);
        heatmapByPlayer.put(playerId, heat);
        pushHeatmap(playerId, heat);
        return cfg;
    }

    public UiScaleConfig getScale(int playerId) {
        return scaleByPlayer.get(playerId);
    }

    public TouchHeatmapConfig getHeatmap(int playerId) {
        return heatmapByPlayer.get(playerId);
    }

    public Map<String, Object> snapshot(int playerId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("uiScale", scaleByPlayer.get(playerId));
        m.put("touchHeatmap", heatmapByPlayer.get(playerId));
        return m;
    }

    private void pushUiScale(int playerId, UiScaleConfig cfg) {
        if (sessionManager == null) {
            return;
        }
        GameSession s = sessionManager.getOrNull(playerId);
        if (s == null) {
            return;
        }
        String json = "{\"uiScale\":" + cfg.uiScale()
                + ",\"designWidth\":" + cfg.designWidth()
                + ",\"designHeight\":" + cfg.designHeight()
                + ",\"deviceClass\":\"" + cfg.deviceClass() + "\"}";
        s.send(new GamePacket(CmdIds.UI_SCALE_SC_NOTIFY, json.getBytes(StandardCharsets.UTF_8)));
    }

    private void pushHeatmap(int playerId, TouchHeatmapConfig cfg) {
        if (sessionManager == null) {
            return;
        }
        GameSession s = sessionManager.getOrNull(playerId);
        if (s == null) {
            return;
        }
        String json = "{\"hotZoneExpandPx\":" + cfg.hotZoneExpandPx()
                + ",\"vibrateOnMiss\":" + cfg.vibrateOnMiss()
                + ",\"amplifyScale\":" + cfg.amplifyScale()
                + ",\"joystickDeadzone\":" + cfg.joystickDeadzone()
                + ",\"skillPressThresholdMs\":" + cfg.skillPressThresholdMs()
                + ",\"swipeCancelSkill\":" + cfg.swipeCancelSkill() + "}";
        s.send(new GamePacket(CmdIds.TOUCH_HEATMAP_SC_NOTIFY, json.getBytes(StandardCharsets.UTF_8)));
    }
}
