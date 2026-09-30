package cn.itcast.demo.mylunarcore.minigame;

import cn.itcast.demo.mylunarcore.battle.BattleReplayShareService;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MiniGame 框架：按配置创建跑酷/塔防/钓鱼实例，维护 Redis ZSET 排行榜。
 */
@Service
public class MiniGameFramework {

    private static final Logger log = LoggerFactory.getLogger(MiniGameFramework.class);

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GameDef(String miniGameId, String type, String displayName, String tutorialAnimUrl,
                          String script, List<Map<String, Object>> checkpoints,
                          int turretSlots, int waves, double baseCatchRate) {}

    public record StartResult(boolean ok, int retcode, String miniGameId, Map<String, Object> payload) {}

    private final ObjectMapper objectMapper;
    private final Path dataDir;
    private final ObjectProvider<GameSessionManager> sessionProvider;
    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final ObjectProvider<BattleReplayShareService> shareProvider;
    private final Map<String, GameDef> defs = new ConcurrentHashMap<>();
    private final Map<String, MiniGameInstance> templates = new ConcurrentHashMap<>();
    private final Map<String, Long> personalBest = new ConcurrentHashMap<>();

    public MiniGameFramework(ObjectMapper objectMapper,
                             @Value("${lunarcore.data-dir:data}") String dataDir,
                             ObjectProvider<GameSessionManager> sessionProvider,
                             ObjectProvider<StringRedisTemplate> redisProvider,
                             ObjectProvider<BattleReplayShareService> shareProvider) {
        this.objectMapper = objectMapper;
        this.dataDir = Path.of(dataDir);
        this.sessionProvider = sessionProvider;
        this.redisProvider = redisProvider;
        this.shareProvider = shareProvider;
    }

    @PostConstruct
    public void load() {
        reload();
    }

    public boolean reload() {
        Path file = dataDir.resolve("MiniGamePoolConfigs.json");
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try {
            JsonNode root = objectMapper.readTree(Files.readString(file));
            defs.clear();
            templates.clear();
            JsonNode games = root.path("games");
            if (games.isArray()) {
                for (JsonNode n : games) {
                    GameDef def = objectMapper.treeToValue(n, GameDef.class);
                    if (def == null || def.miniGameId() == null) {
                        continue;
                    }
                    defs.put(def.miniGameId(), def);
                    templates.put(def.miniGameId(), createInstance(def));
                }
            }
            log.info("MiniGamePoolConfigs loaded count={}", defs.size());
            return true;
        } catch (Exception e) {
            log.warn("load MiniGamePoolConfigs failed: {}", e.toString());
            return false;
        }
    }

    private MiniGameInstance createInstance(GameDef def) {
        String type = def.type() == null ? "" : def.type().toUpperCase();
        return switch (type) {
            case "RACING" -> new RacingActivity(def.miniGameId(), def.checkpoints());
            case "TOWER_DEFENSE" -> new TowerDefenseActivity(def.miniGameId(),
                    def.turretSlots() <= 0 ? 4 : def.turretSlots(),
                    def.waves() <= 0 ? 5 : def.waves());
            case "FISHING" -> new FishingActivity(def.miniGameId(),
                    def.baseCatchRate() <= 0 ? 0.45 : def.baseCatchRate());
            default -> new RacingActivity(def.miniGameId(), def.checkpoints());
        };
    }

    public List<GameDef> listGames() {
        return List.copyOf(defs.values());
    }

    public GameDef find(String miniGameId) {
        return defs.get(miniGameId);
    }

    public StartResult start(int playerId, String miniGameId) {
        MiniGameInstance inst = templates.get(miniGameId);
        GameDef def = defs.get(miniGameId);
        if (inst == null || def == null) {
            return new StartResult(false, 1, miniGameId, Map.of());
        }
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("displayName", def.displayName());
        if (inst instanceof RacingActivity racing) {
            ctx.put("checkpoints", racing.checkpoints());
        }
        inst.onStart(playerId, ctx);
        return new StartResult(true, 0, miniGameId, ctx);
    }

    public Map<String, Object> tick(int playerId, String miniGameId, Map<String, Object> payload) {
        MiniGameInstance inst = templates.get(miniGameId);
        if (inst == null) {
            return Map.of("retcode", 1);
        }
        boolean ended = inst.onTick(playerId, payload);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("retcode", 0);
        out.put("ended", ended);
        if (ended) {
            Map<String, Object> reward = inst.getReward(playerId);
            out.putAll(reward);
            submitScore(playerId, miniGameId, ((Number) reward.getOrDefault("score", 0)).longValue());
        }
        return out;
    }

    public void submitScore(int playerId, String miniGameId, long score) {
        if (playerId <= 0 || miniGameId == null || score <= 0) {
            return;
        }
        String pbKey = playerId + ":" + miniGameId;
        Long prev = personalBest.get(pbKey);
        boolean personalHigh = prev == null || score > prev;
        if (personalHigh) {
            personalBest.put(pbKey, score);
        }
        boolean top10 = false;
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis != null) {
            try {
                String zkey = "minigame:score:" + miniGameId;
                redis.opsForZSet().add(zkey, String.valueOf(playerId), score);
                Long rank = redis.opsForZSet().reverseRank(zkey, String.valueOf(playerId));
                top10 = rank != null && rank < 10;
            } catch (Exception e) {
                log.debug("redis score skipped: {}", e.getMessage());
            }
        } else if (personalHigh) {
            top10 = true; // 无 Redis 时以个人最佳视作可分享
        }
        if (personalHigh || top10) {
            pushHighScore(playerId, miniGameId, score, personalHigh, top10);
        }
    }

    public List<Map<String, Object>> topScores(String miniGameId, int limit) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return List.of();
        }
        try {
            Set<String> members = redis.opsForZSet()
                    .reverseRange("minigame:score:" + miniGameId, 0, Math.max(0, limit - 1));
            if (members == null) {
                return List.of();
            }
            List<Map<String, Object>> out = new ArrayList<>();
            int rank = 1;
            for (String m : members) {
                Double s = redis.opsForZSet().score("minigame:score:" + miniGameId, m);
                out.add(Map.of("rank", rank++, "playerId", Integer.parseInt(m),
                        "score", s == null ? 0 : s.longValue()));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    private void pushHighScore(int playerId, String miniGameId, long score,
                               boolean personalHigh, boolean top10) {
        GameSessionManager sessions = sessionProvider.getIfAvailable();
        if (sessions == null) {
            return;
        }
        GameSession session = sessions.getOrNull(playerId);
        if (session == null) {
            return;
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("miniGameId", miniGameId);
            body.put("score", score);
            body.put("personalHigh", personalHigh);
            body.put("top10", top10);
            body.put("shareHint", "BattleReplayShareService");
            // 可选：触发战报分享服务生成链接（不强制）
            BattleReplayShareService share = shareProvider.getIfAvailable();
            if (share != null) {
                body.put("shareAvailable", true);
            }
            byte[] payload = objectMapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
            session.send(new GamePacket(CmdIds.ACTIVITY_HIGH_SCORE_SC_NOTIFY, payload));
        } catch (Exception e) {
            log.debug("high score notify failed: {}", e.getMessage());
        }
    }
}
