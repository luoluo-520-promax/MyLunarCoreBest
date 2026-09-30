package cn.itcast.demo.mylunarcore.exploration;

import cn.itcast.demo.mylunarcore.common.ActivityScheduleService;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.scene.WorldTimeService;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 动态探索事件（限时裂隙）：结合 WorldTime 与活动排期刷新。
 */
@Service
public class ExplorationEventService {

    private static final Logger log = LoggerFactory.getLogger(ExplorationEventService.class);

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Vec3(float x, float y, float z) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EventDef(String eventId, int planeId, int floorId, Vec3 pos,
                           List<String> worldTimePeriods, String activityWindowHint,
                           String miniChallengeId, int waves, int maxTurns,
                           int rewardItemId, int rewardCount, String rewardHint) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Root(int schemaVersion, List<EventDef> events) {}

    public record ActiveRift(String instanceId, String eventId, int planeId, int floorId,
                             Vec3 pos, long spawnedAtMs) {}

    public record ChallengeResult(boolean ok, int retcode, int rewardItemId, int rewardCount,
                                  String rewardHint) {}

    private final ObjectMapper objectMapper;
    private final Path dataDir;
    private final ObjectProvider<WorldTimeService> worldTimeProvider;
    private final ObjectProvider<ActivityScheduleService> scheduleProvider;
    private final ObjectProvider<GameSessionManager> sessionProvider;
    private final Map<String, EventDef> defs = new ConcurrentHashMap<>();
    private final Map<String, ActiveRift> activeByEvent = new ConcurrentHashMap<>();
    private final Map<String, Integer> challengeProgress = new ConcurrentHashMap<>();

    public ExplorationEventService(ObjectMapper objectMapper,
                                   @Value("${lunarcore.data-dir:data}") String dataDir,
                                   ObjectProvider<WorldTimeService> worldTimeProvider,
                                   ObjectProvider<ActivityScheduleService> scheduleProvider,
                                   ObjectProvider<GameSessionManager> sessionProvider) {
        this.objectMapper = objectMapper;
        this.dataDir = Path.of(dataDir);
        this.worldTimeProvider = worldTimeProvider;
        this.scheduleProvider = scheduleProvider;
        this.sessionProvider = sessionProvider;
    }

    @PostConstruct
    public void load() {
        reload();
    }

    public boolean reload() {
        Path file = dataDir.resolve("ExplorationEvents.json");
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try {
            Root root = objectMapper.readValue(Files.readString(file), Root.class);
            defs.clear();
            if (root != null && root.events() != null) {
                for (EventDef e : root.events()) {
                    if (e != null && e.eventId() != null) {
                        defs.put(e.eventId(), e);
                    }
                }
            }
            log.info("ExplorationEvents loaded count={}", defs.size());
            return true;
        } catch (Exception e) {
            log.warn("load ExplorationEvents failed: {}", e.toString());
            return false;
        }
    }

    /** 刷新当前时段应出现的裂隙实体。 */
    public List<ActiveRift> refreshActiveRifts() {
        String period = currentPeriod();
        List<ActiveRift> out = new ArrayList<>();
        for (EventDef def : defs.values()) {
            if (!periodAllowed(def, period)) {
                activeByEvent.remove(def.eventId());
                continue;
            }
            ActiveRift rift = activeByEvent.computeIfAbsent(def.eventId(), id ->
                    new ActiveRift("rift-" + UUID.randomUUID().toString().substring(0, 8),
                            def.eventId(), def.planeId(), def.floorId(), def.pos(),
                            System.currentTimeMillis()));
            out.add(rift);
        }
        return out;
    }

    public ChallengeResult startChallenge(int playerId, String eventId) {
        EventDef def = defs.get(eventId);
        if (def == null) {
            return new ChallengeResult(false, 1, 0, 0, "");
        }
        if (activeByEvent.get(eventId) == null) {
            refreshActiveRifts();
            if (activeByEvent.get(eventId) == null) {
                return new ChallengeResult(false, 2, 0, 0, "");
            }
        }
        challengeProgress.put(playerId + ":" + eventId, 0);
        notifyPlayer(playerId, Map.of(
                "eventId", eventId,
                "miniChallengeId", def.miniChallengeId() == null ? "" : def.miniChallengeId(),
                "waves", def.waves(),
                "maxTurns", def.maxTurns(),
                "phase", "START"));
        return new ChallengeResult(true, 0, 0, 0, "");
    }

    public ChallengeResult onWaveCleared(int playerId, String eventId) {
        EventDef def = defs.get(eventId);
        if (def == null) {
            return new ChallengeResult(false, 1, 0, 0, "");
        }
        String key = playerId + ":" + eventId;
        int waves = challengeProgress.merge(key, 1, Integer::sum);
        if (waves < Math.max(1, def.waves())) {
            return new ChallengeResult(true, 0, 0, 0, "");
        }
        challengeProgress.remove(key);
        notifyPlayer(playerId, Map.of(
                "eventId", eventId,
                "phase", "COMPLETE",
                "rewardItemId", def.rewardItemId(),
                "rewardCount", def.rewardCount(),
                "rewardHint", def.rewardHint() == null ? "" : def.rewardHint()));
        return new ChallengeResult(true, 0, def.rewardItemId(), def.rewardCount(),
                def.rewardHint() == null ? "" : def.rewardHint());
    }

    private String currentPeriod() {
        WorldTimeService wt = worldTimeProvider.getIfAvailable();
        if (wt == null) {
            return "DUSK";
        }
        try {
            WorldTimeService.Period period = wt.currentPeriod();
            return period == null ? "DUSK" : period.name();
        } catch (Exception e) {
            return "DUSK";
        }
    }

    private boolean periodAllowed(EventDef def, String period) {
        if (def.worldTimePeriods() == null || def.worldTimePeriods().isEmpty()) {
            return true;
        }
        for (String p : def.worldTimePeriods()) {
            if (p != null && p.equalsIgnoreCase(period)) {
                return true;
            }
        }
        return false;
    }

    private void notifyPlayer(int playerId, Map<String, Object> body) {
        GameSessionManager sessions = sessionProvider.getIfAvailable();
        if (sessions == null) {
            return;
        }
        GameSession session = sessions.getOrNull(playerId);
        if (session == null) {
            return;
        }
        try {
            byte[] payload = objectMapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
            session.send(new GamePacket(CmdIds.EXPLORATION_EVENT_SC_NOTIFY, payload));
        } catch (Exception e) {
            log.debug("exploration event notify failed: {}", e.getMessage());
        }
    }
}
