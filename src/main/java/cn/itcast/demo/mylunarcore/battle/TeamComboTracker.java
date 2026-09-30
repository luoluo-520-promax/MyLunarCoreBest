package cn.itcast.demo.mylunarcore.battle;

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
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 团队属性技能连携：记录最近 windowMs 内属性序列，匹配反应配置后广播。
 */
@Service
public class TeamComboTracker {

    private static final Logger log = LoggerFactory.getLogger(TeamComboTracker.class);

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Reaction(String comboKey, List<String> elements, String name,
                           double damageBonusPct, double defShredPct, double aoeRadius, String fxId) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Root(int schemaVersion, int windowMs, List<Reaction> reactions) {}

    public record SkillPulse(int playerId, String element, long atMs) {}

    public record ComboHit(String comboKey, String name, double damageBonusPct, String fxId,
                           List<SkillPulse> contributors) {}

    private final ObjectMapper objectMapper;
    private final Path dataDir;
    private final ObjectProvider<GameSessionManager> sessionProvider;
    private final Map<Long, Deque<SkillPulse>> recentByBattle = new ConcurrentHashMap<>();
    private volatile int windowMs = 2000;
    private volatile List<Reaction> reactions = List.of();

    public TeamComboTracker(ObjectMapper objectMapper,
                            @Value("${lunarcore.data-dir:data}") String dataDir,
                            ObjectProvider<GameSessionManager> sessionProvider) {
        this.objectMapper = objectMapper;
        this.dataDir = Path.of(dataDir);
        this.sessionProvider = sessionProvider;
    }

    @PostConstruct
    public void load() {
        reload();
    }

    public boolean reload() {
        Path file = dataDir.resolve("ElementReactionConfig.json");
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try {
            Root root = objectMapper.readValue(Files.readString(file), Root.class);
            windowMs = root == null || root.windowMs() <= 0 ? 2000 : root.windowMs();
            reactions = root == null || root.reactions() == null ? List.of() : List.copyOf(root.reactions());
            log.info("ElementReactionConfig loaded reactions={}", reactions.size());
            return true;
        } catch (Exception e) {
            log.warn("load ElementReactionConfig failed: {}", e.toString());
            return false;
        }
    }

    public ComboHit recordSkill(long battleId, int playerId, String element, Iterable<Integer> notifyPlayerIds) {
        if (battleId <= 0 || element == null || element.isBlank()) {
            return null;
        }
        String el = element.toUpperCase(Locale.ROOT);
        long now = System.currentTimeMillis();
        Deque<SkillPulse> q = recentByBattle.computeIfAbsent(battleId, k -> new ArrayDeque<>());
        synchronized (q) {
            q.addLast(new SkillPulse(playerId, el, now));
            while (!q.isEmpty() && now - q.peekFirst().atMs() > windowMs) {
                q.removeFirst();
            }
            ComboHit hit = match(q);
            if (hit != null) {
                broadcast(hit, notifyPlayerIds);
                q.clear();
            }
            return hit;
        }
    }

    private ComboHit match(Deque<SkillPulse> q) {
        if (q.size() < 2) {
            return null;
        }
        List<SkillPulse> list = new ArrayList<>(q);
        for (Reaction r : reactions) {
            if (r.elements() == null || r.elements().size() < 2) {
                continue;
            }
            List<String> need = r.elements().stream()
                    .map(s -> s == null ? "" : s.toUpperCase(Locale.ROOT))
                    .toList();
            List<SkillPulse> found = new ArrayList<>();
            for (String n : need) {
                SkillPulse matchPulse = null;
                for (SkillPulse p : list) {
                    if (p.element().equals(n) && found.stream().noneMatch(f -> f == p)) {
                        matchPulse = p;
                        break;
                    }
                }
                if (matchPulse == null) {
                    found.clear();
                    break;
                }
                found.add(matchPulse);
            }
            if (found.size() == need.size()) {
                return new ComboHit(r.comboKey(), r.name(), r.damageBonusPct(),
                        r.fxId() == null ? "" : r.fxId(), List.copyOf(found));
            }
        }
        return null;
    }

    private void broadcast(ComboHit hit, Iterable<Integer> playerIds) {
        if (playerIds == null) {
            return;
        }
        GameSessionManager sessions = sessionProvider.getIfAvailable();
        if (sessions == null) {
            return;
        }
        try {
            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("comboKey", hit.comboKey());
            body.put("name", hit.name());
            body.put("damageBonusPct", hit.damageBonusPct());
            body.put("fxId", hit.fxId());
            byte[] payload = objectMapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
            for (Integer pid : playerIds) {
                if (pid == null) {
                    continue;
                }
                GameSession s = sessions.getOrNull(pid);
                if (s != null) {
                    s.send(new GamePacket(CmdIds.BATTLE_COMBO_SC_NOTIFY, payload));
                }
            }
        } catch (Exception e) {
            log.debug("combo notify failed: {}", e.getMessage());
        }
    }
}
