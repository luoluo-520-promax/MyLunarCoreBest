package cn.itcast.demo.mylunarcore.common;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置发布指纹：记录热更/导入与游戏版本绑定，支撑回滚审计与版本对比。
 * 表主键列为 {@code release_id}（见 migration_p3）。
 */
@Service
public class ConfigReleaseService {

    private final JdbcTemplate jdbc;

    public ConfigReleaseService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String configName, String contentHash, String gameVersion, String operator, String note) {
        if (configName == null || configName.isBlank() || contentHash == null || contentHash.isBlank()) {
            return;
        }
        try {
            jdbc.update("""
                    INSERT INTO config_release (config_name, content_hash, game_version, operator, note, created_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    configName,
                    contentHash,
                    gameVersion == null ? "" : gameVersion,
                    operator == null ? "" : operator,
                    note == null ? "" : note,
                    Instant.now().toString());
        } catch (Exception ignored) {
            // 表未就绪时跳过，不阻断热更主路径
        }
    }

    public List<Map<String, Object>> list(String configName, int limit) {
        int n = Math.max(1, Math.min(200, limit));
        try {
            if (configName != null && !configName.isBlank()) {
                return jdbc.queryForList("""
                        SELECT release_id, release_id AS id, config_name, content_hash, game_version, operator, note, created_at
                        FROM config_release WHERE config_name=? ORDER BY release_id DESC LIMIT ?
                        """, configName, n);
            }
            return jdbc.queryForList("""
                    SELECT release_id, release_id AS id, config_name, content_hash, game_version, operator, note, created_at
                    FROM config_release ORDER BY release_id DESC LIMIT ?
                    """, n);
        } catch (Exception e) {
            return List.of();
        }
    }

    public Map<String, Object> diff(String configName, long leftId, long rightId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("configName", configName);
        try {
            Map<String, Object> left = loadOne(leftId);
            Map<String, Object> right = loadOne(rightId);
            out.put("left", left);
            out.put("right", right);
            String lh = left == null ? "" : String.valueOf(left.getOrDefault("content_hash", ""));
            String rh = right == null ? "" : String.valueOf(right.getOrDefault("content_hash", ""));
            out.put("sameHash", lh.equals(rh));
            List<String> changes = new ArrayList<>();
            if (!lh.equals(rh)) {
                changes.add("content_hash: " + lh + " -> " + rh);
            }
            if (left != null && right != null) {
                String lv = String.valueOf(left.getOrDefault("game_version", ""));
                String rv = String.valueOf(right.getOrDefault("game_version", ""));
                if (!lv.equals(rv)) {
                    changes.add("game_version: " + lv + " -> " + rv);
                }
            }
            out.put("changes", changes);
        } catch (Exception e) {
            out.put("ok", false);
            out.put("message", e.getMessage());
        }
        return out;
    }

    private Map<String, Object> loadOne(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                """
                SELECT release_id, release_id AS id, config_name, content_hash, game_version, operator, note, created_at
                FROM config_release WHERE release_id=?
                """,
                id);
        return rows.isEmpty() ? null : rows.get(0);
    }
}
