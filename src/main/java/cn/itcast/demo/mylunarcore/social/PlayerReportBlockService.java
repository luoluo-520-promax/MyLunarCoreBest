package cn.itcast.demo.mylunarcore.social;

import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.protocol.QolSocialSystemProto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家举报与屏蔽：落库举报单、管理员处理结果回执、双向聊天/场景交互屏蔽。
 */
@Service
public class PlayerReportBlockService {

    private static final Logger log = LoggerFactory.getLogger(PlayerReportBlockService.class);

    public record ReportResult(boolean ok, int retcode, long reportId) {}

    public record BlockResult(boolean ok, int retcode, boolean blocked) {}

    private final JdbcTemplate jdbc;
    private final ObjectProvider<GameSessionManager> sessionProvider;
    private final ConcurrentHashMap<Integer, Set<Integer>> blockCache = new ConcurrentHashMap<>();

    public PlayerReportBlockService(JdbcTemplate jdbc,
                                    ObjectProvider<GameSessionManager> sessionProvider) {
        this.jdbc = jdbc;
        this.sessionProvider = sessionProvider;
    }

    public ReportResult report(int reporterId, int targetId, String scene,
                               String evidenceType, String evidence, String reason) {
        if (reporterId <= 0 || targetId <= 0 || reporterId == targetId) {
            return new ReportResult(false, 2, 0);
        }
        try {
            GeneratedKeyHolder keys = new GeneratedKeyHolder();
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement("""
                        INSERT INTO player_report
                        (reporter_id, target_id, scene, evidence_type, evidence, reason, status, created_at)
                        VALUES (?, ?, ?, ?, ?, ?, 'OPEN', ?)
                        """, Statement.RETURN_GENERATED_KEYS);
                ps.setInt(1, reporterId);
                ps.setInt(2, targetId);
                ps.setString(3, nullToEmpty(scene));
                ps.setString(4, nullToEmpty(evidenceType));
                ps.setString(5, nullToEmpty(evidence));
                ps.setString(6, nullToEmpty(reason));
                ps.setString(7, Instant.now().toString());
                return ps;
            }, keys);
            Number key = keys.getKey();
            long id = key == null ? 0L : key.longValue();
            return new ReportResult(true, 0, id);
        } catch (Exception e) {
            log.debug("report persist skipped: {}", e.getMessage());
            return new ReportResult(false, 5, 0);
        }
    }

    public BlockResult setBlocked(int playerId, int targetId, boolean unblock) {
        if (playerId <= 0 || targetId <= 0 || playerId == targetId) {
            return new BlockResult(false, 2, false);
        }
        try {
            if (unblock) {
                jdbc.update("DELETE FROM player_block WHERE player_id=? AND blocked_id=?",
                        playerId, targetId);
                Set<Integer> set = blockCache.get(playerId);
                if (set != null) {
                    set.remove(targetId);
                }
                return new BlockResult(true, 0, false);
            }
            jdbc.update("""
                    INSERT IGNORE INTO player_block (player_id, blocked_id, created_at)
                    VALUES (?, ?, ?)
                    """, playerId, targetId, Instant.now().toString());
            blockCache.computeIfAbsent(playerId, k -> ConcurrentHashMap.newKeySet()).add(targetId);
            return new BlockResult(true, 0, true);
        } catch (Exception e) {
            log.debug("block persist skipped: {}", e.getMessage());
            return new BlockResult(false, 5, false);
        }
    }

    /** 任一方屏蔽则双方聊天/场景交互提示应被过滤（AOI 仍可见实体）。 */
    public boolean isBlockedEitherWay(int a, int b) {
        if (a <= 0 || b <= 0) {
            return false;
        }
        if (cachedContains(a, b) || cachedContains(b, a)) {
            return true;
        }
        try {
            Integer n = jdbc.queryForObject("""
                    SELECT COUNT(1) FROM player_block
                    WHERE (player_id=? AND blocked_id=?) OR (player_id=? AND blocked_id=?)
                    """, Integer.class, a, b, b, a);
            boolean yes = n != null && n > 0;
            if (yes) {
                blockCache.computeIfAbsent(a, k -> ConcurrentHashMap.newKeySet()).add(b);
            }
            return yes;
        } catch (Exception e) {
            return false;
        }
    }

    public List<Map<String, Object>> listOpenReports(int limit) {
        try {
            return jdbc.queryForList("""
                    SELECT id, reporter_id, target_id, scene, evidence_type, evidence, reason, status, created_at
                    FROM player_report WHERE status='OPEN' ORDER BY id DESC LIMIT ?
                    """, Math.max(1, Math.min(200, limit)));
        } catch (Exception e) {
            return List.of();
        }
    }

    public boolean resolveReport(long reportId, String result, String note, String operator) {
        try {
            int n = jdbc.update("""
                    UPDATE player_report SET status=?, resolve_result=?, resolve_note=?,
                      operator=?, resolved_at=? WHERE id=? AND status='OPEN'
                    """, "RESOLVED", nullToEmpty(result), nullToEmpty(note),
                    nullToEmpty(operator), Instant.now().toString(), reportId);
            if (n <= 0) {
                return false;
            }
            notifyReporter(reportId, result, note);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void notifyReporter(long reportId, String result, String note) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT reporter_id FROM player_report WHERE id=?", reportId);
            if (rows.isEmpty()) {
                return;
            }
            int reporterId = ((Number) rows.get(0).get("reporter_id")).intValue();
            GameSessionManager sessions = sessionProvider.getIfAvailable();
            if (sessions == null) {
                return;
            }
            GameSession session = sessions.getOrNull(reporterId);
            if (session == null) {
                return;
            }
            QolSocialSystemProto.ReportResultScNotify notify =
                    QolSocialSystemProto.ReportResultScNotify.newBuilder()
                            .setReportId(reportId)
                            .setResult(nullToEmpty(result))
                            .setNote(nullToEmpty(note))
                            .build();
            session.send(new GamePacket(CmdIds.REPORT_RESULT_SC_NOTIFY, notify.toByteArray()));
        } catch (Exception e) {
            log.debug("report notify skipped: {}", e.getMessage());
        }
    }

    private boolean cachedContains(int playerId, int targetId) {
        Set<Integer> set = blockCache.get(playerId);
        return set != null && set.contains(targetId);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
