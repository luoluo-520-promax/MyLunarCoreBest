package cn.itcast.demo.mylunarcore.story;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 主线章节进度：通关 Challenge/Battle 后解锁下一 Plane/Floor，供场景进入校验。
 */
@Service
public class StoryChapterService {

    private static final Logger log = LoggerFactory.getLogger(StoryChapterService.class);

    public record ChapterView(int chapterId, String title, int currentChapterId, boolean unlocked,
                              int unlockPlaneId, int unlockFloorId) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ChapterCfg(int chapterId, String title, List<Integer> mainlineQuestIds,
                             int unlockPlaneId, int unlockFloorId, int requiredChallengeId,
                             int requiredBattleStageId, int nextChapterId, int priority) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PlaneKey(int planeId, int floorId) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RootCfg(List<ChapterCfg> chapters, List<PlaneKey> defaultUnlockedPlanes) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Path dataDir;
    private final Map<Integer, ChapterCfg> chapters = new ConcurrentHashMap<>();
    private final List<PlaneKey> defaultUnlocked = new ArrayList<>();

    public StoryChapterService(JdbcTemplate jdbc,
                               ObjectMapper objectMapper,
                               @Value("${lunarcore.data-dir:data}") String dataDir) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.dataDir = Path.of(dataDir);
    }

    @PostConstruct
    public void loadConfig() {
        Path file = dataDir.resolve("ChapterConfigs.json");
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            RootCfg root = objectMapper.readValue(Files.readString(file), RootCfg.class);
            chapters.clear();
            defaultUnlocked.clear();
            if (root != null) {
                if (root.chapters() != null) {
                    for (ChapterCfg c : root.chapters()) {
                        chapters.put(c.chapterId(), c);
                    }
                }
                if (root.defaultUnlockedPlanes() != null) {
                    defaultUnlocked.addAll(root.defaultUnlockedPlanes());
                }
            }
        } catch (Exception e) {
            log.warn("load ChapterConfigs failed: {}", e.toString());
        }
    }

    public boolean canEnterPlane(int playerId, int planeId, int floorId) {
        if (chapters.isEmpty()) {
            return true; // 无配置时不拦截（兼容旧联调）
        }
        ensureProgress(playerId);
        Set<String> unlocked = loadUnlockedPlanes(playerId);
        if (unlocked.contains(key(planeId, floorId))) {
            return true;
        }
        for (PlaneKey p : defaultUnlocked) {
            if (p.planeId() == planeId && p.floorId() == floorId) {
                return true;
            }
        }
        // 当前章节及其已完成章节对应的地图
        Progress prog = loadProgress(playerId);
        ChapterCfg cur = chapters.get(prog.currentChapterId);
        if (cur != null && cur.unlockPlaneId() == planeId && cur.unlockFloorId() == floorId) {
            return true;
        }
        for (int cid : prog.completedChapters) {
            ChapterCfg c = chapters.get(cid);
            if (c != null && c.unlockPlaneId() == planeId && c.unlockFloorId() == floorId) {
                return true;
            }
        }
        return false;
    }

    /** 战斗胜利后尝试推进主线。 */
    public void onBattleCleared(int playerId, int battleStageId) {
        if (playerId <= 0 || battleStageId <= 0) {
            return;
        }
        ensureProgress(playerId);
        Progress prog = loadProgress(playerId);
        ChapterCfg cur = chapters.get(prog.currentChapterId);
        if (cur == null) {
            return;
        }
        if (cur.requiredBattleStageId() > 0 && cur.requiredBattleStageId() == battleStageId) {
            advance(playerId, prog, cur);
        }
    }

    public void onChallengeCleared(int playerId, int challengeId) {
        if (playerId <= 0 || challengeId <= 0) {
            return;
        }
        ensureProgress(playerId);
        Progress prog = loadProgress(playerId);
        ChapterCfg cur = chapters.get(prog.currentChapterId);
        if (cur == null) {
            return;
        }
        if (cur.requiredChallengeId() > 0 && cur.requiredChallengeId() == challengeId) {
            advance(playerId, prog, cur);
        }
    }

    /** 主线强制触发：返回当前章节主线任务 ID（按 priority 降序）。 */
    public List<Integer> activeMainlineQuestIds(int playerId) {
        ensureProgress(playerId);
        Progress prog = loadProgress(playerId);
        ChapterCfg cur = chapters.get(prog.currentChapterId);
        if (cur == null || cur.mainlineQuestIds() == null) {
            return List.of();
        }
        return List.copyOf(cur.mainlineQuestIds());
    }

    /** 当前主线优先级（越大越优先）；无配置返回 0。 */
    public int currentMainlinePriority(int playerId) {
        ensureProgress(playerId);
        Progress prog = loadProgress(playerId);
        ChapterCfg cur = chapters.get(prog.currentChapterId);
        return cur == null ? 0 : cur.priority();
    }

    public boolean isMainlineQuest(int questId) {
        for (ChapterCfg c : chapters.values()) {
            if (c.mainlineQuestIds() != null && c.mainlineQuestIds().contains(questId)) {
                return true;
            }
        }
        return false;
    }

    public List<ChapterView> list(int playerId) {
        ensureProgress(playerId);
        Progress prog = loadProgress(playerId);
        List<ChapterView> out = new ArrayList<>();
        chapters.values().stream()
                .sorted(Comparator.comparingInt(ChapterCfg::chapterId))
                .forEach(c -> out.add(new ChapterView(
                        c.chapterId(), c.title(), prog.currentChapterId,
                        prog.currentChapterId >= c.chapterId || prog.completedChapters.contains(c.chapterId),
                        c.unlockPlaneId(), c.unlockFloorId())));
        return out;
    }

    private void advance(int playerId, Progress prog, ChapterCfg cur) {
        Set<Integer> completed = new HashSet<>(prog.completedChapters);
        completed.add(cur.chapterId());
        Set<String> unlocked = loadUnlockedPlanes(playerId);
        unlocked.add(key(cur.unlockPlaneId(), cur.unlockFloorId()));
        int next = cur.nextChapterId() > 0 ? cur.nextChapterId() : cur.chapterId();
        ChapterCfg nextCfg = chapters.get(next);
        if (nextCfg != null) {
            unlocked.add(key(nextCfg.unlockPlaneId(), nextCfg.unlockFloorId()));
        }
        try {
            jdbc.update("""
                    UPDATE story_chapter_progress
                    SET current_chapter_id = ?, completed_chapters_json = ?, unlocked_planes_json = ?,
                        updated_at = NOW(3)
                    WHERE player_id = ?
                    """, next, objectMapper.writeValueAsString(completed),
                    objectMapper.writeValueAsString(unlocked), playerId);
            log.info("Story chapter advanced player={} from={} to={}", playerId, cur.chapterId(), next);
        } catch (Exception e) {
            log.warn("story advance failed: {}", e.toString());
        }
    }

    private void ensureProgress(int playerId) {
        if (playerId <= 0) {
            return;
        }
        try {
            String unlockedJson = objectMapper.writeValueAsString(
                    defaultUnlocked.stream().map(p -> key(p.planeId(), p.floorId())).toList());
            jdbc.update("""
                    INSERT IGNORE INTO story_chapter_progress
                    (player_id, current_chapter_id, completed_chapters_json, unlocked_planes_json)
                    VALUES (?, 1, '[]', ?)
                    """, playerId, unlockedJson);
        } catch (Exception ignored) {
        }
    }

    private Progress loadProgress(int playerId) {
        try {
            List<Progress> list = jdbc.query("""
                    SELECT current_chapter_id, completed_chapters_json FROM story_chapter_progress WHERE player_id = ?
                    """, (rs, i) -> new Progress(rs.getInt(1), parseIntSet(rs.getString(2))), playerId);
            if (!list.isEmpty()) {
                return list.get(0);
            }
        } catch (Exception ignored) {
        }
        return new Progress(1, Set.of());
    }

    private Set<String> loadUnlockedPlanes(int playerId) {
        try {
            String json = jdbc.query("""
                    SELECT unlocked_planes_json FROM story_chapter_progress WHERE player_id = ?
                    """, rs -> rs.next() ? rs.getString(1) : null, playerId);
            if (json == null || json.isBlank()) {
                return new HashSet<>();
            }
            return new HashSet<>(objectMapper.readValue(json, new TypeReference<List<String>>() {}));
        } catch (Exception e) {
            return new HashSet<>();
        }
    }

    private Set<Integer> parseIntSet(String json) {
        if (json == null || json.isBlank()) {
            return new HashSet<>();
        }
        try {
            return new HashSet<>(objectMapper.readValue(json, new TypeReference<List<Integer>>() {}));
        } catch (Exception e) {
            return new HashSet<>();
        }
    }

    private static String key(int planeId, int floorId) {
        return planeId + ":" + floorId;
    }

    private record Progress(int currentChapterId, Set<Integer> completedChapters) {}
}
