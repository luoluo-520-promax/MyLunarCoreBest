package cn.itcast.demo.mylunarcore.settings;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 设备性能画像 → QualityPreset（低/中/高/极高），供 ScenePerformanceAdjust 下发渲染参数。
 */
@Service
public class QualityPresetService {

    private static final Logger log = LoggerFactory.getLogger(QualityPresetService.class);

    public record DeviceProfile(String cpuModel, int ramMb, String gpuModel, String osFamily) {}

    public record QualityPreset(String id, int shadowQuality, int particleBudget, float viewDistance,
                                boolean forceDisableShadow, boolean forceDisableParticles, int targetFps) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PresetFile(List<QualityPreset> presets, List<DeviceRule> rules) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DeviceRule(String matchCpuContains, int maxRamMb, String presetId) {}

    private final ObjectMapper objectMapper;
    private final Path dataDir;
    private final AtomicReference<List<QualityPreset>> presets = new AtomicReference<>(defaultPresets());
    private final AtomicReference<List<DeviceRule>> rules = new AtomicReference<>(List.of());

    public QualityPresetService(ObjectMapper objectMapper,
                                @Value("${lunarcore.data-dir:data}") String dataDir) {
        this.objectMapper = objectMapper;
        this.dataDir = Path.of(dataDir);
    }

    @PostConstruct
    public void load() {
        Path file = dataDir.resolve("QualityPresets.json");
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            PresetFile cfg = objectMapper.readValue(Files.readString(file), PresetFile.class);
            if (cfg != null && cfg.presets() != null && !cfg.presets().isEmpty()) {
                presets.set(List.copyOf(cfg.presets()));
            }
            if (cfg != null && cfg.rules() != null) {
                rules.set(List.copyOf(cfg.rules()));
            }
            log.info("QualityPresets loaded presets={}", presets.get().size());
        } catch (Exception e) {
            log.warn("load QualityPresets failed: {}", e.toString());
        }
    }

    public QualityPreset resolve(DeviceProfile profile) {
        if (profile != null) {
            String cpu = profile.cpuModel() == null ? "" : profile.cpuModel().toLowerCase(Locale.ROOT);
            for (DeviceRule rule : rules.get()) {
                if (rule == null || rule.presetId() == null) {
                    continue;
                }
                boolean cpuMatch = rule.matchCpuContains() == null || rule.matchCpuContains().isBlank()
                        || cpu.contains(rule.matchCpuContains().toLowerCase(Locale.ROOT));
                boolean ramMatch = rule.maxRamMb() <= 0 || profile.ramMb() <= rule.maxRamMb();
                if (cpuMatch && ramMatch) {
                    QualityPreset p = byId(rule.presetId());
                    if (p != null) {
                        return p;
                    }
                }
            }
            if (profile.ramMb() > 0 && profile.ramMb() < 3072) {
                return byId("low");
            }
            if (profile.ramMb() >= 8192) {
                return byId("ultra");
            }
        }
        return byId("medium");
    }

    public QualityPreset byId(String id) {
        String key = id == null ? "medium" : id.trim().toLowerCase(Locale.ROOT);
        for (QualityPreset p : presets.get()) {
            if (p.id() != null && p.id().equalsIgnoreCase(key)) {
                return p;
            }
        }
        return defaultPresets().stream().filter(p -> "medium".equals(p.id())).findFirst().orElse(defaultPresets().get(1));
    }

    public Map<String, Object> toSceneAdjustPayload(QualityPreset preset, String reason) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("presetId", preset.id());
        m.put("shadowQuality", preset.shadowQuality());
        m.put("particleBudget", preset.particleBudget());
        m.put("viewDistance", preset.viewDistance());
        m.put("forceDisableShadow", preset.forceDisableShadow());
        m.put("forceDisableParticles", preset.forceDisableParticles());
        m.put("targetFps", preset.targetFps());
        m.put("reason", reason == null ? "device_profile" : reason);
        return m;
    }

    private static List<QualityPreset> defaultPresets() {
        List<QualityPreset> list = new ArrayList<>();
        list.add(new QualityPreset("low", 0, 50, 0.6f, true, true, 30));
        list.add(new QualityPreset("medium", 1, 150, 0.85f, false, false, 45));
        list.add(new QualityPreset("high", 2, 400, 1.0f, false, false, 60));
        list.add(new QualityPreset("ultra", 3, 800, 1.2f, false, false, 60));
        return list;
    }
}
