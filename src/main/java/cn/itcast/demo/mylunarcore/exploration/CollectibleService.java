package cn.itcast.demo.mylunarcore.exploration;

import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 隐藏收集品（忆泡/书籍/档案）：普通 / 隐藏 / 彩蛋三级。
 */
@Service
public class CollectibleService {

    private static final Logger log = LoggerFactory.getLogger(CollectibleService.class);

    public enum Tier { NORMAL, HIDDEN, EASTER_EGG }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Vec3(float x, float y, float z) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CollectibleDef(String collectibleId, String name, String tier, int planeId, int floorId,
                                 Vec3 pos, float aoiRevealRadius, String requiredAction,
                                 int loreUnlockThreshold, String loreTextKey) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Root(int schemaVersion, List<CollectibleDef> collectibles) {}

    public record CollectResult(boolean newlyObtained, String collectibleId, String loreTextKey,
                                boolean loreUnlocked, int ownedCount) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Path dataDir;
    private final ObjectProvider<GameSessionManager> sessionProvider;
    private final ObjectProvider<ExplorationService> explorationProvider;
    private final Map<String, CollectibleDef> defs = new ConcurrentHashMap<>();

    public CollectibleService(JdbcTemplate jdbc,
                              ObjectMapper objectMapper,
                              @Value("${lunarcore.data-dir:data}") String dataDir,
                              ObjectProvider<GameSessionManager> sessionProvider,
                              ObjectProvider<ExplorationService> explorationProvider) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.dataDir = Path.of(dataDir);
        this.sessionProvider = sessionProvider;
        this.explorationProvider = explorationProvider;
    }

    @PostConstruct
    public void init() {
        ensureTable();
        reload();
    }

    private void ensureTable() {
        try {
            jdbc.execute("""
                    CREATE TABLE IF NOT EXISTS player_collectibles (
                      player_id INT NOT NULL,
                      collectible_id VARCHAR(64) NOT NULL,
                      obtain_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      PRIMARY KEY (player_id, collectible_id)
                    )
                    """);
        } catch (Exception e) {
            log.debug("player_collectibles ensure skipped: {}", e.getMessage());
        }
    }

    public boolean reload() {
        Path file = dataDir.resolve("CollectibleConfigs.json");
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try {
            Root root = objectMapper.readValue(Files.readString(file), Root.class);
            defs.clear();
            if (root != null && root.collectibles() != null) {
                for (CollectibleDef c : root.collectibles()) {
                    if (c != null && c.collectibleId() != null && !c.collectibleId().isBlank()) {
                        defs.put(c.collectibleId(), c);
                    }
                }
            }
            log.info("CollectibleConfigs loaded count={}", defs.size());
            return true;
        } catch (Exception e) {
            log.warn("load CollectibleConfigs failed: {}", e.toString());
            return false;
        }
    }

    public CollectibleDef find(String id) {
        return defs.get(id);
    }

    public List<CollectibleDef> listVisibleInAoi(int planeId, int floorId, float x, float y, float z) {
        List<CollectibleDef> out = new ArrayList<>();
        for (CollectibleDef c : defs.values()) {
            if (c.planeId() != planeId || c.floorId() != floorId) {
                continue;
            }
            Tier tier = parseTier(c.tier());
            if (tier == Tier.EASTER_EGG) {
                continue;
            }
            if (tier == Tier.NORMAL) {
                out.add(c);
                continue;
            }
            // HIDDEN：靠近才显示
            if (c.pos() == null) {
                continue;
            }
            float r = c.aoiRevealRadius() <= 0 ? 5.0f : c.aoiRevealRadius();
            float dx = x - c.pos().x();
            float dz = z - c.pos().z();
            if (dx * dx + dz * dz <= r * r) {
                out.add(c);
            }
        }
        return out;
    }

    public CollectResult obtain(int playerId, String collectibleId) {
        return obtain(playerId, collectibleId, null);
    }

    public CollectResult obtain(int playerId, String collectibleId, String action) {
        CollectibleDef def = defs.get(collectibleId);
        if (def == null || playerId <= 0) {
            return new CollectResult(false, collectibleId, "", false, 0);
        }
        Tier tier = parseTier(def.tier());
        if (tier == Tier.EASTER_EGG) {
            String need = def.requiredAction() == null ? "" : def.requiredAction();
            if (action == null || !need.equalsIgnoreCase(action)) {
                return new CollectResult(false, collectibleId, "", false, countOwned(playerId));
            }
        }
        int inserted;
        try {
            inserted = jdbc.update(
                    "INSERT IGNORE INTO player_collectibles(player_id, collectible_id, obtain_time) VALUES(?,?,?)",
                    playerId, collectibleId, java.sql.Timestamp.from(Instant.now()));
        } catch (Exception e) {
            log.debug("collectible insert fail: {}", e.getMessage());
            return new CollectResult(false, collectibleId, "", false, countOwned(playerId));
        }
        int owned = countOwned(playerId);
        boolean lore = def.loreUnlockThreshold() > 0 && owned >= def.loreUnlockThreshold();
        if (inserted > 0) {
            pushExplorationUpdate(playerId, def, owned, lore);
            ExplorationService exploration = explorationProvider.getIfAvailable();
            if (exploration != null) {
                exploration.onSceneInteract(playerId, def.planeId(), def.floorId(),
                        collectibleId, "collectible");
            }
        }
        return new CollectResult(inserted > 0, collectibleId,
                lore ? (def.loreTextKey() == null ? "" : def.loreTextKey()) : "",
                lore, owned);
    }

    public int countOwned(int playerId) {
        try {
            Integer n = jdbc.queryForObject(
                    "SELECT COUNT(1) FROM player_collectibles WHERE player_id = ?",
                    Integer.class, playerId);
            return n == null ? 0 : n;
        } catch (Exception e) {
            return 0;
        }
    }

    private void pushExplorationUpdate(int playerId, CollectibleDef def, int owned, boolean lore) {
        GameSessionManager sessions = sessionProvider.getIfAvailable();
        if (sessions == null) {
            return;
        }
        GameSession session = sessions.getOrNull(playerId);
        if (session == null) {
            return;
        }
        try {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("planeId", def.planeId());
            body.put("floorId", def.floorId());
            body.put("collectId", def.collectibleId());
            body.put("kind", "collectible");
            body.put("ownedCollectibles", owned);
            body.put("loreUnlocked", lore);
            body.put("loreTextKey", lore ? def.loreTextKey() : "");
            byte[] payload = objectMapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
            session.send(new GamePacket(CmdIds.EXPLORATION_UPDATE_SC_NOTIFY, payload));
        } catch (Exception e) {
            log.debug("collectible exploration notify failed: {}", e.getMessage());
        }
    }

    private static Tier parseTier(String tier) {
        if (tier == null || tier.isBlank()) {
            return Tier.NORMAL;
        }
        try {
            return Tier.valueOf(tier.toUpperCase(java.util.Locale.ROOT));
        } catch (Exception e) {
            return Tier.NORMAL;
        }
    }
}
