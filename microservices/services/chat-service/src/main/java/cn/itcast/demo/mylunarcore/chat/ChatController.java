package cn.itcast.demo.mylunarcore.chat;

import cn.itcast.demo.mylunarcore.chat.api.ChatApi;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 聊天枢纽：世界频道削峰队列 + 离线私聊缓存（7 天语义，进程内实现）。
 * <p>生产可换 Kafka topic {@code lunar.chat.world} / Redis Stream。
 */
@RestController
@RequestMapping("/v1/chat")
public class ChatController implements ChatApi {

    private static final long OFFLINE_TTL_MS = 7L * 24 * 3600_000L;

    private final Deque<Map<String, Object>> recent = new ArrayDeque<>();
    private final ConcurrentLinkedQueue<Map<String, Object>> worldBacklog = new ConcurrentLinkedQueue<>();
    private final ConcurrentHashMap<Integer, List<Map<String, Object>>> offlinePrivate = new ConcurrentHashMap<>();

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "ok", true,
                "service", "chat-service",
                "engine", "backlog+offline-cache",
                "redisChannels", List.of("lunar:chat:world", "lunar:chat:private"),
                "recent", recent.size(),
                "worldBacklog", worldBacklog.size());
    }

    @PostMapping("/world")
    public Map<String, Object> world(@RequestBody Map<String, Object> body) {
        int from = toInt(body.get("from"), toInt(body.get("fromPlayerId"), 0));
        String text = String.valueOf(body.getOrDefault("text", body.getOrDefault("content", "")));
        boolean ok = publishWorld(new WorldMessage(from, text, System.currentTimeMillis()));
        return Map.of("ok", ok, "queued", ok, "backlog", worldBacklog.size());
    }

    @PostMapping("/private")
    public Map<String, Object> privateMsg(@RequestBody Map<String, Object> body) {
        int from = toInt(body.get("from"), toInt(body.get("fromPlayerId"), 0));
        int to = toInt(body.get("to"), toInt(body.get("toPlayerId"), 0));
        String text = String.valueOf(body.getOrDefault("text", body.getOrDefault("content", "")));
        boolean online = Boolean.parseBoolean(String.valueOf(body.getOrDefault("toOnline", "true")));
        boolean ok = publishPrivate(new PrivateMessage(from, to, text, System.currentTimeMillis()));
        if (ok && !online) {
            cacheOffline(to, Map.of(
                    "from", from, "to", to, "text", text, "ts", System.currentTimeMillis()));
        }
        return Map.of("ok", ok, "queued", ok);
    }

    @GetMapping("/offline")
    public Map<String, Object> flushOffline(@RequestParam("uid") int uid) {
        List<Map<String, Object>> msgs = offlinePrivate.remove(uid);
        if (msgs == null) {
            msgs = List.of();
        } else {
            long now = System.currentTimeMillis();
            msgs = msgs.stream()
                    .filter(m -> now - toLong(m.get("ts")) <= OFFLINE_TTL_MS)
                    .toList();
        }
        return Map.of("ok", true, "messages", msgs, "ttlDays", 7);
    }

    @PostMapping("/world/drain")
    public Map<String, Object> drainWorld(@RequestParam(defaultValue = "50") int limit) {
        List<Map<String, Object>> batch = new ArrayList<>();
        for (int i = 0; i < Math.max(1, limit); i++) {
            Map<String, Object> m = worldBacklog.poll();
            if (m == null) {
                break;
            }
            batch.add(m);
        }
        return Map.of("ok", true, "batch", batch, "remaining", worldBacklog.size());
    }

    @Override
    public boolean publishWorld(WorldMessage message) {
        if (message == null || message.content() == null || message.content().isBlank()) {
            return false;
        }
        Map<String, Object> msg = Map.of(
                "channel", "world",
                "from", message.fromPlayerId(),
                "text", message.content(),
                "ts", message.sentAtMs() > 0 ? message.sentAtMs() : System.currentTimeMillis());
        worldBacklog.offer(msg);
        push(msg);
        return true;
    }

    @Override
    public boolean publishPrivate(PrivateMessage message) {
        if (message == null || message.content() == null || message.content().isBlank()) {
            return false;
        }
        Map<String, Object> msg = Map.of(
                "channel", "private",
                "from", message.fromPlayerId(),
                "to", message.toPlayerId(),
                "text", message.content(),
                "ts", message.sentAtMs() > 0 ? message.sentAtMs() : System.currentTimeMillis());
        push(msg);
        return true;
    }

    private void cacheOffline(int toUid, Map<String, Object> msg) {
        offlinePrivate.compute(toUid, (k, list) -> {
            List<Map<String, Object>> out = list == null ? new ArrayList<>() : new ArrayList<>(list);
            out.add(msg);
            while (out.size() > 200) {
                out.remove(0);
            }
            return out;
        });
    }

    private void push(Map<String, Object> msg) {
        synchronized (recent) {
            recent.addFirst(msg);
            while (recent.size() > 100) {
                recent.removeLast();
            }
        }
    }

    private static int toInt(Object v, int def) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v != null) {
            try {
                return Integer.parseInt(String.valueOf(v));
            } catch (NumberFormatException ignored) {
            }
        }
        return def;
    }

    private static long toLong(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(v));
        } catch (Exception e) {
            return Instant.EPOCH.toEpochMilli();
        }
    }
}
