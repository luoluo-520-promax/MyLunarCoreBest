package cn.itcast.demo.mylunarcore.dialogue;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对话语音与口型时间戳配置：data/DialogueVoiceConfig.json。
 */
@Repository
public class DialogueVoiceConfigRepository {

    private static final Logger log = LoggerFactory.getLogger(DialogueVoiceConfigRepository.class);

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VoiceEntry(String lineKey, String audioKey, String voiceId, int durationMs,
                             List<Integer> lipSyncTimestamps) {
        public VoiceEntry {
            lineKey = lineKey == null ? "" : lineKey;
            audioKey = audioKey == null ? "" : audioKey;
            voiceId = voiceId == null ? "" : voiceId;
            lipSyncTimestamps = lipSyncTimestamps == null ? List.of() : List.copyOf(lipSyncTimestamps);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Root(int schemaVersion, List<VoiceEntry> entries) {}

    private final ObjectMapper objectMapper;
    private final Path dataDir;
    private final Map<String, VoiceEntry> byLineKey = new ConcurrentHashMap<>();

    public DialogueVoiceConfigRepository(ObjectMapper objectMapper,
                                         @Value("${lunarcore.data-dir:data}") String dataDir) {
        this.objectMapper = objectMapper;
        this.dataDir = Path.of(dataDir);
    }

    @PostConstruct
    public void load() {
        reload();
    }

    public boolean reload() {
        Path file = dataDir.resolve("DialogueVoiceConfig.json");
        if (!Files.isRegularFile(file)) {
            log.warn("DialogueVoiceConfig.json missing, voice lip-sync disabled");
            return false;
        }
        try {
            Root root = objectMapper.readValue(Files.readString(file), Root.class);
            byLineKey.clear();
            if (root != null && root.entries() != null) {
                for (VoiceEntry e : root.entries()) {
                    if (e != null && !e.lineKey().isBlank()) {
                        byLineKey.put(e.lineKey(), e);
                    }
                }
            }
            log.info("DialogueVoiceConfig loaded entries={}", byLineKey.size());
            return true;
        } catch (Exception e) {
            log.warn("load DialogueVoiceConfig failed: {}", e.toString());
            return false;
        }
    }

    public VoiceEntry find(String lineKey) {
        if (lineKey == null || lineKey.isBlank()) {
            return null;
        }
        return byLineKey.get(lineKey);
    }

    public Map<String, VoiceEntry> snapshot() {
        return Collections.unmodifiableMap(byLineKey);
    }
}
