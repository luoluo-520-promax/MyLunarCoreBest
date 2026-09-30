package cn.itcast.demo.mylunarcore.dialogue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家对话进度：已播放标记、选项分支记录；优先 DB，失败回退内存。
 */
@Service
public class DialogueProgressService {

    public record ChoiceRecord(String nodeId, String choiceId, long atMs) {}

    public record Progress(String treeId, String currentNodeId, Set<String> flags, Set<Integer> unlockedCgs,
                           List<ChoiceRecord> choiceHistory, boolean played, String savepointNodeId) {
        public Progress {
            flags = flags == null ? Set.of() : Set.copyOf(flags);
            unlockedCgs = unlockedCgs == null ? Set.of() : Set.copyOf(unlockedCgs);
            choiceHistory = choiceHistory == null ? List.of() : List.copyOf(choiceHistory);
            treeId = treeId == null ? "" : treeId;
            currentNodeId = currentNodeId == null ? "" : currentNodeId;
            savepointNodeId = savepointNodeId == null ? "" : savepointNodeId;
        }

        public Progress(String treeId, String currentNodeId, Set<String> flags, Set<Integer> unlockedCgs,
                        List<ChoiceRecord> choiceHistory, boolean played) {
            this(treeId, currentNodeId, flags, unlockedCgs, choiceHistory, played, "");
        }
    }

    private final Map<String, Progress> byPlayerTree = new ConcurrentHashMap<>();
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public DialogueProgressService() {
        this(null, new ObjectMapper());
    }

    public DialogueProgressService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    public Progress getOrStart(int playerId, String treeId, String entryNodeId) {
        String key = key(playerId, treeId);
        Progress existing = byPlayerTree.get(key);
        if (existing != null && treeId.equals(existing.treeId())) {
            // 修复 hasPlayed/误创建导致的空 currentNodeId
            if ((existing.currentNodeId() == null || existing.currentNodeId().isBlank())
                    && entryNodeId != null && !entryNodeId.isBlank()) {
                Progress repaired = new Progress(existing.treeId(), entryNodeId, existing.flags(),
                        existing.unlockedCgs(), existing.choiceHistory(), existing.played());
                persist(playerId, repaired);
                return repaired;
            }
            return existing;
        }
        Progress loaded = load(playerId, treeId);
        if (loaded != null) {
            if ((loaded.currentNodeId() == null || loaded.currentNodeId().isBlank())
                    && entryNodeId != null && !entryNodeId.isBlank()) {
                loaded = new Progress(loaded.treeId(), entryNodeId, loaded.flags(),
                        loaded.unlockedCgs(), loaded.choiceHistory(), loaded.played());
                persist(playerId, loaded);
                return loaded;
            }
            byPlayerTree.put(key, loaded);
            return loaded;
        }
        Progress started = new Progress(treeId, entryNodeId == null ? "" : entryNodeId,
                Set.of(), Set.of(), List.of(), false);
        persist(playerId, started);
        return started;
    }

    /**
     * 只读查询进度；不存在则返回 null（不会写入空节点进度）。
     */
    public Progress find(int playerId, String treeId) {
        String key = key(playerId, treeId);
        Progress existing = byPlayerTree.get(key);
        if (existing != null && treeId.equals(existing.treeId())) {
            return existing;
        }
        Progress loaded = load(playerId, treeId);
        if (loaded != null) {
            byPlayerTree.put(key, loaded);
        }
        return loaded;
    }

    public void markPlayed(int playerId, String treeId) {
        Progress p = getOrStart(playerId, treeId, "");
        Progress next = new Progress(p.treeId(), p.currentNodeId(), p.flags(), p.unlockedCgs(),
                p.choiceHistory(), true, p.savepointNodeId());
        persist(playerId, next);
    }

    public boolean hasPlayed(int playerId, String treeId) {
        Progress p = find(playerId, treeId);
        return p != null && p.played();
    }

    public Progress advance(int playerId, String nextNodeId, String questFlag, int unlockCgId) {
        return advance(playerId, nextNodeId, questFlag, unlockCgId, null, null);
    }

