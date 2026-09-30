package cn.itcast.demo.mylunarcore.activity;

import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.protocol.QolSocialSystemProto;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 回流检测：登录时根据离线天数激活 RETURN_PLAYER 活动实例并推送通知。
 */
@Service
public class ReturnCheckService {

    private static final Logger log = LoggerFactory.getLogger(ReturnCheckService.class);

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReturnCfg(int activityId, int offlineDaysThreshold, int durationDays,
                            String title, String bonusDesc, List<String> questChain) {
        static ReturnCfg defaults() {
            return new ReturnCfg(90001, 14, 7, "回归开拓者礼遇", "双倍掉落+专属任务链",
                    List.of("login_day1", "claim_mail", "clear_abyss"));
        }
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Path dataDir;
    private final ObjectProvider<GameSessionManager> sessionProvider;
    private final ObjectProvider<ActivityFlowDslService> flowProvider;
    private volatile ReturnCfg config = ReturnCfg.defaults();

    public ReturnCheckService(JdbcTemplate jdbc,
                              ObjectMapper objectMapper,
                              @Value("${lunarcore.data-dir:data}") String dataDir,
                              ObjectProvider<GameSessionManager> sessionProvider,
                              ObjectProvider<ActivityFlowDslService> flowProvider) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.dataDir = Path.of(dataDir);
        this.sessionProvider = sessionProvider;
        this.flowProvider = flowProvider;
    }

    @PostConstruct
    public void loadConfig() {
        Path file = dataDir.resolve("ReturnPlayerActivityConfig.json");
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            ReturnCfg cfg = objectMapper.readValue(Files.readString(file), ReturnCfg.class);
            if (cfg != null && cfg.activityId() > 0) {
                config = cfg;
            }
        } catch (Exception e) {
            log.warn("load ReturnPlayerActivityConfig failed: {}", e.toString());
        }
    }

    /**
     * @param lastLogoutAtMs 上次登出时间戳；未知时传 0 跳过
     */
    public boolean checkAndActivate(int playerId, long lastLogoutAtMs) {
        if (playerId <= 0 || lastLogoutAtMs <= 0) {
            return false;
        }
        long offlineDays = ChronoUnit.DAYS.between(
                Instant.ofEpochMilli(lastLogoutAtMs), Instant.now());
        if (offlineDays < config.offlineDaysThreshold()) {
            return false;
        }
        if (hasActiveReturn(playerId)) {
            return false;
        }
        Instant expire = Instant.now().plus(config.durationDays(), ChronoUnit.DAYS);
        try {
            jdbc.update("""
                    INSERT INTO player_return_activity
                    (player_id, activity_id, offline_days, expire_at, active, created_at)
                    VALUES (?, ?, ?, ?, 1, ?)
                    """, playerId, config.activityId(), (int) offlineDays,
                    java.sql.Timestamp.from(expire), Instant.now().toString());
        } catch (Exception e) {
            log.debug("return activity persist skipped: {}", e.getMessage());
            return false;
        }
        ActivityFlowDslService flow = flowProvider.getIfAvailable();
        if (flow != null) {
            try {
                flow.start(playerId, config.activityId(), "return_player");
            } catch (Exception e) {
                log.debug("return flow start skipped: {}", e.getMessage());
            }
        }
        pushNotify(playerId, (int) offlineDays, expire.toEpochMilli());
        return true;
    }

    private boolean hasActiveReturn(int playerId) {
        try {
            Integer n = jdbc.queryForObject("""
                    SELECT COUNT(1) FROM player_return_activity
                    WHERE player_id=? AND active=1 AND expire_at > CURRENT_TIMESTAMP
                    """, Integer.class, playerId);
            return n != null && n > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private void pushNotify(int playerId, int offlineDays, long expireAtMs) {
        GameSessionManager sessions = sessionProvider.getIfAvailable();
        if (sessions == null) {
            return;
        }
        GameSession session = sessions.getOrNull(playerId);
        if (session == null) {
            return;
        }
        QolSocialSystemProto.ReturnActivityScNotify notify =
                QolSocialSystemProto.ReturnActivityScNotify.newBuilder()
                        .setActivityId(config.activityId())
                        .setOfflineDays(offlineDays)
                        .setExpireAtMs(expireAtMs)
                        .setTitle(config.title() == null ? "" : config.title())
                        .setBonusDesc(config.bonusDesc() == null ? "" : config.bonusDesc())
                        .build();
        session.send(new GamePacket(CmdIds.RETURN_ACTIVITY_SC_NOTIFY, notify.toByteArray()));
    }
}
