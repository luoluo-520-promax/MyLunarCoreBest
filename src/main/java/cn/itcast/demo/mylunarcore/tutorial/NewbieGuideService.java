package cn.itcast.demo.mylunarcore.tutorial;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 新手引导：步骤链 + 检查点（committed）落库 + 断线重连从最近检查点恢复 + 强制跳过。
 */
@Service
public class NewbieGuideService {

    public record Step(String id, String title, String hint, String assistPrompt) {}

    public record Progress(String currentStepId, String checkpointStepId, boolean completed,
                           boolean skipped, List<Step> steps, List<String> committedStepIds) {}

    private final JdbcTemplate jdbc;
    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private volatile List<Step> steps = List.of();
    /** 内存兜底：playerId -> committed step ids */
    private final Map<Integer, ProgressMem> mem = new ConcurrentHashMap<>();

    public NewbieGuideService(JdbcTemplate jdbc, ResourceLoader resourceLoader) {
        this.jdbc = jdbc;
        this.resourceLoader = resourceLoader;
    }

    @PostConstruct
    public void load() {
        try {
            Resource res = resourceLoader.getResource("classpath:data/NewbieGuide.json");
            if (!res.exists()) {
                res = resourceLoader.getResource("file:data/NewbieGuide.json");
            }
            if (res.exists()) {
                Map<String, Object> root = objectMapper.readValue(res.getInputStream(), new TypeReference<>() {});
                List<Map<String, Object>> raw = (List<Map<String, Object>>) root.getOrDefault("steps", List.of());
                List<Step> parsed = new ArrayList<>();
                for (Map<String, Object> m : raw) {
                    parsed.add(new Step(
                            String.valueOf(m.get("id")),
                            String.valueOf(m.getOrDefault("title", "")),
                            String.valueOf(m.getOrDefault("hint", "")),
                            String.valueOf(m.getOrDefault("assistPrompt", ""))));
                }
                steps = List.copyOf(parsed);
            }
        } catch (Exception ignored) {
            steps = List.of(
                    new Step("login", "欢迎开拓者", "完成首次登录", "新手第一次登录后该做什么？"),
                    new Step("first_battle", "初战试炼", "完成一场战斗", "如何开始第一场战斗？"),
                    new Step("first_gacha", "星轨跃迁", "进行一次抽卡", "抽卡消耗什么？保底怎么算？")
            );
        }
        if (steps.isEmpty()) {
            steps = List.of(new Step("login", "欢迎开拓者", "完成首次登录", "新手指南"));
        }
    }

    public List<Step> steps() {
        return steps;
    }

    public Map<String, Object> progress(int playerId) {
        Progress p = progressDetail(playerId);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("currentStepId", p.currentStepId());
        body.put("checkpointStepId", p.checkpointStepId());
        body.put("completed", p.completed());
        body.put("skipped", p.skipped());
        body.put("committedStepIds", p.committedStepIds());
        body.put("steps", steps);
        return body;
    }

