package cn.itcast.demo.mylunarcore.exploration;

import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.scene.SceneInteractHandler;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 机关谜题状态机：INACTIVE → ACTIVE → SOLVED。
 */
@Service
public class PuzzleStateService {

    private static final Logger log = LoggerFactory.getLogger(PuzzleStateService.class);

    public enum PuzzleType { PRESSURE_PLATE, ELEMENT_LIGHT, SEQUENCE_STEP, TIMED_PARKOUR }
    public enum PuzzleState { INACTIVE, ACTIVE, SOLVED }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Vec3(float x, float y, float z) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Checkpoint(String id, float x, float y, float z) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PuzzleDef(int puzzleId, String zoneId, int planeId, int floorId, String type,
                            float radius, Vec3 center, String requiredElement, List<String> sequence,
                            int timeLimitMs, List<Checkpoint> checkpoints, int rewardId,
                            Vec3 hiddenDoorPos, String explorationCollectId) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Root(int schemaVersion, List<PuzzleDef> puzzles) {}

    public record RuntimeState(int puzzleId, PuzzleState state, List<String> sequenceProgress,
                               List<String> checkpointProgress, long activatedAtMs) {}

    public record SolveResult(boolean solved, int puzzleId, int rewardId, Vec3 hiddenDoorPos,
                              String explorationCollectId) {}

    private final ObjectMapper objectMapper;
    private final Path dataDir;
    private final ObjectProvider<GameSessionManager> sessionProvider;
    private final ObjectProvider<SceneInteractHandler> interactProvider;
    private final Map<Integer, PuzzleDef> defs = new ConcurrentHashMap<>();
    /** playerId:puzzleId → runtime */
    private final Map<String, RuntimeState> runtime = new ConcurrentHashMap<>();

    public PuzzleStateService(ObjectMapper objectMapper,
                              @Value("${lunarcore.data-dir:data}") String dataDir,
                              ObjectProvider<GameSessionManager> sessionProvider,
                              ObjectProvider<SceneInteractHandler> interactProvider) {
        this.objectMapper = objectMapper;
        this.dataDir = Path.of(dataDir);
        this.sessionProvider = sessionProvider;
        this.interactProvider = interactProvider;
    }

    @PostConstruct
    public void load() {
        reload();
    }

    public boolean reload() {
        Path file = dataDir.resolve("PuzzleConfigs.json");
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try {
            Root root = objectMapper.readValue(Files.readString(file), Root.class);
            defs.clear();
            if (root != null && root.puzzles() != null) {
                for (PuzzleDef p : root.puzzles()) {
                    if (p != null && p.puzzleId() > 0) {
                        defs.put(p.puzzleId(), p);
                    }
                }
            }
            log.info("PuzzleConfigs loaded count={}", defs.size());
            return true;
        } catch (Exception e) {
            log.warn("load PuzzleConfigs failed: {}", e.toString());
            return false;
        }
    }

    public PuzzleDef find(int puzzleId) {
        return defs.get(puzzleId);
    }

    public RuntimeState activate(int playerId, int puzzleId) {
        PuzzleDef def = defs.get(puzzleId);
        if (def == null) {
            return null;
        }
        RuntimeState state = new RuntimeState(puzzleId, PuzzleState.ACTIVE, new ArrayList<>(),
                new ArrayList<>(), System.currentTimeMillis());
        runtime.put(key(playerId, puzzleId), state);
        return state;
    }

    /** 压力板：玩家位置进入圆心半径。 */
    public SolveResult onPlayerPosition(int playerId, int planeId, int floorId, float x, float y, float z) {
        for (PuzzleDef def : defs.values()) {
            if (def.planeId() != planeId || def.floorId() != floorId) {
                continue;
            }
            if (!PuzzleType.PRESSURE_PLATE.name().equalsIgnoreCase(def.type())) {
                continue;
            }
            if (def.center() == null) {
                continue;
            }
            float dx = x - def.center().x();
            float dz = z - def.center().z();
            float r = def.radius() <= 0 ? 2.0f : def.radius();
            if (dx * dx + dz * dz <= r * r) {
                ensureActive(playerId, def.puzzleId());
                return trySolve(playerId, def);
            }
        }
        return null;
    }

    /** 元素点亮：特定属性攻击命中。 */
    public SolveResult onElementHit(int playerId, int puzzleId, String element) {
        PuzzleDef def = defs.get(puzzleId);
        if (def == null || !PuzzleType.ELEMENT_LIGHT.name().equalsIgnoreCase(def.type())) {
            return null;
        }
        ensureActive(playerId, puzzleId);
        if (element != null && element.equalsIgnoreCase(def.requiredElement())) {
            return trySolve(playerId, def);
        }
        return null;
    }

