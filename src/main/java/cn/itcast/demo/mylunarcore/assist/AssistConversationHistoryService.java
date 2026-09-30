package cn.itcast.demo.mylunarcore.assist;

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
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话级多轮对话历史：最近 N 轮 + 超长摘要压缩；优先 Redis（带 TTL），否则进程内存。
 */
@Service
public class AssistConversationHistoryService {

    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_ASSIST, AssistConversationHistoryService.class);

    public record Turn(String role, String text) {
    }

    public record HistoryView(List<Turn> turns, String summary) {
        public static HistoryView empty() {
            return new HistoryView(List.of(), "");
        }
    }

    private final LunarCoreProperties properties;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConcurrentHashMap<String, MemorySession> memory = new ConcurrentHashMap<>();

    public AssistConversationHistoryService(LunarCoreProperties properties,
                                            ObjectProvider<StringRedisTemplate> redisProvider) {
        this.properties = properties;
        StringRedisTemplate candidate = redisProvider.getIfAvailable();
        this.redis = properties.getRedis().isEnabled() ? candidate : null;
    }

    /** 读取并拼装可注入 prompt 的历史文本。 */
    public String promptBlock(long uid, String sessionId) {
        HistoryView view = load(uid, sessionId);
        if (view.turns().isEmpty() && (view.summary() == null || view.summary().isBlank())) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        if (view.summary() != null && !view.summary().isBlank()) {
            sb.append("历史摘要：").append(view.summary()).append('\n');
        }
        for (Turn t : view.turns()) {
            sb.append(t.role()).append('：').append(t.text()).append('\n');
        }
        return sb.toString().trim();
    }

    public HistoryView load(long uid, String sessionId) {
        String key = redisKey(uid, sessionId);
        if (redis != null) {
            try {
                String json = redis.opsForValue().get(key);
                if (json != null && !json.isBlank()) {
                    MemorySession s = objectMapper.readValue(json, MemorySession.class);
                    return new HistoryView(List.copyOf(s.turns), s.summary == null ? "" : s.summary);
                }
            } catch (Exception e) {
                log.debug("assist conv redis load failed: {}", e.toString());
            }
        }
        MemorySession mem = memory.get(key);
        if (mem == null || mem.expired(ttlMs())) {
            return HistoryView.empty();
        }
        return new HistoryView(List.copyOf(mem.turns), mem.summary == null ? "" : mem.summary);
    }

    /** 追加一轮用户问 + 助手答，必要时摘要压缩。 */
    public void append(long uid, String sessionId, String question, String answer) {
        if (uid <= 0) {
            return;
        }
        String key = redisKey(uid, sessionId);
        MemorySession session = loadMutable(key);
        session.touch();
        session.turns.add(new Turn("玩家", truncate(question, 240)));
        session.turns.add(new Turn("助手", truncate(answer, 480)));
        trimAndCompress(session);
        persist(key, session);
    }

    private MemorySession loadMutable(String key) {
        if (redis != null) {
            try {
                String json = redis.opsForValue().get(key);
                if (json != null && !json.isBlank()) {
                    MemorySession s = objectMapper.readValue(json, MemorySession.class);
                    if (s.turns == null) {
                        s.turns = new ArrayList<>();
                    }
                    return s;
                }
            } catch (Exception ignored) {
                // fall through to memory
            }
        }
        return memory.computeIfAbsent(key, k -> new MemorySession());
    }

    private void persist(String key, MemorySession session) {
        memory.put(key, session);
        if (redis == null) {
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(session);
            redis.opsForValue().set(key, json, Duration.ofMillis(ttlMs()));
        } catch (Exception e) {
            log.debug("assist conv redis save failed: {}", e.toString());
        }
    }

    private void trimAndCompress(MemorySession session) {
        int maxTurns = Math.max(2, properties.getAiAssist().getConversationHistoryMaxTurns() * 2);
        int maxChars = Math.max(400, properties.getAiAssist().getConversationHistoryMaxChars());
        while (session.turns.size() > maxTurns) {
            Turn removed = session.turns.remove(0);
            session.summary = mergeSummary(session.summary, removed);
        }
        int chars = session.turns.stream().mapToInt(t -> t.text().length()).sum();
        while (chars > maxChars && !session.turns.isEmpty()) {
            Turn removed = session.turns.remove(0);
            session.summary = mergeSummary(session.summary, removed);
            chars = session.turns.stream().mapToInt(t -> t.text().length()).sum();
        }
        if (session.summary != null && session.summary.length() > 600) {
            session.summary = session.summary.substring(0, 600) + "…";
        }
    }

    private static String mergeSummary(String summary, Turn turn) {
        String piece = turn.role() + ":" + truncate(turn.text(), 80);
        if (summary == null || summary.isBlank()) {
            return piece;
        }
        return summary + "；" + piece;
    }

    private String redisKey(long uid, String sessionId) {
        LunarCoreProperties.AiAssistProperties cfg = properties.getAiAssist();
        String env = nullToEmpty(cfg.getEnvKeyPrefix());
        String sid = (sessionId == null || sessionId.isBlank()) ? "default" : sessionId.trim();
        return nullToEmpty(cfg.getConversationRedisKeyPrefix()) + env + ":" + uid + ":" + sid;
    }

    private long ttlMs() {
        return Math.max(60L, properties.getAiAssist().getConversationHistoryTtlSeconds()) * 1000L;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /** Jackson / 内存会话状态。 */
    public static class MemorySession {
        public List<Turn> turns = new ArrayList<>();
        public String summary = "";
        public long lastAccessMs = System.currentTimeMillis();

        void touch() {
            lastAccessMs = System.currentTimeMillis();
        }

        boolean expired(long ttlMs) {
            return System.currentTimeMillis() - lastAccessMs > ttlMs;
        }
    }
}