    public Progress advance(int playerId, String nextNodeId, String questFlag, int unlockCgId,
                            String fromNodeId, String choiceId) {
        Progress current = findCurrent(playerId);
        if (current == null) {
            current = new Progress("", nextNodeId,
                    questFlag == null || questFlag.isBlank() ? Set.of() : Set.of(questFlag),
                    unlockCgId > 0 ? Set.of(unlockCgId) : Set.of(),
                    List.of(), false);
        }
        Set<String> flags = new LinkedHashSet<>(current.flags());
        if (questFlag != null && !questFlag.isBlank()) {
            flags.add(questFlag);
        }
        Set<Integer> cgs = new LinkedHashSet<>(current.unlockedCgs());
        if (unlockCgId > 0) {
            cgs.add(unlockCgId);
        }
        List<ChoiceRecord> history = new ArrayList<>(current.choiceHistory());
        if (choiceId != null && !choiceId.isBlank()) {
            history.add(new ChoiceRecord(fromNodeId == null ? current.currentNodeId() : fromNodeId,
                    choiceId, System.currentTimeMillis()));
        }
        boolean played = current.played() || nextNodeId == null || nextNodeId.isBlank();
        Progress next = new Progress(current.treeId(), nextNodeId == null ? "" : nextNodeId,
                flags, cgs, history, played, current.savepointNodeId());
        persist(playerId, next);
        return next;
    }

    public Progress recordChoiceAndAdvance(int playerId, String treeId, String fromNodeId,
                                           String choiceId, String nextNodeId,
                                           String questFlag, int unlockCgId) {
        Progress current = getOrStart(playerId, treeId, fromNodeId);
        Set<String> flags = new LinkedHashSet<>(current.flags());
        if (questFlag != null && !questFlag.isBlank()) {
            flags.add(questFlag);
        }
        Set<Integer> cgs = new LinkedHashSet<>(current.unlockedCgs());
        if (unlockCgId > 0) {
            cgs.add(unlockCgId);
        }
        List<ChoiceRecord> history = new ArrayList<>(current.choiceHistory());
        if (choiceId != null && !choiceId.isBlank()) {
            history.add(new ChoiceRecord(fromNodeId, choiceId, System.currentTimeMillis()));
        }
        Progress next = new Progress(treeId, nextNodeId, flags, cgs, history, false,
                fromNodeId == null || fromNodeId.isBlank() ? current.savepointNodeId() : fromNodeId);
        persist(playerId, next);
        return next;
    }

    /** 关键选项节点 SAVEPOINT：断线后可回到选项前。 */
    public Progress markSavepoint(int playerId, String treeId, String nodeId) {
        Progress current = getOrStart(playerId, treeId, nodeId);
        Progress next = new Progress(current.treeId(), current.currentNodeId(), current.flags(),
                current.unlockedCgs(), current.choiceHistory(), current.played(),
                nodeId == null || nodeId.isBlank() ? current.currentNodeId() : nodeId);
        persist(playerId, next);
        return next;
    }

    /**
     * 从最近 SAVEPOINT 恢复当前节点；无存档点返回 null。
     */
    public Progress resumeFromSavepoint(int playerId, String treeId) {
        Progress current = find(playerId, treeId);
        if (current == null || current.savepointNodeId() == null || current.savepointNodeId().isBlank()) {
            return null;
        }
        Progress next = new Progress(current.treeId(), current.savepointNodeId(), current.flags(),
                current.unlockedCgs(), current.choiceHistory(), false, current.savepointNodeId());
        persist(playerId, next);
        return next;
    }

    public boolean hasCg(int playerId, int cgId) {
        return unlockedCgs(playerId).contains(cgId);
    }