    public Progress progressDetail(int playerId) {
        String stepId = steps.get(0).id();
        String checkpoint = stepId;
        boolean completed = false;
        boolean skipped = false;
        List<String> committed = new ArrayList<>();
        ProgressMem m = mem.get(playerId);
        if (m != null) {
            stepId = m.currentStepId;
            checkpoint = m.checkpointStepId;
            completed = m.completed;
            skipped = m.skipped;
            committed = new ArrayList<>(m.committed);
        }
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    """
                    SELECT step_id, completed, COALESCE(checkpoint_step_id, step_id) AS checkpoint_step_id,
                           COALESCE(skipped, 0) AS skipped, COALESCE(committed_json, '[]') AS committed_json
                    FROM player_newbie_guide WHERE player_id = ?
                    """, playerId);
            if (!rows.isEmpty()) {
                Map<String, Object> row = rows.get(0);
                stepId = String.valueOf(row.get("step_id"));
                checkpoint = String.valueOf(row.get("checkpoint_step_id"));
                completed = ((Number) row.get("completed")).intValue() == 1;
                skipped = ((Number) row.get("skipped")).intValue() == 1;
                committed = parseCommitted(String.valueOf(row.get("committed_json")));
            }
        } catch (Exception ignored) {
            // 旧表无新列时降级查询
            try {
                List<Map<String, Object>> rows = jdbc.queryForList(
                        "SELECT step_id, completed FROM player_newbie_guide WHERE player_id = ?", playerId);
                if (!rows.isEmpty()) {
                    stepId = String.valueOf(rows.get(0).get("step_id"));
                    checkpoint = stepId;
                    completed = ((Number) rows.get(0).get("completed")).intValue() == 1;
                }
            } catch (Exception ignored2) {
                // 表缺失
            }
        }
        return new Progress(stepId, checkpoint, completed, skipped, steps, List.copyOf(committed));
    }

    /**
     * 推进引导；{@code commitCheckpoint=true} 时立即把该步写入检查点，供断线重连恢复。
     */
    public boolean advance(int playerId, String stepId) {
        return advance(playerId, stepId, true);
    }

    public boolean advance(int playerId, String stepId, boolean commitCheckpoint) {
        if (playerId <= 0 || stepId == null) {
            return false;
        }
        Progress cur = progressDetail(playerId);
        if (cur.skipped() || cur.completed()) {
            return false;
        }
        int idx = indexOf(stepId);
        if (idx < 0) {
            return false;
        }
        boolean last = idx >= steps.size() - 1;
        String nextId = last ? stepId : steps.get(idx + 1).id();
        int completed = last ? 1 : 0;
        List<String> committed = new ArrayList<>(cur.committedStepIds());
        String checkpoint = cur.checkpointStepId();
        if (commitCheckpoint) {
            if (!committed.contains(stepId)) {
                committed.add(stepId);
            }
            checkpoint = stepId;
        }
        persist(playerId, nextId, completed, checkpoint, false, committed);
        return true;
    }

    /** 创角/老玩家跳过全部强制教学。 */
    public boolean skipAll(int playerId) {
        if (playerId <= 0) {
            return false;
        }
        String last = steps.get(steps.size() - 1).id();
        List<String> all = steps.stream().map(Step::id).toList();
        persist(playerId, last, 1, last, true, all);
        return true;
    }

    public String currentAssistPrompt(int playerId) {
        Progress p = progressDetail(playerId);
        if (p.skipped() || p.completed()) {
            return "";
        }
        String stepId = p.checkpointStepId() == null || p.checkpointStepId().isBlank()
                ? p.currentStepId() : p.checkpointStepId();
        return steps.stream().filter(s -> s.id().equals(stepId)).map(Step::assistPrompt).findFirst().orElse("");
    }

    private void persist(int playerId, String stepId, int completed, String checkpoint,
                         boolean skipped, List<String> committed) {
        mem.put(playerId, new ProgressMem(stepId, checkpoint, completed == 1, skipped, List.copyOf(committed)));
        String json;
        try {
            json = objectMapper.writeValueAsString(committed);
        } catch (Exception e) {
            json = "[]";
        }
        String now = Instant.now().toString();
        try {
            int n = jdbc.update("""
                    UPDATE player_newbie_guide
                    SET step_id = ?, completed = ?, checkpoint_step_id = ?, skipped = ?,
                        committed_json = ?, updated_at = ?
                    WHERE player_id = ?
                    """, stepId, completed, checkpoint, skipped ? 1 : 0, json, now, playerId);
            if (n == 0) {
                jdbc.update("""
                        INSERT INTO player_newbie_guide
                        (player_id, step_id, completed, checkpoint_step_id, skipped, committed_json, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?)
                        """, playerId, stepId, completed, checkpoint, skipped ? 1 : 0, json, now);
            }
        } catch (Exception e) {
            try {
                int n = jdbc.update("""
                        UPDATE player_newbie_guide SET step_id = ?, completed = ?, updated_at = ? WHERE player_id = ?
                        """, stepId, completed, now, playerId);
                if (n == 0) {
                    jdbc.update("""
                            INSERT INTO player_newbie_guide (player_id, step_id, completed, updated_at)
                            VALUES (?, ?, ?, ?)
                            """, playerId, stepId, completed, now);
                }
            } catch (Exception ignored) {
                // 仅内存
            }
        }
    }

    private int indexOf(String stepId) {
        for (int i = 0; i < steps.size(); i++) {
            if (steps.get(i).id().equals(stepId)) {
                return i;
            }
        }
        return -1;
    }

    private List<String> parseCommitted(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private static final class ProgressMem {
        final String currentStepId;
        final String checkpointStepId;
        final boolean completed;
        final boolean skipped;
        final List<String> committed;

        ProgressMem(String currentStepId, String checkpointStepId, boolean completed,
                    boolean skipped, List<String> committed) {
            this.currentStepId = currentStepId;
            this.checkpointStepId = checkpointStepId;
            this.completed = completed;
            this.skipped = skipped;
            this.committed = committed;
        }
    }
}
