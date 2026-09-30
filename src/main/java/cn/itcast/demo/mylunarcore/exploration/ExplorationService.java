package cn.itcast.demo.mylunarcore.exploration;

import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.protocol.QolSocialSystemProto;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
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
 * 区域探索度：按 Plane/Floor 记录宝箱/解密/观景点等收集状态，并推送进度变更。
 */
@Service
public class ExplorationService {

    private static final Logger log = LoggerFactory.getLogger(ExplorationService.class);

    public record CollectableDef(String collectId, String kind, String name) {}

    public record RegionProgress(int planeId, int floorId, int collected, int total,
                                 int percent, List<CollectableView> collectables) {}

    public record CollectableView(String collectId, String kind, String name, boolean collected) {}

    public record RecordResult(boolean newlyCollected, RegionProgress progress) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RegionCfg(int planeId, int floorId, List<CollectableDef> collectables) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RootCfg(List<RegionCfg> regions) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Path dataDir;
    private final ObjectProvider<GameSessionManager> sessionProvider;
    private final Map<String, RegionCfg> regions = new ConcurrentHashMap<>();

    public ExplorationService(JdbcTemplate jdbc,
                              ObjectMapper objectMapper,
                              @Value("${lunarcore.data-dir:data}") String dataDir,
                              ObjectProvider<GameSessionManager> sessionProvider) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.dataDir = Path.of(dataDir);
        this.sessionProvider = sessionProvider;
    }

    @PostConstruct
    public void loadConfig() {
        Path file = dataDir.resolve("ExplorationConfigs.json");
        if (!Files.isRegularFile(file)) {
            seedDefault();
            return;
        }
        try {
            RootCfg root = objectMapper.readValue(Files.readString(file), RootCfg.class);
            regions.clear();
            if (root != null && root.regions() != null) {
                for (RegionCfg r : root.regions()) {
                    regions.put(key(r.planeId(), r.floorId()), r);
                }
            }
            if (regions.isEmpty()) {
                seedDefault();
            }
        } catch (Exception e) {
            log.warn("load ExplorationConfigs failed: {}", e.toString());
            seedDefault();
        }
    }

    private void seedDefault() {
        regions.put(key(10001, 1), new RegionCfg(10001, 1, List.of(
                new CollectableDef("chest_1", "chest", "路边宝箱"),
                new CollectableDef("chest_2", "chest", "山崖宝箱"),
                new CollectableDef("puzzle_1", "puzzle", "石碑解密"),
                new CollectableDef("view_1", "viewpoint", "观景台"),
                new CollectableDef("book_1", "book", "旧书页")
        )));
    }

    public RegionProgress getInfo(int playerId, int planeId, int floorId) {
        RegionCfg cfg = regions.getOrDefault(key(planeId, floorId),
                new RegionCfg(planeId, floorId, List.of()));
        List<String> owned = loadCollected(playerId, planeId, floorId);
        List<CollectableView> views = new ArrayList<>();
        int collected = 0;
        for (CollectableDef def : cfg.collectables() == null ? List.<CollectableDef>of() : cfg.collectables()) {
            boolean yes = owned.contains(def.collectId());
            if (yes) {
                collected++;
            }
            views.add(new CollectableView(def.collectId(), def.kind(), def.name(), yes));
        }
        int total = views.size();
        int percent = total <= 0 ? 0 : (collected * 100 / total);
        return new RegionProgress(planeId, floorId, collected, total, percent, views);
    }

    public RecordResult recordCollect(int playerId, int planeId, int floorId,
                                      String collectId, String kind) {
        if (playerId <= 0 || collectId == null || collectId.isBlank()) {
            return new RecordResult(false, getInfo(playerId, planeId, floorId));
        }
        boolean inserted = false;
        try {
            int n = jdbc.update("""
                    INSERT IGNORE INTO player_exploration
                    (player_id, plane_id, floor_id, collect_id, kind, collected_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, playerId, planeId, floorId, collectId,
                    kind == null ? "other" : kind, Instant.now().toString());
            inserted = n > 0;
        } catch (Exception e) {
            log.debug("exploration persist skipped: {}", e.getMessage());
        }
        RegionProgress progress = getInfo(playerId, planeId, floorId);
        if (inserted) {
            pushUpdate(playerId, progress, collectId, kind);
        }
        return new RecordResult(inserted, progress);
    }

    /** 开启宝箱 / 拾取场景物时由 SceneInteractHandler 调用。 */
    public void onSceneInteract(int playerId, int planeId, int floorId, String entityId, String interactType) {
        String kind = switch (interactType == null ? "" : interactType.toLowerCase()) {
            case "chest", "pickup", "prop" -> "chest";
            case "puzzle", "decrypt" -> "puzzle";
            case "viewpoint", "view" -> "viewpoint";
            default -> "other";
        };
        String collectId = entityId == null || entityId.isBlank()
                ? kind + "_" + planeId + "_" + floorId + "_" + System.currentTimeMillis() % 100000
                : entityId;
        recordCollect(playerId, planeId, floorId, collectId, kind);
    }

    private void pushUpdate(int playerId, RegionProgress progress, String collectId, String kind) {
        GameSessionManager sessions = sessionProvider.getIfAvailable();
        if (sessions == null) {
            return;
        }
        GameSession session = sessions.getOrNull(playerId);
        if (session == null) {
            return;
        }
        QolSocialSystemProto.ExplorationUpdateScNotify notify =
                QolSocialSystemProto.ExplorationUpdateScNotify.newBuilder()
                        .setPlaneId(progress.planeId())
                        .setFloorId(progress.floorId())
                        .setCollectId(collectId == null ? "" : collectId)
                        .setKind(kind == null ? "" : kind)
                        .setCollected(progress.collected())
                        .setTotal(progress.total())
                        .setProgressPercent(progress.percent())
                        .build();
        session.send(new GamePacket(CmdIds.EXPLORATION_UPDATE_SC_NOTIFY, notify.toByteArray()));
    }

    private List<String> loadCollected(int playerId, int planeId, int floorId) {
        try {
            return jdbc.queryForList("""
                    SELECT collect_id FROM player_exploration
                    WHERE player_id=? AND plane_id=? AND floor_id=?
                    """, String.class, playerId, planeId, floorId);
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String key(int planeId, int floorId) {
        return planeId + ":" + floorId;
    }
}
