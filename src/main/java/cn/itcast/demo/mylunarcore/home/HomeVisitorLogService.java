package cn.itcast.demo.mylunarcore.home;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 家园异步拜访日志：记录好友来访、留言与点赞，房主再次上线时推送。
 */
@Service
public class HomeVisitorLogService {

    public record LogEntry(int visitorId, String action, String message, long atMs, boolean unread,
                           int facilityId, String facilityName, String facilityIcon) {
        public LogEntry(int visitorId, String action, String message, long atMs, boolean unread) {
            this(visitorId, action, message, atMs, unread, 0, "", "");
        }
    }

    private final JdbcTemplate jdbc;
    private final Map<Integer, List<LogEntry>> memory = new ConcurrentHashMap<>();

    public HomeVisitorLogService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public LogEntry recordAssist(int hostPlayerId, int visitorId, int facilityId,
                                 String facilityName, String facilityIcon) {
        String name = facilityName == null ? "设施" + facilityId : facilityName;
        String msg = "帮你加速了 " + name + " 的产量";
        LogEntry entry = new LogEntry(visitorId, "assist", msg, System.currentTimeMillis(), true,
                facilityId, name, facilityIcon == null ? "" : facilityIcon);
        if (hostPlayerId <= 0 || visitorId <= 0 || hostPlayerId == visitorId) {
            return null;
        }
        memory.computeIfAbsent(hostPlayerId, k -> new CopyOnWriteArrayList<>()).add(entry);
        if (jdbc != null) {
            try {
                jdbc.update("""
                        INSERT INTO home_visitor_social_log
                          (host_player_id, visitor_id, action, message, unread, created_at_ms)
                        VALUES (?, ?, ?, ?, 1, ?)
                        """, hostPlayerId, visitorId, "assist", entry.message(), entry.atMs());
            } catch (Exception ignored) {
                // 仅内存
            }
        }
        return entry;
    }

    public LogEntry recordVisit(int hostPlayerId, int visitorId) {
        return append(hostPlayerId, visitorId, "visit", "");
    }

    public LogEntry recordLike(int hostPlayerId, int visitorId) {
        return append(hostPlayerId, visitorId, "like", "");
    }

    public LogEntry recordMessage(int hostPlayerId, int visitorId, String message) {
        String text = message == null ? "" : message.trim();
        if (text.length() > 200) {
            text = text.substring(0, 200);
        }
        return append(hostPlayerId, visitorId, "message", text);
    }

    public List<LogEntry> list(int hostPlayerId, int limit) {
        int n = Math.max(1, Math.min(50, limit <= 0 ? 20 : limit));
        List<LogEntry> fromDb = load(hostPlayerId, n);
        if (!fromDb.isEmpty()) {
            return fromDb;
        }
        List<LogEntry> mem = memory.getOrDefault(hostPlayerId, List.of());
        if (mem.size() <= n) {
            return List.copyOf(mem);
        }
        return List.copyOf(mem.subList(Math.max(0, mem.size() - n), mem.size()));
    }

    public List<LogEntry> drainUnread(int hostPlayerId) {
        List<LogEntry> unread = new ArrayList<>();
        for (LogEntry e : list(hostPlayerId, 50)) {
            if (e.unread()) {
                unread.add(e);
            }
        }
        markRead(hostPlayerId);
        return unread;
    }

    public int unreadCount(int hostPlayerId) {
        int n = 0;
        for (LogEntry e : list(hostPlayerId, 50)) {
            if (e.unread()) {
                n++;
            }
        }
        return n;
    }

    private LogEntry append(int hostPlayerId, int visitorId, String action, String message) {
        if (hostPlayerId <= 0 || visitorId <= 0 || hostPlayerId == visitorId) {
            return null;
        }
        LogEntry entry = new LogEntry(visitorId, action, message == null ? "" : message,
                System.currentTimeMillis(), true);
        memory.computeIfAbsent(hostPlayerId, k -> new CopyOnWriteArrayList<>()).add(entry);
        if (jdbc != null) {
            try {
                jdbc.update("""
                        INSERT INTO home_visitor_social_log
                          (host_player_id, visitor_id, action, message, unread, created_at_ms)
                        VALUES (?, ?, ?, ?, 1, ?)
                        """, hostPlayerId, visitorId, action, entry.message(), entry.atMs());
            } catch (Exception ignored) {
                try {
                    jdbc.update("INSERT INTO home_visit_log (host_player_id, visitor_id) VALUES (?, ?)",
                            hostPlayerId, visitorId);
                } catch (Exception ignored2) {
                    // 仅内存
                }
            }
        }
        return entry;
    }

    private List<LogEntry> load(int hostPlayerId, int limit) {
        if (jdbc == null) {
            return List.of();
        }
        try {
            return jdbc.query("""
                    SELECT visitor_id, action, message, created_at_ms, unread
                    FROM home_visitor_social_log
                    WHERE host_player_id = ?
                    ORDER BY id DESC
                    LIMIT ?
                    """, (rs, i) -> new LogEntry(rs.getInt(1), rs.getString(2),
                    rs.getString(3) == null ? "" : rs.getString(3),
                    rs.getLong(4), rs.getInt(5) == 1), hostPlayerId, limit);
        } catch (Exception e) {
            return List.of();
        }
    }

    private void markRead(int hostPlayerId) {
        List<LogEntry> mem = memory.get(hostPlayerId);
        if (mem != null) {
            List<LogEntry> next = new ArrayList<>();
            for (LogEntry e : mem) {
                next.add(new LogEntry(e.visitorId(), e.action(), e.message(), e.atMs(), false,
                        e.facilityId(), e.facilityName(), e.facilityIcon()));
            }
            memory.put(hostPlayerId, new CopyOnWriteArrayList<>(next));
        }
        if (jdbc == null) {
            return;
        }
        try {
            jdbc.update("UPDATE home_visitor_social_log SET unread = 0 WHERE host_player_id = ?", hostPlayerId);
        } catch (Exception ignored) {
            // ignore
        }
    }
}
