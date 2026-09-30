package cn.itcast.demo.mylunarcore.repo;

import cn.itcast.demo.mylunarcore.model.QuestProgressEntity;
import cn.itcast.demo.mylunarcore.quest.QuestConfigRepository;
import cn.itcast.demo.mylunarcore.quest.QuestConfigRepository.ObjectiveConfig;
import cn.itcast.demo.mylunarcore.quest.QuestConfigRepository.QuestConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Repository
public class QuestProgressRepository {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Integer>> OBJ_MAP = new TypeReference<>() {};

    private final JdbcTemplate jdbcTemplate;
    private final QuestConfigRepository questConfigRepository;

    public QuestProgressRepository(JdbcTemplate jdbcTemplate, QuestConfigRepository questConfigRepository) {
        this.jdbcTemplate = jdbcTemplate;
        this.questConfigRepository = questConfigRepository;
    }

    public List<QuestProgressEntity> listByPlayer(int playerId) {
        String sql = "SELECT * FROM quest_progress WHERE player_id = ? AND status != 4 ORDER BY id DESC";
        return jdbcTemplate.query(sql, (rs, rowNum) -> mapRow(rs), playerId);
    }

    public QuestProgressEntity find(int playerId, int questId) {
        String sql = "SELECT * FROM quest_progress WHERE player_id = ? AND quest_id = ? LIMIT 1";
        List<QuestProgressEntity> list = jdbcTemplate.query(sql, (rs, rowNum) -> mapRow(rs), playerId, questId);
        return list.isEmpty() ? null : list.get(0);
    }

    public long insert(int playerId, int questId, int status, String objectivesJson) {
        String sql = "INSERT INTO quest_progress(player_id, quest_id, status, objectives_json) VALUES(?, ?, ?, ?)";
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(con -> {
            PreparedStatement ps = con.prepareStatement(sql, new String[]{"id"});
            ps.setInt(1, playerId);
            ps.setInt(2, questId);
            ps.setInt(3, status);
            ps.setString(4, objectivesJson);
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        return key == null ? 0 : key.longValue();
    }

    public int update(int playerId, int questId, int status, String objectivesJson) {
        String sql = "UPDATE quest_progress SET status = ?, objectives_json = ?, updated_at = NOW() WHERE player_id = ? AND quest_id = ?";
        return jdbcTemplate.update(sql, status, objectivesJson, playerId, questId);
    }

    /** 列出玩家任务进度（排除已放弃 status=4）。 */
    public List<QuestProgressEntity> list(int playerId) {
        return listByPlayer(playerId);
    }

    /** 接取任务：无记录则插入 status=1，有记录则更新为进行中。 */
    public void ensureAccepted(int playerId, int questId) {
        QuestConfig cfg = questConfigRepository.find(questId);
        String initialJson = cfg == null ? "{}" : toJson(zeroObjectives(cfg));
        QuestProgressEntity existing = find(playerId, questId);
        if (existing == null) {
            insert(playerId, questId, 1, initialJson);
        } else {
            String json = existing.getObjectivesJson();
            if (json == null || json.isBlank() || "{}".equals(json.trim())) {
                json = initialJson;
            }
            update(playerId, questId, 1, json);
        }
    }

    /** 是否已完成待提交（status=2）。 */
    public boolean isCompleted(int playerId, int questId) {
        QuestProgressEntity q = find(playerId, questId);
        return q != null && q.getStatus() == 2;
    }

    /** 标记为已提交（status=3）。 */
    public void markSubmitted(int playerId, int questId) {
        QuestProgressEntity q = find(playerId, questId);
        update(playerId, questId, 3, q == null || q.getObjectivesJson() == null ? "{}" : q.getObjectivesJson());
    }

    /**
     * 响应任务触发：匹配进行中任务的目标，递增计数；全部达标则 status=2。
     * <ul>
     *   <li>triggerType=1 杀怪：p1=monsterId</li>
     *   <li>triggerType=2 NPC：p1=npcId</li>
     *   <li>triggerType=3 场景事件：p2=事件子类型（targetId 匹配 p2，targetId=0 通配）</li>
     *   <li>triggerType=4 通用：targetId=0 或 targetId==p1</li>
     * </ul>
     */
    public void applyTrigger(int playerId, int triggerType, long p1, long p2, long p3) {
        List<QuestProgressEntity> inProgress = listByPlayer(playerId).stream()
                .filter(q -> q.getStatus() == 1)
                .toList();
        for (QuestProgressEntity progress : inProgress) {
            QuestConfig cfg = questConfigRepository.find(progress.getQuestId());
            if (cfg == null || cfg.objectives() == null || cfg.objectives().isEmpty()) {
                continue;
            }
            Map<String, Integer> counts = parseObjectives(progress.getObjectivesJson());
            boolean changed = false;
            for (ObjectiveConfig obj : cfg.objectives()) {
                if (obj.targetType() != triggerType) {
                    continue;
                }
                if (!matchesTarget(obj, triggerType, p1, p2)) {
                    continue;
                }
                String key = String.valueOf(obj.objectiveId());
                int current = counts.getOrDefault(key, 0);
                int required = Math.max(1, obj.required());
                if (current < required) {
                    counts.put(key, current + 1);
                    changed = true;
                }
            }
            if (!changed) {
                continue;
            }
            int newStatus = allObjectivesMet(cfg, counts) ? 2 : 1;
            update(playerId, progress.getQuestId(), newStatus, toJson(counts));
        }
    }

    private static boolean matchesTarget(ObjectiveConfig obj, int triggerType, long p1, long p2) {
        int targetId = obj.targetId();
        if (triggerType == 3) {
            return targetId == 0 || targetId == (int) p2;
        }
        if (triggerType == 4) {
            return targetId == 0 || targetId == (int) p1;
        }
        return targetId == 0 || targetId == (int) p1;
    }

    private static boolean allObjectivesMet(QuestConfig cfg, Map<String, Integer> counts) {
        for (ObjectiveConfig obj : cfg.objectives()) {
            int required = Math.max(1, obj.required());
            int current = counts.getOrDefault(String.valueOf(obj.objectiveId()), 0);
            if (current < required) {
                return false;
            }
        }
        return true;
    }

    private static Map<String, Integer> zeroObjectives(QuestConfig cfg) {
        Map<String, Integer> map = new HashMap<>();
        if (cfg.objectives() != null) {
            for (ObjectiveConfig obj : cfg.objectives()) {
                map.put(String.valueOf(obj.objectiveId()), 0);
            }
        }
        return map;
    }

    private static Map<String, Integer> parseObjectives(String json) {
        if (json == null || json.isBlank()) {
            return new HashMap<>();
        }
        try {
            Map<String, Integer> parsed = MAPPER.readValue(json, OBJ_MAP);
            return parsed == null ? new HashMap<>() : new HashMap<>(parsed);
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private static String toJson(Map<String, Integer> counts) {
        try {
            return MAPPER.writeValueAsString(counts);
        } catch (Exception e) {
            return "{}";
        }
    }

    private QuestProgressEntity mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        QuestProgressEntity q = new QuestProgressEntity();
        q.setId(rs.getLong("id"));
        q.setPlayerId(rs.getInt("player_id"));
        q.setQuestId(rs.getInt("quest_id"));
        q.setStatus(rs.getInt("status"));
        q.setObjectivesJson(rs.getString("objectives_json"));
        q.setCreatedAt(rs.getTimestamp("created_at"));
        q.setUpdatedAt(rs.getTimestamp("updated_at"));
        return q;
    }
}
