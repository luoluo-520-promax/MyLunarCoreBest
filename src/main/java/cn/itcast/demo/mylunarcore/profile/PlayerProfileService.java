package cn.itcast.demo.mylunarcore.profile;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 社交侧玩家资料：签名、心情文案、自定义在线状态；预设状态模板由后台/配置下发。
 */
@Service
public class PlayerProfileService {

    private static final Logger log = LoggerFactory.getLogger(PlayerProfileService.class);
    private static final int SIGNATURE_MAX = 80;
    private static final int STATUS_MSG_MAX = 40;

    public record Profile(int playerId, String signature, String statusMessage, String customStatus) {}

    public record StatusTemplate(String code, String label) {}

    public record OpResult(boolean ok, int retcode, Profile profile) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RootCfg(List<StatusTemplate> presets) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Path dataDir;
    private final ConcurrentHashMap<Integer, Profile> mem = new ConcurrentHashMap<>();
    private volatile List<StatusTemplate> presets = List.of(
            new StatusTemplate("ONLINE", "在线"),
            new StatusTemplate("BUSY", "忙碌"),
            new StatusTemplate("DND", "勿扰"),
            new StatusTemplate("PARTY", "组队中"),
            new StatusTemplate("ABYSS", "在打深渊")
    );

    public PlayerProfileService(JdbcTemplate jdbc,
                                ObjectMapper objectMapper,
                                @Value("${lunarcore.data-dir:data}") String dataDir) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.dataDir = Path.of(dataDir);
    }

    @PostConstruct
    public void loadPresets() {
        Path file = dataDir.resolve("PlayerStatusPresets.json");
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            RootCfg root = objectMapper.readValue(Files.readString(file), RootCfg.class);
            if (root != null && root.presets() != null && !root.presets().isEmpty()) {
                presets = List.copyOf(root.presets());
            }
        } catch (Exception e) {
            log.warn("load PlayerStatusPresets failed: {}", e.toString());
        }
    }

    public List<StatusTemplate> statusPresets() {
        return presets;
    }

    public Profile getOrDefault(int playerId) {
        Profile cached = mem.get(playerId);
        if (cached != null) {
            return cached;
        }
        Profile loaded = load(playerId);
        mem.put(playerId, loaded);
        return loaded;
    }

    public OpResult setStatus(int playerId, String signature, String statusMessage, String customStatus) {
        if (playerId <= 0) {
            return new OpResult(false, 1, getOrDefault(playerId));
        }
        String sig = trim(signature, SIGNATURE_MAX);
        String msg = trim(statusMessage, STATUS_MSG_MAX);
        String status = customStatus == null || customStatus.isBlank() ? "ONLINE" : customStatus.trim();
        if (status.length() > 32) {
            status = status.substring(0, 32);
        }
        Profile next = new Profile(playerId, sig, msg, status);
        mem.put(playerId, next);
        try {
            jdbc.update("""
                    INSERT INTO player_social_profile
                    (player_id, signature, status_message, custom_status, updated_at)
                    VALUES (?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE signature=VALUES(signature),
                      status_message=VALUES(status_message),
                      custom_status=VALUES(custom_status),
                      updated_at=VALUES(updated_at)
                    """, playerId, sig, msg, status, Instant.now().toString());
        } catch (Exception e) {
            log.debug("profile persist skipped: {}", e.getMessage());
        }
        return new OpResult(true, 0, next);
    }

    public List<Map<String, Object>> presetsForAdmin() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (StatusTemplate t : presets) {
            out.add(Map.of("code", t.code(), "label", t.label()));
        }
        return out;
    }

    public void replacePresets(List<StatusTemplate> next) {
        if (next == null || next.isEmpty()) {
            return;
        }
        presets = List.copyOf(next);
        try {
            Path file = dataDir.resolve("PlayerStatusPresets.json");
            objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValue(file.toFile(), Map.of("presets", presets));
        } catch (Exception e) {
            log.warn("save PlayerStatusPresets failed: {}", e.toString());
        }
    }

    private Profile load(int playerId) {
        try {
            List<Profile> list = jdbc.query("""
                    SELECT signature, status_message, custom_status
                    FROM player_social_profile WHERE player_id=?
                    """, (rs, i) -> new Profile(playerId,
                    nullToEmpty(rs.getString("signature")),
                    nullToEmpty(rs.getString("status_message")),
                    nullToEmpty(rs.getString("custom_status"))), playerId);
            if (!list.isEmpty()) {
                Profile p = list.get(0);
                if (p.customStatus().isBlank()) {
                    return new Profile(playerId, p.signature(), p.statusMessage(), "ONLINE");
                }
                return p;
            }
        } catch (Exception ignored) {
        }
        return new Profile(playerId, "", "", "ONLINE");
    }

    private static String trim(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