    public Set<Integer> unlockedCgs(int playerId) {
        Set<Integer> all = new LinkedHashSet<>();
        String prefix = playerId + ":";
        for (Map.Entry<String, Progress> e : byPlayerTree.entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                all.addAll(e.getValue().unlockedCgs());
            }
        }
        if (jdbc == null) {
            return Collections.unmodifiableSet(all);
        }
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT unlocked_cgs_json FROM dialogue_progress WHERE player_id = ?", playerId);
            for (Map<String, Object> row : rows) {
                all.addAll(readIntSet(String.valueOf(row.get("unlocked_cgs_json"))));
            }
        } catch (Exception ignored) {
            // ignore
        }
        return Collections.unmodifiableSet(all);
    }

    public List<ChoiceRecord> choiceHistory(int playerId, String treeId) {
        return getOrStart(playerId, treeId, "").choiceHistory();
    }

    /** 任务/场景解锁：是否持有某剧情 flag（跨树聚合）。 */
    public boolean hasQuestFlag(int playerId, String flag) {
        if (flag == null || flag.isBlank()) {
            return false;
        }
        return allFlags(playerId).contains(flag);
    }

    /** 是否选过指定 choiceId（任意树）。 */
    public boolean hasChosen(int playerId, String choiceId) {
        if (choiceId == null || choiceId.isBlank()) {
            return false;
        }
        String prefix = playerId + ":";
        for (Map.Entry<String, Progress> e : byPlayerTree.entrySet()) {
            if (!e.getKey().startsWith(prefix)) {
                continue;
            }
            for (ChoiceRecord c : e.getValue().choiceHistory()) {
                if (choiceId.equals(c.choiceId())) {
                    return true;
                }
            }
        }
        if (jdbc == null) {
            return false;
        }
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT choice_history_json FROM dialogue_progress WHERE player_id = ?", playerId);
            for (Map<String, Object> row : rows) {
                for (ChoiceRecord c : readChoices(String.valueOf(row.get("choice_history_json")))) {
                    if (choiceId.equals(c.choiceId())) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {
            // ignore
        }
        return false;
    }

    /** 聚合玩家全部剧情 flags，供任务/关卡解锁查询。 */
    public Set<String> allFlags(int playerId) {
        Set<String> all = new LinkedHashSet<>();
        String prefix = playerId + ":";
        for (Map.Entry<String, Progress> e : byPlayerTree.entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                all.addAll(e.getValue().flags());
            }
        }
        if (jdbc == null) {
            return Collections.unmodifiableSet(all);
        }
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT flags_json FROM dialogue_progress WHERE player_id = ?", playerId);
            for (Map<String, Object> row : rows) {
                all.addAll(readStringSet(String.valueOf(row.get("flags_json"))));
            }
        } catch (Exception ignored) {
            // ignore
        }
        return Collections.unmodifiableSet(all);
    }

    private Progress findCurrent(int playerId) {
        String prefix = playerId + ":";
        Progress latest = null;
        for (Map.Entry<String, Progress> e : byPlayerTree.entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                latest = e.getValue();
            }
        }
        return latest;
    }

    private void persist(int playerId, Progress p) {
        byPlayerTree.put(key(playerId, p.treeId()), p);
        if (jdbc == null) {
            return;
        }
        try {
            jdbc.update("""
                    INSERT INTO dialogue_progress
                      (player_id, tree_id, current_node_id, flags_json, unlocked_cgs_json, choice_history_json, played, savepoint_node_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                      current_node_id=VALUES(current_node_id), flags_json=VALUES(flags_json),
                      unlocked_cgs_json=VALUES(unlocked_cgs_json), choice_history_json=VALUES(choice_history_json),
                      played=VALUES(played), savepoint_node_id=VALUES(savepoint_node_id)
                    """, playerId, p.treeId(), p.currentNodeId(),
                    objectMapper.writeValueAsString(p.flags()),
                    objectMapper.writeValueAsString(p.unlockedCgs()),
                    objectMapper.writeValueAsString(p.choiceHistory()),
                    p.played() ? 1 : 0,
                    p.savepointNodeId());
        } catch (Exception ignored) {
            try {
                jdbc.update("""
                        INSERT INTO dialogue_progress
                          (player_id, tree_id, current_node_id, flags_json, unlocked_cgs_json, choice_history_json, played)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                          current_node_id=VALUES(current_node_id), flags_json=VALUES(flags_json),
                          unlocked_cgs_json=VALUES(unlocked_cgs_json), choice_history_json=VALUES(choice_history_json),
                          played=VALUES(played)
                        """, playerId, p.treeId(), p.currentNodeId(),
                        objectMapper.writeValueAsString(p.flags()),
                        objectMapper.writeValueAsString(p.unlockedCgs()),
                        objectMapper.writeValueAsString(p.choiceHistory()),
                        p.played() ? 1 : 0);
            } catch (Exception ignored2) {
                // memory only
            }
        }
    }

    private Progress load(int playerId, String treeId) {
        if (jdbc == null) {
            return null;
        }
        try {
            return jdbc.query("""
                    SELECT tree_id, current_node_id, flags_json, unlocked_cgs_json, choice_history_json, played
                    FROM dialogue_progress WHERE player_id = ? AND tree_id = ?
                    """, rs -> {
                if (!rs.next()) {
                    return null;
                }
                return new Progress(
                        rs.getString(1),
                        rs.getString(2),
                        readStringSet(rs.getString(3)),
                        readIntSet(rs.getString(4)),
                        readChoices(rs.getString(5)),
                        rs.getInt(6) == 1);
            }, playerId, treeId);
        } catch (Exception e) {
            return null;
        }
    }

    private Set<String> readStringSet(String json) {
        try {
            List<String> list = objectMapper.readValue(json, new TypeReference<>() {});
            return new LinkedHashSet<>(list);
        } catch (Exception e) {
            return Set.of();
        }
    }

    private Set<Integer> readIntSet(String json) {
        try {
            List<Integer> list = objectMapper.readValue(json, new TypeReference<>() {});
            return new LinkedHashSet<>(list);
        } catch (Exception e) {
            return Set.of();
        }
    }

    private List<ChoiceRecord> readChoices(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private static String key(int playerId, String treeId) {
        return playerId + ":" + (treeId == null ? "" : treeId);
    }
}
