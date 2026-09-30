package cn.itcast.demo.mylunarcore.activity;

import cn.itcast.demo.mylunarcore.common.ActivityConfigService;
import cn.itcast.demo.mylunarcore.model.ActivityConfig;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.protocol.QolSocialSystemProto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 活动即将结束提醒：登录推送 24h 内结束且有未领奖提示；定时扫描结束前 1h 紧急推送。
 */
@Service
public class ActivityReminderService {

    private static final Logger log = LoggerFactory.getLogger(ActivityReminderService.class);
    private static final long HOUR_MS = 3_600_000L;
    private static final long DAY_MS = 24 * HOUR_MS;

    public record EndingActivity(int activityId, String title, long endEpochSec, boolean hasUnclaimed) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Path dataDir;
    private final ObjectProvider<ActivityConfigService> activityConfigProvider;
    private final ObjectProvider<GameSessionManager> sessionProvider;
    /** playerId:activityId:urgentFlag → last push ms */
    private final ConcurrentHashMap<String, Long> dedup = new ConcurrentHashMap<>();

    public ActivityReminderService(JdbcTemplate jdbc,
                                   ObjectMapper objectMapper,
                                   @Value("${lunarcore.data-dir:data}") String dataDir,
                                   ObjectProvider<ActivityConfigService> activityConfigProvider,
                                   ObjectProvider<GameSessionManager> sessionProvider) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.dataDir = Path.of(dataDir);
        this.activityConfigProvider = activityConfigProvider;
        this.sessionProvider = sessionProvider;
    }

    public void pushOnLogin(int playerId) {
        if (playerId <= 0) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        for (EndingActivity a : listEndingSoon(playerId, nowMs, DAY_MS)) {
            pushOne(playerId, a, false, nowMs);
        }
        pushNewMiniGames(playerId);
    }

    /**
     * 推送当前版本开放的新小玩法（教学动画 URL），引导玩家尝鲜。
     */
    public void pushNewMiniGames(int playerId) {
        GameSessionManager sessions = sessionProvider.getIfAvailable();
        if (sessions == null || playerId <= 0) {
            return;
        }
        GameSession session = sessions.getOrNull(playerId);
        if (session == null) {
            return;
        }
        Path pool = dataDir.resolve("MiniGamePoolConfigs.json");
        if (!Files.isRegularFile(pool)) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(Files.readString(pool));
            JsonNode games = root.path("games");
            if (!games.isArray()) {
                return;
            }
            for (JsonNode g : games) {
                String id = g.path("miniGameId").asText("");
                if (id.isBlank()) {
                    continue;
                }
                String body = objectMapper.writeValueAsString(Map.of(
                        "miniGameId", id,
                        "displayName", g.path("displayName").asText(id),
                        "tutorialAnimUrl", g.path("tutorialAnimUrl").asText("")));
                session.send(new GamePacket(CmdIds.NEW_MINI_GAME_SC_NOTIFY,
                        body.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }
        } catch (Exception e) {
            log.debug("pushNewMiniGames skipped: {}", e.getMessage());
        }
    }

    /** 每分钟扫描在线玩家，对结束前 1h 活动发紧急提醒。 */
    @Scheduled(fixedDelay = 60_000L)
    public void scheduledUrgentPush() {
        GameSessionManager sessions = sessionProvider.getIfAvailable();
        if (sessions == null) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        for (GameSession session : sessions.snapshotSessions()) {
            if (session == null) {
                continue;
            }
            int playerId = (int) session.getUid();
            if (playerId <= 0) {
                continue;
            }
            for (EndingActivity a : listEndingSoon(playerId, nowMs, HOUR_MS)) {
                pushOne(playerId, a, true, nowMs);
            }
        }
    }

    private void pushOne(int playerId, EndingActivity a, boolean urgent, long nowMs) {
        String key = playerId + ":" + a.activityId() + ":" + (urgent ? "1" : "0");
        Long prev = dedup.get(key);
        long ttl = urgent ? HOUR_MS : DAY_MS;
        if (prev != null && nowMs - prev < ttl) {
            return;
        }
        if (!markDedupDb(playerId, a.activityId(), urgent)) {
            return;
        }
        dedup.put(key, nowMs);
        GameSessionManager sessions = sessionProvider.getIfAvailable();
        if (sessions == null) {
            return;
        }
        GameSession session = sessions.getOrNull(playerId);
        if (session == null) {
            return;
        }
        long remainMs = Math.max(0L, a.endEpochSec() * 1000L - nowMs);
        QolSocialSystemProto.ActivityEndingSoonScNotify notify =
                QolSocialSystemProto.ActivityEndingSoonScNotify.newBuilder()
                        .setActivityId(a.activityId())
                        .setTitle(a.title() == null ? "" : a.title())
                        .setRemainMs(remainMs)
                        .setUrgent(urgent)
                        .setHasUnclaimed(a.hasUnclaimed())
                        .build();
        session.send(new GamePacket(CmdIds.ACTIVITY_ENDING_SOON_SC_NOTIFY, notify.toByteArray()));
    }

    private List<EndingActivity> listEndingSoon(int playerId, long nowMs, long windowMs) {
        List<EndingActivity> out = new ArrayList<>();
        long nowSec = nowMs / 1000L;
        long windowSec = windowMs / 1000L;
        ActivityConfigService cfgSvc = activityConfigProvider.getIfAvailable();
        if (cfgSvc != null) {
            for (Map.Entry<Integer, ActivityConfig> e : cfgSvc.snapshot().entrySet()) {
                ActivityConfig c = e.getValue();
                if (c == null) {
                    continue;
                }
                long end = resolveEndSec(c);
                if (end <= nowSec || end - nowSec > windowSec) {
                    continue;
                }
                boolean unclaimed = guessUnclaimed(playerId, c.getActivityId());
                if (!unclaimed) {
                    continue;
                }
                out.add(new EndingActivity(c.getActivityId(), c.getName(), end, true));
            }
            if (!out.isEmpty()) {
                return out;
            }
        }
        out.addAll(loadFromJsonFiles(playerId, nowSec, windowSec));
        return out;
    }

    private List<EndingActivity> loadFromJsonFiles(int playerId, long nowSec, long windowSec) {
        List<EndingActivity> out = new ArrayList<>();
        for (String file : List.of("ActivityConfigs.json", "VersionActivityConfigs.json")) {
            Path path = dataDir.resolve(file);
            if (!Files.isRegularFile(path)) {
                continue;
            }
            try {
                JsonNode root = objectMapper.readTree(Files.readString(path));
                JsonNode arr = root.isArray() ? root : root.path("activities");
                if (!arr.isArray()) {
                    continue;
                }
                for (JsonNode n : arr) {
                    int id = n.path("activityId").asInt(n.path("versionActivityId").asInt(0));
                    if (id <= 0) {
                        continue;
                    }
                    long end = n.path("endTime").asLong(0);
                    if (end <= 0 && n.has("closeAt")) {
                        try {
                            end = Instant.parse(n.path("closeAt").asText()).getEpochSecond();
                        } catch (Exception ignored) {
                        }
                    }
                    if (end <= nowSec || end - nowSec > windowSec) {
                        continue;
                    }
                    String title = n.path("name").asText(n.path("title").asText("活动"));
                    if (guessUnclaimed(playerId, id)) {
                        out.add(new EndingActivity(id, title, end, true));
                    }
                }
            } catch (Exception e) {
                log.debug("load {} for reminder failed: {}", file, e.getMessage());
            }
        }
        return out;
    }

    private static long resolveEndSec(ActivityConfig c) {
        if (c.getEffectEnd() > 0) {
            return c.getEffectEnd();
        }
        return c.getEndTime();
    }

    /**
     * 轻量未领奖判断：若玩家曾参与活动模板事件且无 claim/reward 记录，或无流水则默认 true（提醒侧偏积极）。
     */
    private boolean guessUnclaimed(int playerId, int activityId) {
        try {
            Integer played = jdbc.queryForObject("""
                    SELECT COUNT(1) FROM activity_template_event
                    WHERE player_id=? AND activity_id=?
                    """, Integer.class, playerId, activityId);
            if (played == null || played == 0) {
                return true;
            }
            Integer claimed = jdbc.queryForObject("""
                    SELECT COUNT(1) FROM activity_template_event
                    WHERE player_id=? AND activity_id=?
                      AND (action LIKE 'claim%' OR action LIKE '%reward%' OR action='compensate')
                    """, Integer.class, playerId, activityId);
            return claimed == null || claimed == 0;
        } catch (Exception e) {
            return true;
        }
    }

    /** DB 去重；表缺失时回退内存 dedup（已在调用前检查）。 */
    private boolean markDedupDb(int playerId, int activityId, boolean urgent) {
        String kind = urgent ? "urgent" : "login24h";
        try {
            int n = jdbc.update("""
                    INSERT IGNORE INTO player_activity_reminder_dedup
                    (player_id, activity_id, kind, pushed_at)
                    VALUES (?, ?, ?, ?)
                    """, playerId, activityId, kind, Instant.now().toString());
            return n > 0;
        } catch (Exception e) {
            // 表不存在：仅依赖内存 dedup
            return true;
        }
    }
}
