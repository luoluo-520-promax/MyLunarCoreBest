package cn.itcast.demo.mylunarcore.hall;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 世界聊天敏感词：从 data/ChatSensitiveWords.json 热加载。
 */
@Component
public class ChatSensitiveWordFilter {

    private static final Logger log = LoggerFactory.getLogger(ChatSensitiveWordFilter.class);

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Root(List<String> words, List<String> patterns) {}

    private final ObjectMapper objectMapper;
    private final Path dataDir;
    private final AtomicReference<List<String>> words = new AtomicReference<>(List.of());
    private final AtomicReference<List<java.util.regex.Pattern>> patterns = new AtomicReference<>(List.of());

    public ChatSensitiveWordFilter(ObjectMapper objectMapper,
                                   @Value("${lunarcore.data-dir:data}") String dataDir) {
        this.objectMapper = objectMapper;
        this.dataDir = Path.of(dataDir);
    }

    @PostConstruct
    public void reload() {
        Path file = dataDir.resolve("ChatSensitiveWords.json");
        if (!Files.isRegularFile(file)) {
            words.set(List.of());
            patterns.set(List.of());
            return;
        }
        try {
            Root root = objectMapper.readValue(Files.readString(file), Root.class);
            List<String> w = root == null || root.words() == null ? List.of() : List.copyOf(root.words());
            List<java.util.regex.Pattern> p = new ArrayList<>();
            if (root != null && root.patterns() != null) {
                for (String s : root.patterns()) {
                    if (s != null && !s.isBlank()) {
                        p.add(java.util.regex.Pattern.compile(s, java.util.regex.Pattern.CASE_INSENSITIVE));
                    }
                }
            }
            words.set(w);
            patterns.set(List.copyOf(p));
            log.info("ChatSensitiveWordFilter loaded words={} patterns={}", w.size(), p.size());
        } catch (Exception e) {
            log.warn("load ChatSensitiveWords failed: {}", e.toString());
        }
    }

    /** @return true 命中敏感词 */
    public boolean containsSensitive(String content) {
        if (content == null || content.isBlank()) {
            return false;
        }
        String lower = content.toLowerCase(Locale.ROOT);
        for (String w : words.get()) {
            if (w != null && !w.isBlank() && lower.contains(w.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        for (java.util.regex.Pattern p : patterns.get()) {
            if (p.matcher(content).find()) {
                return true;
            }
        }
        return false;
    }
}
