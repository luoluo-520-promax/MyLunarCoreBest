package cn.itcast.demo.mylunarcore.scene;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 加载 {@code NPCScheduleConfig.json}：NPC/生物「时段 → 行为」作息表。
 */
@Component
public class NpcScheduleConfigRepository {

    private static final Logger log = LoggerFactory.getLogger(NpcScheduleConfigRepository.class);

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VecCfg(Float x, Float y, Float z) {
        public float xOr(float d) {
            return x != null ? x : d;
        }

        public float yOr(float d) {
            return y != null ? y : d;
        }

        public float zOr(float d) {
            return z != null ? z : d;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PeriodCfg(String period, String behavior, String waypointGroup,
                            String interactionSpot, String homeSpot, String animHint) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ScheduleCfg(int npcId, String defaultBehavior, Float moveSpeed, String idleAnim,
                              List<PeriodCfg> periods, Map<String, VecCfg> spots,
                              Map<String, List<VecCfg>> waypointGroups) {
        public ScheduleCfg {
            if (defaultBehavior == null || defaultBehavior.isBlank()) {
                defaultBehavior = "IDLE";
            }
            if (moveSpeed == null || moveSpeed <= 0) {
                moveSpeed = 2.0f;
            }
            if (periods == null) {
                periods = List.of();
            }
            if (spots == null) {
                spots = Map.of();
            }
            if (waypointGroups == null) {
                waypointGroups = Map.of();
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RootCfg(int version, List<ScheduleCfg> schedules) {}

    private final ObjectMapper objectMapper;
    private final Path dataDir;
    private final Map<Integer, ScheduleCfg> byNpcId = new ConcurrentHashMap<>();

    public NpcScheduleConfigRepository(ObjectMapper objectMapper,
                                       @Value("${lunarcore.data-dir:data}") String dataDir) {
        this.objectMapper = objectMapper;
        this.dataDir = Path.of(dataDir);
    }

    @PostConstruct
    public void loadConfig() {
        Path file = dataDir.resolve("NPCScheduleConfig.json");
        if (!Files.isRegularFile(file)) {
            log.info("NPCScheduleConfig.json missing, npc daily routines disabled");
            return;
        }
        try {
            RootCfg root = objectMapper.readValue(Files.readString(file), RootCfg.class);
            byNpcId.clear();
            if (root != null && root.schedules() != null) {
                for (ScheduleCfg s : root.schedules()) {
                    if (s.npcId() > 0) {
                        byNpcId.put(s.npcId(), s);
                    }
                }
            }
            log.info("NPCScheduleConfig loaded, schedules={}", byNpcId.size());
        } catch (Exception e) {
            log.warn("load NPCScheduleConfig failed: {}", e.toString());
        }
    }

    public ScheduleCfg find(int npcId) {
        return byNpcId.get(npcId);
    }

    public Map<Integer, ScheduleCfg> all() {
        return Collections.unmodifiableMap(byNpcId);
    }

    /** 配置时段名 → WorldTime skybox；兼容 morning→dawn。 */
    public static String normalizePeriodKey(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String key = raw.trim().toLowerCase();
        if ("morning".equals(key)) {
            return "dawn";
        }
        return key;
    }
}
