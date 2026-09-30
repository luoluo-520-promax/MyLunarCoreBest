package cn.itcast.demo.mylunarcore.dialogue;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 剧情表演时间轴脚本：data/DialoguePerformanceScripts.json。
 */
@Repository
public class DialoguePerformanceScriptRepository {

    private static final Logger log = LoggerFactory.getLogger(DialoguePerformanceScriptRepository.class);

    public enum BeatType {
        PLAY_EMOTION, PLAY_GESTURE, PLAY_VOICE, CAMERA_SHOT, SCREEN_EFFECT, WAIT
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Beat(String type, int startMs, Map<String, Object> payload) {
        public Beat {
            type = type == null ? "WAIT" : type;
            payload = payload == null ? Map.of() : Map.copyOf(payload);
        }

        public BeatType beatType() {
            try {
                return BeatType.valueOf(type);
            } catch (Exception e) {
                return BeatType.WAIT;
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Script(int estimatedDurationMs, List<Beat> beats) {
        public Script {
            beats = beats == null ? List.of() : List.copyOf(beats);
        }
    }

    private final ObjectMapper objectMapper;
    private final Path dataDir;
    private final Map<String, Script> scripts = new ConcurrentHashMap<>();

    public DialoguePerformanceScriptRepository(ObjectMapper objectMapper,
                                               @Value("${lunarcore.data-dir:data}") String dataDir) {
        this.objectMapper = objectMapper;
        this.dataDir = Path.of(dataDir);
    }

    @PostConstruct
    public void load() {
        reload();
    }

    public boolean reload() {
        Path file = dataDir.resolve("DialoguePerformanceScripts.json");
        if (!Files.isRegularFile(file)) {
            seedDefault();
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(Files.readString(file));
            JsonNode scriptsNode = root.path("scripts");
            scripts.clear();
            if (scriptsNode.isObject()) {
                scriptsNode.fields().forEachRemaining(e -> {
                    try {
                        Script s = objectMapper.treeToValue(e.getValue(), Script.class);
                        scripts.put(e.getKey(), s);
                    } catch (Exception ignored) {
                        // skip bad script
                    }
                });
            }
            if (scripts.isEmpty()) {
                seedDefault();
            }
            log.info("DialoguePerformanceScripts loaded count={}", scripts.size());
            return true;
        } catch (Exception e) {
            log.warn("load DialoguePerformanceScripts failed: {}", e.toString());
            seedDefault();
            return false;
        }
    }

    private void seedDefault() {
        scripts.put("perf_affinity_ack", new Script(1800, List.of(
                new Beat("PLAY_EMOTION", 0, Map.of("emotionId", "happy")),
                new Beat("PLAY_GESTURE", 100, Map.of("gesture", "nod_smile"))
        )));
    }

    public Script find(String performanceId) {
        if (performanceId == null || performanceId.isBlank()) {
            return null;
        }
        return scripts.get(performanceId);
    }

    public Map<String, Script> snapshot() {
        return Collections.unmodifiableMap(scripts);
    }

    /** 将脚本序列化为客户端 timeline JSON（有序指令数组）。 */
    public String toTimelineJson(Script script) {
        if (script == null) {
            return "[]";
        }
        try {
            List<Map<String, Object>> arr = new ArrayList<>();
            for (Beat b : script.beats()) {
                arr.add(Map.of(
                        "type", b.type(),
                        "start_time", b.startMs(),
                        "payload", b.payload()
                ));
            }
            return objectMapper.writeValueAsString(arr);
        } catch (Exception e) {
            return "[]";
        }
    }
}
