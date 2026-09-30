package cn.itcast.demo.mylunarcore.assist.memory;

import cn.itcast.demo.mylunarcore.common.AppLogger;
import cn.itcast.demo.mylunarcore.common.LogCategory;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家长期记忆：UID 维度关键事件摘要与情感标签，供多轮陪伴与主动教练检索。
 */
@Service
public class AssistLongTermMemoryService {

    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_ASSIST, AssistLongTermMemoryService.class);
    private static final int MAX_EVENTS = 32;

    public record MemoryEvent(long atMs, String category, String emotionalTag, String summary, double salience) {
    }

    private final LunarCoreProperties properties;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConcurrentHashMap<Long, List<MemoryEvent>> memory = new ConcurrentHashMap<>();

    public AssistLongTermMemoryService(LunarCoreProperties properties,
                                       ObjectProvider<StringRedisTemplate> redisProvider) {
        this.properties = properties;
        StringRedisTemplate candidate = redisProvider.getIfAvailable();
        this.redis = properties.getRedis().isEnabled() ? candidate : null;
    }

    public void record(long uid, String category, String emotionalTag, String summary, double salience) {
        if (uid <= 0 || summary == null || summary.isBlank()) {
            return;
        }
        List<MemoryEvent> events = loadMutable(uid);
        events.add(new MemoryEvent(System.currentTimeMillis(),
                category == null ? "general" : category,
                emotionalTag == null ? "" : emotionalTag,
                truncate(summary, 200),
                Math.max(0.1, salience)));
        trim(events);
        persist(uid, events);
    }

    public String dominantEmotionalTag(long uid) {
        List<MemoryEvent> events = load(uid);
        if (events.isEmpty()) {
            return "";
        }
        long weekAgo = System.currentTimeMillis() - 7L * 24 * 3600 * 1000;
        return events.stream()
                .filter(e -> e.atMs() >= weekAgo && e.emotionalTag() != null && !e.emotionalTag().isBlank())
                .max(Comparator.comparingDouble(MemoryEvent::salience))
                .map(MemoryEvent::emotionalTag)
                .orElse("");
    }

    public String promptBlock(long uid, String scene) {
        List<MemoryEvent> events = load(uid);
        if (events.isEmpty()) {
            return "";
        }
        long cutoff = System.currentTimeMillis() - 14L * 24 * 3600 * 1000;
        List<MemoryEvent> recent = events.stream()
                .filter(e -> e.atMs() >= cutoff)
                .sorted(Comparator.comparingDouble(MemoryEvent::salience).reversed())
                .limit(5)
                .toList();
        if (recent.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("【玩家长期记忆】");
        for (MemoryEvent e : recent) {
            sb.append('\n').append(e.category()).append('/');
            if (!e.emotionalTag().isBlank()) {
                sb.append(e.emotionalTag()).append(':');
            }
            sb.append(e.summary());
        }
        if (scene != null && scene.toLowerCase(Locale.ROOT).contains("gacha")) {
            for (MemoryEvent e : recent) {
                if ("gacha_fail".equals(e.category())) {
                    sb.append("\n（抽卡相关：记得上次经历，语气宜安慰鼓励）");
                    break;
                }
            }
        }
        return sb.toString().trim();
    }

    public boolean shouldUseHealingPersona(long uid) {
        String tag = dominantEmotionalTag(uid);
        return tag.contains("愤怒") || tag.contains("沮丧") || tag.contains("连败");
    }

    public List<MemoryEvent> recallSimilar(long uid, String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return List.of();
        }
        String k = keyword.toLowerCase(Locale.ROOT);
        return load(uid).stream()
                .filter(e -> e.summary().toLowerCase(Locale.ROOT).contains(k))
                .limit(3)
                .toList();
    }

    private List<MemoryEvent> load(long uid) {
        if (redis != null) {
            try {
                String json = redis.opsForValue().get(redisKey(uid));
                if (json != null && !json.isBlank()) {
                    return objectMapper.readValue(json, new TypeReference<List<MemoryEvent>>() {});
                }
            } catch (Exception e) {
                log.debug("long-term memory redis load failed: {}", e.toString());
            }
        }
        List<MemoryEvent> mem = memory.get(uid);
        return mem == null ? List.of() : List.copyOf(mem);
    }

    private List<MemoryEvent> loadMutable(long uid) {
        List<MemoryEvent> loaded = new ArrayList<>(load(uid));
        memory.put(uid, loaded);
        return loaded;
    }

    private void persist(long uid, List<MemoryEvent> events) {
        memory.put(uid, new ArrayList<>(events));
        if (redis == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(events);
            long ttlDays = Math.max(7, properties.getAiAssist().getLongTermMemoryTtlDays());
            redis.opsForValue().set(redisKey(uid), json, Duration.ofDays(ttlDays));
        } catch (Exception e) {
            log.debug("long-term memory redis persist failed: {}", e.toString());
        }
    }

    private static void trim(List<MemoryEvent> events) {
        while (events.size() > MAX_EVENTS) {
            events.remove(0);
        }
    }

    private String redisKey(long uid) {
        return properties.getAiAssist().getEnvKeyPrefix() + ":assist:ltm:" + uid;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
