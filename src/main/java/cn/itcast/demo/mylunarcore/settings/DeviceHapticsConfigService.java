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
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 设备触觉波形热更表：按 device_family 下发 PS5/Xbox/手机震动元数据。
 */
@Service
public class DeviceHapticsConfigService {

    private static final Logger log = LoggerFactory.getLogger(DeviceHapticsConfigService.class);

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WaveformCfg(int waveformId, String deviceFamily, String name,
                              List<Float> amplitudes, int durationMs, float triggerForce) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RootCfg(List<WaveformCfg> waveforms) {}

    private final ObjectMapper objectMapper;
    private final Path dataDir;
    private final AtomicReference<Map<String, List<WaveformCfg>>> byFamily =
            new AtomicReference<>(Map.of());

    public DeviceHapticsConfigService(ObjectMapper objectMapper,
                                      @Value("${lunarcore.data-dir:data}") String dataDir) {
        this.objectMapper = objectMapper;
        this.dataDir = Path.of(dataDir);
    }

    @PostConstruct
    public void load() {
        Path file = dataDir.resolve("DeviceHapticsConfig.json");
        if (!Files.isRegularFile(file)) {
            byFamily.set(defaults());
            return;
        }
        try {
            RootCfg root = objectMapper.readValue(Files.readString(file), RootCfg.class);
            Map<String, List<WaveformCfg>> map = new ConcurrentHashMap<>();
            if (root != null && root.waveforms() != null) {
                for (WaveformCfg w : root.waveforms()) {
                    if (w == null || w.deviceFamily() == null) {
                        continue;
                    }
                    map.computeIfAbsent(w.deviceFamily().toLowerCase(), k -> new ArrayList<>()).add(w);
                }
            }
            if (map.isEmpty()) {
                map = defaults();
            }
            byFamily.set(map);
        } catch (Exception e) {
            log.warn("load DeviceHapticsConfig failed: {}", e.toString());
            byFamily.set(defaults());
        }
    }

    public List<WaveformCfg> forFamily(String deviceFamily) {
        String key = deviceFamily == null || deviceFamily.isBlank() ? "generic" : deviceFamily.toLowerCase();
        List<WaveformCfg> list = byFamily.get().get(key);
        if (list == null || list.isEmpty()) {
            list = byFamily.get().getOrDefault("generic", List.of());
        }
        return list;
    }

    /** 反击成功=101，暴击=102，击杀=103，普攻=100；战技=104，终结技=105，受击=106 */
    public static int pickWaveformId(boolean critical, boolean kill, boolean counter) {
        if (kill) {
            return 103;
        }
        if (counter) {
            return 101;
        }
        if (critical) {
            return 102;
        }
        return 100;
    }

    /** 按技能类型与设备族选择波形（iOS/Android/手柄差异化）。 */
    public static int pickWaveformBySkill(String skillKind, String deviceFamily) {
        String kind = skillKind == null ? "" : skillKind.toLowerCase();
        String family = deviceFamily == null ? "generic" : deviceFamily.toLowerCase();
        int base = switch (kind) {
            case "skill", "战技" -> 104;
            case "ultimate", "ult", "终结技" -> 105;
            case "hit", "受击" -> 106;
            default -> 100;
        };
        // iOS 触觉更细，偏移 +10 使用同族高档波形位；Android/手柄用基础 ID
        if (family.contains("ios") || family.contains("iphone")) {
            return base + 10;
        }
        return base;
    }

    public static int pickIntensity(boolean critical, boolean kill, boolean counter) {
        if (kill) {
            return 95;
        }
        if (counter) {
            return 80;
        }
        if (critical) {
            return 70;
        }
        return 35;
    }

    public static int pickIntensityBySkill(String skillKind) {
        String kind = skillKind == null ? "" : skillKind.toLowerCase();
        return switch (kind) {
            case "ultimate", "ult", "终结技" -> 90;
            case "skill", "战技" -> 65;
            case "hit", "受击" -> 45;
            default -> 35;
        };
    }

    private static Map<String, List<WaveformCfg>> defaults() {
        Map<String, List<WaveformCfg>> map = new ConcurrentHashMap<>();
        map.put("ps5", List.of(
                new WaveformCfg(100, "ps5", "light_hit", List.of(0.2f, 0.4f, 0.2f), 40, 0.2f),
                new WaveformCfg(101, "ps5", "counter_trigger", List.of(0.6f, 0.9f, 0.5f), 80, 0.85f),
                new WaveformCfg(102, "ps5", "crit", List.of(0.5f, 0.8f, 0.6f), 60, 0.5f),
                new WaveformCfg(103, "ps5", "kill", List.of(0.9f, 1.0f, 0.7f), 120, 1.0f)
        ));
        map.put("xbox", List.of(
                new WaveformCfg(100, "xbox", "light_hit", List.of(0.2f, 0.35f), 35, 0f),
                new WaveformCfg(101, "xbox", "counter", List.of(0.7f, 0.5f), 70, 0f),
                new WaveformCfg(102, "xbox", "crit", List.of(0.6f, 0.8f), 55, 0f),
                new WaveformCfg(103, "xbox", "kill", List.of(1.0f, 0.8f), 100, 0f)
        ));
        map.put("mobile", List.of(
                new WaveformCfg(100, "mobile", "tap", List.of(0.3f), 20, 0f),
                new WaveformCfg(101, "mobile", "counter_tick", List.of(0.5f, 0.2f), 40, 0f),
                new WaveformCfg(102, "mobile", "crit_buzz", List.of(0.6f), 35, 0f),
                new WaveformCfg(103, "mobile", "kill_pulse", List.of(0.8f, 0.4f, 0.8f), 90, 0f)
        ));
        map.put("generic", map.get("mobile"));
        return map;
    }
}