    /** 顺序踩踏。 */
    public SolveResult onSequencePad(int playerId, int puzzleId, String padId) {
        PuzzleDef def = defs.get(puzzleId);
        if (def == null || !PuzzleType.SEQUENCE_STEP.name().equalsIgnoreCase(def.type())) {
            return null;
        }
        RuntimeState st = ensureActive(playerId, puzzleId);
        List<String> expected = def.sequence() == null ? List.of() : def.sequence();
        List<String> progress = new ArrayList<>(st.sequenceProgress());
        int nextIdx = progress.size();
        if (nextIdx >= expected.size()) {
            return trySolve(playerId, def);
        }
        if (padId != null && padId.equals(expected.get(nextIdx))) {
            progress.add(padId);
            runtime.put(key(playerId, puzzleId),
                    new RuntimeState(puzzleId, PuzzleState.ACTIVE, progress, st.checkpointProgress(), st.activatedAtMs()));
            if (progress.size() >= expected.size()) {
                return trySolve(playerId, def);
            }
        } else {
            runtime.put(key(playerId, puzzleId),
                    new RuntimeState(puzzleId, PuzzleState.ACTIVE, new ArrayList<>(),
                            st.checkpointProgress(), st.activatedAtMs()));
        }
        return null;
    }

    /** 限时跑酷检查点。 */
    public SolveResult onParkourCheckpoint(int playerId, int puzzleId, String checkpointId) {
        PuzzleDef def = defs.get(puzzleId);
        if (def == null || !PuzzleType.TIMED_PARKOUR.name().equalsIgnoreCase(def.type())) {
            return null;
        }
        RuntimeState st = ensureActive(playerId, puzzleId);
        if (def.timeLimitMs() > 0
                && System.currentTimeMillis() - st.activatedAtMs() > def.timeLimitMs()) {
            runtime.put(key(playerId, puzzleId),
                    new RuntimeState(puzzleId, PuzzleState.INACTIVE, List.of(), List.of(), 0));
            return null;
        }
        List<Checkpoint> cps = def.checkpoints() == null ? List.of() : def.checkpoints();
        List<String> progress = new ArrayList<>(st.checkpointProgress());
        int nextIdx = progress.size();
        if (nextIdx < cps.size() && checkpointId != null
                && checkpointId.equalsIgnoreCase(cps.get(nextIdx).id())) {
            progress.add(checkpointId);
            runtime.put(key(playerId, puzzleId),
                    new RuntimeState(puzzleId, PuzzleState.ACTIVE, st.sequenceProgress(), progress, st.activatedAtMs()));
            if (progress.size() >= cps.size()) {
                return trySolve(playerId, def);
            }
        }
        return null;
    }

    private RuntimeState ensureActive(int playerId, int puzzleId) {
        return runtime.compute(key(playerId, puzzleId), (k, old) -> {
            if (old != null && old.state() == PuzzleState.SOLVED) {
                return old;
            }
            if (old != null && old.state() == PuzzleState.ACTIVE) {
                return old;
            }
            return new RuntimeState(puzzleId, PuzzleState.ACTIVE, new ArrayList<>(),
                    new ArrayList<>(), System.currentTimeMillis());
        });
    }

    private SolveResult trySolve(int playerId, PuzzleDef def) {
        RuntimeState st = runtime.get(key(playerId, def.puzzleId()));
        if (st != null && st.state() == PuzzleState.SOLVED) {
            return new SolveResult(false, def.puzzleId(), def.rewardId(), def.hiddenDoorPos(),
                    def.explorationCollectId());
        }
        runtime.put(key(playerId, def.puzzleId()),
                new RuntimeState(def.puzzleId(), PuzzleState.SOLVED, List.of(), List.of(),
                        System.currentTimeMillis()));
        SceneInteractHandler interact = interactProvider.getIfAvailable();
        if (interact != null && def.explorationCollectId() != null && !def.explorationCollectId().isBlank()) {
            interact.onPuzzleSolved(playerId, def.planeId(), def.floorId(), def.explorationCollectId());
        }
        broadcastComplete(playerId, def);
        return new SolveResult(true, def.puzzleId(), def.rewardId(), def.hiddenDoorPos(),
                def.explorationCollectId());
    }

    private void broadcastComplete(int playerId, PuzzleDef def) {
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
            body.put("puzzleId", def.puzzleId());
            body.put("rewardId", def.rewardId());
            if (def.hiddenDoorPos() != null) {
                body.put("hiddenDoorPos", Map.of(
                        "x", def.hiddenDoorPos().x(),
                        "y", def.hiddenDoorPos().y(),
                        "z", def.hiddenDoorPos().z()));
            }
            body.put("zoneId", def.zoneId() == null ? "" : def.zoneId());
            byte[] payload = objectMapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
            session.send(new GamePacket(CmdIds.SCENE_PUZZLE_COMPLETE_SC_NOTIFY, payload));
        } catch (Exception e) {
            log.debug("puzzle complete notify failed: {}", e.getMessage());
        }
    }

    private static String key(int playerId, int puzzleId) {
        return playerId + ":" + puzzleId;
    }
}
