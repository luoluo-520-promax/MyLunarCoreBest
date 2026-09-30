package cn.itcast.demo.mylunarcore.scene;

import cn.itcast.demo.mylunarcore.settings.QualityPresetService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 设备热管理与帧率自适应：结合 QualityPreset，根据 SoC 温度 / FPS / 电量 / 机型决定渲染降级。
 */
@Service
public class DevicePerfProbeService {

    public static final float THERMAL_THRESHOLD_C = 45f;

    public record Probe(float socTempC, int fps, int batteryPercent, String deviceId, long reportedAtMs,
                        String cpuModel, int ramMb, String gpuModel) {
        public Probe(float socTempC, int fps, int batteryPercent, String deviceId, long reportedAtMs) {
            this(socTempC, fps, batteryPercent, deviceId, reportedAtMs, "", 0, "");
        }
    }

    public record Adjust(int renderTier, boolean disableNpcSilhouette, boolean reduceFarLod,
                         int targetFpsCap, String reason, float socTempC, String presetId,
                         int shadowQuality, int particleBudget, float viewDistance) {
        public Adjust(int renderTier, boolean disableNpcSilhouette, boolean reduceFarLod,
                      int targetFpsCap, String reason, float socTempC) {
            this(renderTier, disableNpcSilhouette, reduceFarLod, targetFpsCap, reason, socTempC,
                    "medium", 1, 150, 0.85f);
        }
    }

    private final Map<Long, Probe> lastProbe = new ConcurrentHashMap<>();
    private final QualityPresetService qualityPresetService;

    public DevicePerfProbeService() {
        this.qualityPresetService = null;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public DevicePerfProbeService(ObjectProvider<QualityPresetService> presetProvider) {
        this.qualityPresetService = presetProvider == null ? null : presetProvider.getIfAvailable();
    }

    public Optional<Adjust> report(long uid, float socTempC, int fps, int batteryPercent, String deviceId) {
        return report(uid, socTempC, fps, batteryPercent, deviceId, "", 0, "");
    }

    public Optional<Adjust> report(long uid, float socTempC, int fps, int batteryPercent, String deviceId,
                                   String cpuModel, int ramMb, String gpuModel) {
        if (uid <= 0 || !Float.isFinite(socTempC) || fps < 0 || batteryPercent < 0 || batteryPercent > 100) {
            return Optional.empty();
        }
        Probe probe = new Probe(socTempC, fps, Math.min(100, batteryPercent),
                deviceId == null ? "" : deviceId, System.currentTimeMillis(),
                cpuModel == null ? "" : cpuModel, Math.max(0, ramMb), gpuModel == null ? "" : gpuModel);
        lastProbe.put(uid, probe);
        return decide(probe, qualityPresetService);
    }

    public Optional<Probe> last(long uid) {
        return Optional.ofNullable(lastProbe.get(uid));
    }

    public static Optional<Adjust> decide(Probe probe) {
        return decide(probe, null);
    }

    public static Optional<Adjust> decide(Probe probe, QualityPresetService presets) {
        QualityPresetService.QualityPreset preset = null;
        if (presets != null) {
            preset = presets.resolve(new QualityPresetService.DeviceProfile(
                    probe.cpuModel(), probe.ramMb(), probe.gpuModel(), ""));
        }
        if (probe.socTempC() > THERMAL_THRESHOLD_C) {
            return Optional.of(merge(0, true, true, 30, "thermal", probe.socTempC(), preset));
        }
        if (probe.fps() > 0 && probe.fps() < 25) {
            return Optional.of(merge(0, true, true, 30, "low_fps", probe.socTempC(), preset));
        }
        if (probe.batteryPercent() > 0 && probe.batteryPercent() <= 15) {
            return Optional.of(merge(1, true, true, 40, "battery", probe.socTempC(), preset));
        }
        if (preset != null && "low".equalsIgnoreCase(preset.id())) {
            return Optional.of(merge(0, true, true, preset.targetFps(), "device_profile_low",
                    probe.socTempC(), preset));
        }
        if (probe.socTempC() > 40f || (probe.fps() > 0 && probe.fps() < 40)) {
            return Optional.of(merge(1, false, true, 45, "preemptive", probe.socTempC(), preset));
        }
        if (preset != null) {
            int tier = switch (preset.id().toLowerCase()) {
                case "ultra" -> 3;
                case "high" -> 2;
                case "low" -> 0;
                default -> 1;
            };
            return Optional.of(merge(tier, preset.forceDisableShadow(), preset.forceDisableParticles(),
                    preset.targetFps(), "device_profile", probe.socTempC(), preset));
        }
        return Optional.empty();
    }

    private static Adjust merge(int tier, boolean disableSilhouette, boolean reduceLod, int fpsCap,
                                String reason, float temp, QualityPresetService.QualityPreset preset) {
        if (preset == null) {
            return new Adjust(tier, disableSilhouette, reduceLod, fpsCap, reason, temp);
        }
        int shadow = Math.min(preset.shadowQuality(), tier);
        int particles = disableSilhouette || preset.forceDisableParticles()
                ? Math.min(50, preset.particleBudget()) : preset.particleBudget();
        return new Adjust(Math.min(tier, shadow > 0 ? tier : 0),
                disableSilhouette || preset.forceDisableShadow(),
                reduceLod,
                Math.min(fpsCap, preset.targetFps()),
                reason, temp, preset.id(), shadow, particles, preset.viewDistance());
    }
}
