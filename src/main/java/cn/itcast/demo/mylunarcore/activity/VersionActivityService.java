package cn.itcast.demo.mylunarcore.activity;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 版本大活动容器：剧情关 + 挑战关 + 商店兑换 + 签到 的树形节点，共享 ActivityToken。
 */
@Service
public class VersionActivityService {

    private static final Logger log = LoggerFactory.getLogger(VersionActivityService.class);

    public record ActivityView(int versionActivityId, String title, int tokenItemId, int tokenBalance,
                               List<String> completedNodes, List<NodeCfg> availableNodes) {}

    public record OpResult(boolean ok, int retcode, int tokenBalance) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NodeCfg(String nodeId, String type, String title, List<String> children,
                          Integer tokenReward, Integer tokenCost) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ActivityCfg(int versionActivityId, String title, int tokenItemId,
                              String openAt, String closeAt, List<NodeCfg> nodes, List<String> rootNodeIds) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RootCfg(List<ActivityCfg> activities) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Path dataDir;
    private final Map<Integer, ActivityCfg> activities = new ConcurrentHashMap<>();

    public VersionActivityService(JdbcTemplate jdbc,
                                  ObjectMapper objectMapper,
                                  @Value("${lunarcore.data-dir:data}") String dataDir) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.dataDir = Path.of(dataDir);
    }

    @PostConstruct
    public void loadConfig() {
        Path file = dataDir.resolve("VersionActivityConfigs.json");
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            RootCfg root = objectMapper.readValue(Files.readString(file), RootCfg.class);
            activities.clear();
            if (root != null && root.activities() != null) {
                for (ActivityCfg a : root.activities()) {
                    activities.put(a.versionActivityId(), a);
                }
            }
        } catch (Exception e) {
            log.warn("load VersionActivityConfigs failed: {}", e.toString());
        }
    }

    public List<ActivityView> listOpen(int playerId) {
        Instant now = Instant.now();
        List<ActivityView> out = new ArrayList<>();
        for (ActivityCfg cfg : activities.values()) {
            if (!isOpen(cfg, now)) {
                continue;
            }
            ensureRow(playerId, cfg.versionActivityId());
            Progress prog = loadProgress(playerId, cfg.versionActivityId());
            out.add(new ActivityView(cfg.versionActivityId(), cfg.title(), cfg.tokenItemId(),
                    prog.tokenBalance, List.copyOf(prog.completed), availableNodes(cfg, prog.completed)));
        }
        return out;
    }

    /** 完成节点（剧情/挑战/签到），发放代币。 */
    @Transactional
    public OpResult completeNode(int playerId, int versionActivityId, String nodeId) {
        ActivityCfg cfg = activities.get(versionActivityId);
        if (cfg == null || playerId <= 0 || nodeId == null) {
            return new OpResult(false, 2, 0);
        }
        if (!isOpen(cfg, Instant.now())) {
            return new OpResult(false, 3, 0);
        }
        NodeCfg node = findNode(cfg, nodeId);
        if (node == null) {
            return new OpResult(false, 4, 0);
        }
        ensureRow(playerId, versionActivityId);
        Progress prog = loadProgress(playerId, versionActivityId);
        if (prog.completed.contains(nodeId)) {
            return new OpResult(false, 5, prog.tokenBalance);
        }
        if (!isNodeAvailable(cfg, prog.completed, nodeId)) {
            return new OpResult(false, 6, prog.tokenBalance);
        }
        if ("exchange_shop".equals(node.type())) {
            return new OpResult(false, 7, prog.tokenBalance); // 商店走 exchange
        }
        int reward = node.tokenReward() == null ? 0 : Math.max(0, node.tokenReward());
        Set<String> next = new HashSet<>(prog.completed);
        next.add(nodeId);
        int balance = prog.tokenBalance + reward;
        persist(playerId, versionActivityId, balance, next);
        return new OpResult(true, 0, balance);
    }

    /** 活动商店兑换：消耗共享代币。 */
    @Transactional
    public OpResult exchange(int playerId, int versionActivityId, String nodeId) {
        ActivityCfg cfg = activities.get(versionActivityId);
        if (cfg == null) {
            return new OpResult(false, 2, 0);
        }
        NodeCfg node = findNode(cfg, nodeId);
        if (node == null || !"exchange_shop".equals(node.type())) {
            return new OpResult(false, 4, 0);
        }
        ensureRow(playerId, versionActivityId);
        Progress prog = loadProgress(playerId, versionActivityId);
        if (!isNodeAvailable(cfg, prog.completed, nodeId) && !prog.completed.contains(nodeId)) {
            // 商店节点可在父节点完成后反复兑换
            if (!parentCompleted(cfg, prog.completed, nodeId)) {
                return new OpResult(false, 6, prog.tokenBalance);
            }
        }
        int cost = node.tokenCost() == null ? 0 : Math.max(0, node.tokenCost());
        if (prog.tokenBalance < cost) {
            return new OpResult(false, 8, prog.tokenBalance);
        }
        Set<String> next = new HashSet<>(prog.completed);
        next.add(nodeId);
        int balance = prog.tokenBalance - cost;
        persist(playerId, versionActivityId, balance, next);
        return new OpResult(true, 0, balance);
    }

    private boolean parentCompleted(ActivityCfg cfg, Set<String> completed, String nodeId) {
        for (NodeCfg n : cfg.nodes()) {
            if (n.children() != null && n.children().contains(nodeId)) {
                return completed.contains(n.nodeId());
            }
        }
        return cfg.rootNodeIds() != null && cfg.rootNodeIds().contains(nodeId);
    }

    private List<NodeCfg> availableNodes(ActivityCfg cfg, Set<String> completed) {
        List<NodeCfg> out = new ArrayList<>();
        Map<String, NodeCfg> byId = new HashMap<>();
        for (NodeCfg n : cfg.nodes()) {
            byId.put(n.nodeId(), n);
        }
        for (NodeCfg n : cfg.nodes()) {
            if (isNodeAvailable(cfg, completed, n.nodeId())) {
                out.add(n);
            }
        }
        return out;
    }

    private boolean isNodeAvailable(ActivityCfg cfg, Set<String> completed, String nodeId) {
        if (completed.contains(nodeId)) {
            return false;
        }
        if (cfg.rootNodeIds() != null && cfg.rootNodeIds().contains(nodeId)) {
            return true;
        }
        for (NodeCfg n : cfg.nodes()) {
            if (n.children() != null && n.children().contains(nodeId) && completed.contains(n.nodeId())) {
                return true;
            }
        }
        return false;
    }

    private static NodeCfg findNode(ActivityCfg cfg, String nodeId) {
        for (NodeCfg n : cfg.nodes()) {
            if (nodeId.equals(n.nodeId())) {
                return n;
            }
        }
        return null;
    }

    private static boolean isOpen(ActivityCfg cfg, Instant now) {
        try {
            Instant open = Instant.parse(cfg.openAt());
            Instant close = Instant.parse(cfg.closeAt());
            return !now.isBefore(open) && !now.isAfter(close);
        } catch (Exception e) {
            return true;
        }
    }

    private void ensureRow(int playerId, int activityId) {
        try {
            jdbc.update("""
                    INSERT IGNORE INTO version_activity_progress
                    (player_id, version_activity_id, token_balance, completed_nodes_json)
                    VALUES (?, ?, 0, '[]')
                    """, playerId, activityId);
        } catch (Exception ignored) {
        }
    }

    private Progress loadProgress(int playerId, int activityId) {
        try {
            List<Progress> list = jdbc.query("""
                    SELECT token_balance, completed_nodes_json FROM version_activity_progress
                    WHERE player_id = ? AND version_activity_id = ?
                    """, (rs, i) -> new Progress(rs.getInt(1), parseSet(rs.getString(2))), playerId, activityId);
            return list.isEmpty() ? new Progress(0, Set.of()) : list.get(0);
        } catch (Exception e) {
            return new Progress(0, Set.of());
        }
    }

    private void persist(int playerId, int activityId, int balance, Set<String> completed) {
        try {
            jdbc.update("""
                    UPDATE version_activity_progress
                    SET token_balance = ?, completed_nodes_json = ?, updated_at = NOW(3)
                    WHERE player_id = ? AND version_activity_id = ?
                    """, balance, objectMapper.writeValueAsString(completed), playerId, activityId);
        } catch (Exception e) {
            log.warn("version activity persist failed: {}", e.toString());
        }
    }

    private Set<String> parseSet(String json) {
        if (json == null || json.isBlank()) {
            return new HashSet<>();
        }
        try {
            return new HashSet<>(objectMapper.readValue(json, new TypeReference<List<String>>() {}));
        } catch (Exception e) {
            return new HashSet<>();
        }
    }

    private record Progress(int tokenBalance, Set<String> completed) {}
}
