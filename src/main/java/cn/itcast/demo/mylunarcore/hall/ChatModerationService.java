package cn.itcast.demo.mylunarcore.hall;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 聊天运营：禁言、举报、审计。
 */
@Service
public class ChatModerationService {

    private static final Logger log = LoggerFactory.getLogger(ChatModerationService.class);

    private final JdbcTemplate jdbc;
    /** 进程内禁言兜底：uid -> unmuteAtMs */
    private final Map<Integer, Long> mutedUntil = new ConcurrentHashMap<>();

    public ChatModerationService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isMuted(int playerId) {
        Long until = mutedUntil.get(playerId);
        if (until != null) {
            if (until > System.currentTimeMillis()) {
                return true;
            }
            mutedUntil.remove(playerId);
        }
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT unmute_at_ms FROM chat_mute WHERE player_id=? AND unmute_at_ms > ?
                    """, playerId, System.currentTimeMillis());
            return !rows.isEmpty();
        } catch (Exception e) {
            return false;
        }
    }

    public void mute(int playerId, long durationMs, String operator, String reason) {
        long until = System.currentTimeMillis() + Math.max(60_000L, durationMs);
        mutedUntil.put(playerId, until);
        try {
            jdbc.update("""
                    INSERT INTO chat_mute (player_id, unmute_at_ms, operator, reason, updated_at)
                    VALUES (?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE unmute_at_ms=VALUES(unmute_at_ms), operator=VALUES(operator),
                      reason=VALUES(reason), updated_at=VALUES(updated_at)
                    """, playerId, until, nullToEmpty(operator), nullToEmpty(reason), Instant.now().toString());
            audit(0, playerId, "mute", reason);
        } catch (Exception e) {
            log.debug("chat mute persist skipped: {}", e.getMessage());
        }
    }

    public void unmute(int playerId, String operator) {
        mutedUntil.remove(playerId);
        try {
            jdbc.update("DELETE FROM chat_mute WHERE player_id=?", playerId);
            audit(0, playerId, "unmute", operator);
        } catch (Exception e) {
            log.debug("chat unmute skipped: {}", e.getMessage());
        }
    }

    public void report(int reporterId, int targetId, String content, String reason) {
        try {
            jdbc.update("""
                    INSERT INTO chat_report (reporter_id, target_id, content, reason, created_at)
                    VALUES (?, ?, ?, ?, ?)
                    """, reporterId, targetId, nullToEmpty(content), nullToEmpty(reason), Instant.now().toString());
            audit(reporterId, targetId, "report", reason);
        } catch (Exception e) {
            log.debug("chat report skipped: {}", e.getMessage());
        }
    }

    public void audit(int actorId, int targetId, String action, String detail) {
        try {
            jdbc.update("""
                    INSERT INTO chat_audit_log (actor_id, target_id, action, detail, created_at)
                    VALUES (?, ?, ?, ?, ?)
                    """, actorId, targetId, nullToEmpty(action), nullToEmpty(detail), Instant.now().toString());
        } catch (Exception e) {
            log.debug("chat audit skipped: {}", e.getMessage());
        }
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
