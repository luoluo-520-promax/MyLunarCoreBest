package cn.itcast.demo.mylunarcore.social;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 玩家自定义表情：上传落库、审核（pending/approved/banned），ID 自 100000 起避免与内置目录冲突。
 */
@Service
public class CustomEmoteService {

    private static final Logger log = LoggerFactory.getLogger(CustomEmoteService.class);
    /** 与基础表情目录 1–99999 错开。 */
    public static final int CUSTOM_ID_FLOOR = 100_000;
    public static final int MAX_IMAGE_BYTES = 256 * 1024;

    public record CustomEmote(int customEmoteId, int playerId, String name, String contentType,
                              String reviewStatus, Instant createdAt) {}

    public record UploadResult(boolean ok, int retcode, int customEmoteId, String reviewStatus) {
        static UploadResult fail(int retcode) {
            return new UploadResult(false, retcode, 0, "");
        }
    }

    private final JdbcTemplate jdbc;

    public CustomEmoteService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UploadResult upload(int playerId, byte[] imageBytes, String contentType, String name) {
        if (playerId <= 0) {
            return UploadResult.fail(1);
        }
        if (imageBytes == null || imageBytes.length == 0) {
            return UploadResult.fail(2);
        }
        if (imageBytes.length > MAX_IMAGE_BYTES) {
            return UploadResult.fail(3);
        }
        String ct = normalizeContentType(contentType);
        if (ct == null) {
            return UploadResult.fail(4);
        }
        String display = name == null || name.isBlank() ? "自定义表情" : name.trim();
        if (display.length() > 32) {
            display = display.substring(0, 32);
        }
        try {
            ensureIdFloor();
            GeneratedKeyHolder keys = new GeneratedKeyHolder();
            String finalDisplay = display;
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement("""
                        INSERT INTO player_custom_emote
                        (player_id, name, content_type, image_data, review_status, created_at, updated_at)
                        VALUES (?, ?, ?, ?, 'pending', ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS);
                ps.setInt(1, playerId);
                ps.setString(2, finalDisplay);
                ps.setString(3, ct);
                ps.setBytes(4, imageBytes);
                String now = Instant.now().toString();
                ps.setString(5, now);
                ps.setString(6, now);
                return ps;
            }, keys);
            Number key = keys.getKey();
            int id = key == null ? 0 : key.intValue();
            if (id > 0 && id < CUSTOM_ID_FLOOR) {
                // 自增未到门槛时强制抬升，避免与内置目录冲突
                jdbc.update("UPDATE player_custom_emote SET custom_emote_id=? WHERE custom_emote_id=?",
                        CUSTOM_ID_FLOOR + id, id);
                id = CUSTOM_ID_FLOOR + id;
                try {
                    jdbc.execute("ALTER TABLE player_custom_emote AUTO_INCREMENT=" + (id + 1));
                } catch (Exception ignored) {
                }
            }
            return new UploadResult(true, 0, id, "pending");
        } catch (Exception e) {
            log.debug("custom emote upload failed: {}", e.getMessage());
            return UploadResult.fail(5);
        }
    }

    public List<CustomEmote> listPending(int limit) {
        int lim = Math.min(Math.max(limit, 1), 200);
        try {
            return jdbc.query("""
                    SELECT custom_emote_id, player_id, name, content_type, review_status, created_at
                    FROM player_custom_emote WHERE review_status='pending'
                    ORDER BY custom_emote_id ASC LIMIT ?
                    """, (rs, i) -> mapRow(rs), lim);
        } catch (Exception e) {
            return List.of();
        }
    }

    public boolean setReviewStatus(int customEmoteId, String status) {
        String s = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
        if (!"approved".equals(s) && !"banned".equals(s) && !"pending".equals(s) && !"rejected".equals(s)) {
            return false;
        }
        if ("rejected".equals(s)) {
            s = "banned";
        }
        try {
            int n = jdbc.update("""
                    UPDATE player_custom_emote SET review_status=?, updated_at=?
                    WHERE custom_emote_id=?
                    """, s, Instant.now().toString(), customEmoteId);
            return n > 0;
        } catch (Exception e) {
            log.debug("setReviewStatus failed: {}", e.getMessage());
            return false;
        }
    }

    public boolean owns(int playerId, int emoteId) {
        if (playerId <= 0 || emoteId < CUSTOM_ID_FLOOR) {
            return false;
        }
        try {
            Integer n = jdbc.queryForObject("""
                    SELECT COUNT(1) FROM player_custom_emote
                    WHERE custom_emote_id=? AND player_id=? AND review_status IN ('pending','approved')
                    """, Integer.class, emoteId, playerId);
            return n != null && n > 0;
        } catch (Exception e) {
            return false;
        }
    }

    public Optional<CustomEmote> findApproved(int emoteId) {
        if (emoteId < CUSTOM_ID_FLOOR) {
            return Optional.empty();
        }
        try {
            List<CustomEmote> list = jdbc.query("""
                    SELECT custom_emote_id, player_id, name, content_type, review_status, created_at
                    FROM player_custom_emote
                    WHERE custom_emote_id=? AND review_status='approved' LIMIT 1
                    """, (rs, i) -> mapRow(rs), emoteId);
            return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private void ensureIdFloor() {
        try {
            Integer max = jdbc.queryForObject(
                    "SELECT COALESCE(MAX(custom_emote_id),0) FROM player_custom_emote", Integer.class);
            if (max != null && max < CUSTOM_ID_FLOOR) {
                jdbc.execute("ALTER TABLE player_custom_emote AUTO_INCREMENT=" + CUSTOM_ID_FLOOR);
            }
        } catch (Exception ignored) {
        }
    }

    private static String normalizeContentType(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return null;
        }
        String ct = contentType.trim().toLowerCase(Locale.ROOT);
        if (ct.contains("png")) {
            return "image/png";
        }
        if (ct.contains("webp")) {
            return "image/webp";
        }
        if (ct.contains("jpeg") || ct.contains("jpg")) {
            return "image/jpeg";
        }
        return null;
    }

    private static CustomEmote mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        String created = rs.getString("created_at");
        Instant at;
        try {
            at = created == null || created.isBlank() ? Instant.EPOCH : Instant.parse(created);
        } catch (Exception e) {
            at = Instant.EPOCH;
        }
        return new CustomEmote(
                rs.getInt("custom_emote_id"),
                rs.getInt("player_id"),
                rs.getString("name"),
                rs.getString("content_type"),
                rs.getString("review_status"),
                at);
    }
}
