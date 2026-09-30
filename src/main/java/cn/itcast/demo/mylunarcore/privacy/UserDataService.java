package cn.itcast.demo.mylunarcore.privacy;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * GDPR/CCPA：用户数据导出（JSON）与删除（软删 + 关联清理标记）。
 */
@Service
public class UserDataService {

    public enum DeleteStatus { NONE, SOFT_DELETED, PURGE_PENDING, PURGED }

    public record ExportBundle(int playerId, String exportedAt, Map<String, Object> payload) {}

    public record DeleteResult(boolean ok, DeleteStatus status, String message) {}

    private final JdbcTemplate jdbc;
    private final Map<Integer, DeleteStatus> deleteFlags = new ConcurrentHashMap<>();
    private final Map<Integer, Map<String, Object>> softBackup = new ConcurrentHashMap<>();

    public UserDataService(ObjectProvider<JdbcTemplate> jdbcProvider) {
        this.jdbc = jdbcProvider == null ? null : jdbcProvider.getIfAvailable();
    }

    public UserDataService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ExportBundle export(int playerId) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("playerId", playerId);
        payload.put("exportedAt", Instant.now().toString());
        if (jdbc != null) {
            try {
                List<Map<String, Object>> profile = jdbc.queryForList(
                        "SELECT uid, nickname, level, created_at FROM player WHERE uid = ?", playerId);
                payload.put("profile", profile);
            } catch (Exception ignored) {
                payload.put("profile", List.of());
            }
            try {
                List<Map<String, Object>> wallet = jdbc.queryForList(
                        "SELECT currency_id, balance FROM player_wallet WHERE player_id = ?", playerId);
                payload.put("wallet", wallet);
            } catch (Exception ignored) {
                payload.put("wallet", List.of());
            }
            try {
                List<Map<String, Object>> settings = jdbc.queryForList(
                        "SELECT * FROM player_settings WHERE player_id = ?", playerId);
                payload.put("settings", settings);
            } catch (Exception ignored) {
                payload.put("settings", List.of());
            }
        } else {
            payload.put("profile", List.of());
            payload.put("wallet", List.of());
            payload.put("settings", List.of());
        }
        Map<String, Object> backup = softBackup.get(playerId);
        if (backup != null) {
            payload.put("softDeletedSnapshot", backup);
        }
        return new ExportBundle(playerId, Instant.now().toString(), payload);
    }

    public DeleteResult softDelete(int playerId) {
        if (playerId <= 0) {
            return new DeleteResult(false, DeleteStatus.NONE, "invalid");
        }
        ExportBundle bundle = export(playerId);
        softBackup.put(playerId, bundle.payload());
        deleteFlags.put(playerId, DeleteStatus.SOFT_DELETED);
        if (jdbc != null) {
            try {
                jdbc.update("UPDATE player SET deleted_at = ? WHERE uid = ?", Instant.now().toString(), playerId);
            } catch (Exception ignored) {
                // 表无 deleted_at 时仅内存标记
            }
        }
        return new DeleteResult(true, DeleteStatus.SOFT_DELETED, "soft_deleted");
    }

    public DeleteResult requestPurge(int playerId) {
        DeleteStatus cur = deleteFlags.getOrDefault(playerId, DeleteStatus.NONE);
        if (cur != DeleteStatus.SOFT_DELETED && cur != DeleteStatus.PURGE_PENDING) {
            softDelete(playerId);
        }
        deleteFlags.put(playerId, DeleteStatus.PURGE_PENDING);
        return new DeleteResult(true, DeleteStatus.PURGE_PENDING, "purge_queued");
    }

    public boolean isDeleted(int playerId) {
        DeleteStatus s = deleteFlags.get(playerId);
        return s == DeleteStatus.SOFT_DELETED || s == DeleteStatus.PURGE_PENDING || s == DeleteStatus.PURGED;
    }

    public DeleteStatus status(int playerId) {
        return deleteFlags.getOrDefault(playerId, DeleteStatus.NONE);
    }
}
