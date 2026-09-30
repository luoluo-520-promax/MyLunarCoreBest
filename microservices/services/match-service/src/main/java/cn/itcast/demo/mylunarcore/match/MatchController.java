package cn.itcast.demo.mylunarcore.match;

import cn.itcast.demo.mylunarcore.match.api.MatchApi;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 全局匹配：按评分 SortedSet 语义入队，成功后返回 zoneId 供客户端跳转。
 * <p>（无 Redis 依赖时用 ConcurrentSkipListMap 模拟；生产可换 Redis ZSET）
 */
@RestController
@RequestMapping("/v1/match")
public class MatchController implements MatchApi {

    private final AtomicInteger queueDepth = new AtomicInteger();
    private final ConcurrentHashMap<String, TicketMeta> tickets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, String> playerTicket = new ConcurrentHashMap<>();
    /** score → ticketId 列表，模拟 Redis SortedSet。 */
    private final ConcurrentSkipListMap<Long, List<String>> scoreIndex = new ConcurrentSkipListMap<>();

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of("ok", true, "service", "match-service", "queueDepth", queueDepth.get(),
                "engine", "sorted-score-queue");
    }

    @PostMapping("/enqueue")
    public Map<String, Object> enqueueHttp(@RequestBody Map<String, Object> body) {
        int uid = toInt(body.get("uid"), toInt(body.get("playerId"), 0));
        int mode = toInt(body.get("mode"), 0);
        int level = toInt(body.get("level"), 1);
        int power = toInt(body.get("power"), 0);
        EnqueueResult result = enqueue(new EnqueueRequest(uid, mode, level, power));
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("ok", result.success());
        out.put("ticket", result.queueId() == null ? "" : result.queueId());
        out.put("status", result.success() ? "QUEUED" : "REJECTED");
        out.put("reason", result.reason() == null ? "" : result.reason());
        // 尝试即时撮合
        MatchFound found = tryMatch(mode);
        if (found != null) {
            out.put("matched", true);
            out.put("zoneId", found.zoneId());
            out.put("players", found.playerIds());
        } else {
            out.put("matched", false);
        }
        return out;
    }

    @PostMapping("/cancel")
    public Map<String, Object> cancelHttp(@RequestBody Map<String, Object> body) {
        int uid = toInt(body.get("uid"), toInt(body.get("playerId"), 0));
        boolean ok = uid > 0 && cancel(new CancelRequest(uid));
        return Map.of("ok", ok);
    }

    @Override
    public EnqueueResult enqueue(EnqueueRequest request) {
        if (request == null || request.playerId() <= 0) {
            return new EnqueueResult(false, "bad_player", null);
        }
        String existing = playerTicket.get(request.playerId());
        if (existing != null && tickets.containsKey(existing)) {
            return new EnqueueResult(true, "already_queued", existing);
        }
        long score = scoreOf(request.level(), request.power());
        String ticket = "m-" + request.playerId() + "-" + UUID.randomUUID().toString().substring(0, 8);
        tickets.put(ticket, new TicketMeta(request.playerId(), request.mode(), score));
        playerTicket.put(request.playerId(), ticket);
        scoreIndex.computeIfAbsent(score, s -> new ArrayList<>()).add(ticket);
        queueDepth.incrementAndGet();
        return new EnqueueResult(true, "ok", ticket);
    }

    @Override
    public boolean cancel(CancelRequest request) {
        if (request == null || request.playerId() <= 0) {
            return false;
        }
        String ticket = playerTicket.remove(request.playerId());
        if (ticket == null) {
            return false;
        }
        TicketMeta meta = tickets.remove(ticket);
        if (meta != null) {
            List<String> list = scoreIndex.get(meta.score());
            if (list != null) {
                list.remove(ticket);
            }
            queueDepth.updateAndGet(v -> Math.max(0, v - 1));
            return true;
        }
        return false;
    }

    private MatchFound tryMatch(int mode) {
        List<TicketMeta> pool = new ArrayList<>();
        for (TicketMeta m : tickets.values()) {
            if (m.mode() == mode) {
                pool.add(m);
            }
        }
        pool.sort(Comparator.comparingLong(TicketMeta::score));
        if (pool.size() < 2) {
            return null;
        }
        TicketMeta a = pool.get(0);
        TicketMeta b = pool.get(1);
        cancel(new CancelRequest(a.playerId()));
        cancel(new CancelRequest(b.playerId()));
        int zoneId = 100000 + mode * 1000 + (a.playerId() % 97);
        return new MatchFound(zoneId, List.of(a.playerId(), b.playerId()));
    }

    private static long scoreOf(int level, int power) {
        return (long) level * 10_000L + Math.max(0, power);
    }

    private record TicketMeta(int playerId, int mode, long score) {}

    private record MatchFound(int zoneId, List<Integer> playerIds) {}

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
}
