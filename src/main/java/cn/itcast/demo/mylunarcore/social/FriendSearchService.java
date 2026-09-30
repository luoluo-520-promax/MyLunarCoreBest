package cn.itcast.demo.mylunarcore.social;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 好友/玩家搜索：数字按 UID 精确或后缀匹配，否则昵称前缀 LIKE；并维护最近联系人。
 */
@Service
public class FriendSearchService {

    private static final Logger log = LoggerFactory.getLogger(FriendSearchService.class);

    public record PlayerHit(long uid, String nickname, int level) {}

    public record SearchPage(List<PlayerHit> hits, int total) {}

    public record RecentContact(long otherId, String nickname, int level, String reason, Instant at) {}

    private final JdbcTemplate jdbc;

    public FriendSearchService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public SearchPage search(String query, int page, int pageSize) {
        String q = query == null ? "" : query.trim();
        int size = Math.min(Math.max(pageSize, 1), 50);
        int p = Math.max(page, 1);
        int offset = (p - 1) * size;
        if (q.isEmpty()) {
            return new SearchPage(List.of(), 0);
        }
        try {
            if (isNumeric(q)) {
                return searchByUid(q, size, offset);
            }
            return searchByNicknamePrefix(q, size, offset);
        } catch (Exception e) {
            log.debug("friend search failed: {}", e.getMessage());
            return new SearchPage(List.of(), 0);
        }
    }

    public void recordRecent(int playerId, long otherId, String reason) {
        if (playerId <= 0 || otherId <= 0 || playerId == otherId) {
            return;
        }
        String r = reason == null || reason.isBlank() ? "search" : reason.trim();
        if (r.length() > 32) {
            r = r.substring(0, 32);
        }
        try {
            jdbc.update("""
                    INSERT INTO player_recent_contact (player_id, other_id, reason, updated_at)
                    VALUES (?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE reason=VALUES(reason), updated_at=VALUES(updated_at)
                    """, playerId, otherId, r, Instant.now().toString());
        } catch (Exception e) {
            log.debug("recordRecent skipped: {}", e.getMessage());
        }
    }

    public List<RecentContact> listRecent(int playerId, int limit) {
        if (playerId <= 0) {
            return List.of();
        }
        int lim = Math.min(Math.max(limit, 1), 50);
        try {
            return jdbc.query("""
                    SELECT c.other_id, COALESCE(p.nickname, '') AS nickname,
                           COALESCE(p.level, 1) AS level, c.reason, c.updated_at
                    FROM player_recent_contact c
                    LEFT JOIN player p ON p.uid = c.other_id
                    WHERE c.player_id=?
                    ORDER BY c.updated_at DESC
                    LIMIT ?
                    """, (rs, i) -> {
                Instant at;
                try {
                    String s = rs.getString("updated_at");
                    at = s == null || s.isBlank() ? Instant.EPOCH : Instant.parse(s);
                } catch (Exception e) {
                    at = Instant.EPOCH;
                }
                return new RecentContact(
                        rs.getLong("other_id"),
                        rs.getString("nickname"),
                        rs.getInt("level"),
                        rs.getString("reason"),
                        at);
            }, playerId, lim);
        } catch (Exception e) {
            return List.of();
        }
    }

    private SearchPage searchByUid(String q, int size, int offset) {
        long exact;
        try {
            exact = Long.parseLong(q);
        } catch (NumberFormatException e) {
            exact = -1L;
        }
        String suffix = "%" + q;
        Integer total = jdbc.queryForObject("""
                SELECT COUNT(1) FROM player
                WHERE uid=? OR CAST(uid AS VARCHAR) LIKE ?
                """, Integer.class, exact, suffix);
        int t = total == null ? 0 : total;
        List<PlayerHit> hits = jdbc.query("""
                SELECT uid, nickname, level FROM player
                WHERE uid=? OR CAST(uid AS VARCHAR) LIKE ?
                ORDER BY CASE WHEN uid=? THEN 0 ELSE 1 END, uid ASC
                LIMIT ? OFFSET ?
                """, (rs, i) -> new PlayerHit(rs.getLong("uid"), rs.getString("nickname"), rs.getInt("level")),
                exact, suffix, exact, size, offset);
        return new SearchPage(hits, t);
    }

    private SearchPage searchByNicknamePrefix(String q, int size, int offset) {
        String like = q + "%";
        Integer total = jdbc.queryForObject(
                "SELECT COUNT(1) FROM player WHERE nickname LIKE ?", Integer.class, like);
        int t = total == null ? 0 : total;
        List<PlayerHit> hits = jdbc.query("""
                SELECT uid, nickname, level FROM player
                WHERE nickname LIKE ?
                ORDER BY nickname ASC, uid ASC
                LIMIT ? OFFSET ?
                """, (rs, i) -> new PlayerHit(rs.getLong("uid"), rs.getString("nickname"), rs.getInt("level")),
                like, size, offset);
        return new SearchPage(hits == null ? new ArrayList<>() : hits, t);
    }

    private static boolean isNumeric(String s) {
        if (s.isEmpty() || s.length() > 18) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
