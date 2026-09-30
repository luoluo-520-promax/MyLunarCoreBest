package cn.itcast.demo.mylunarcore.qol;

import cn.itcast.demo.mylunarcore.abyss.AbyssSeasonService;
import cn.itcast.demo.mylunarcore.guild.GuildWarService;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.protocol.QolSocialSystemProto;
import cn.itcast.demo.mylunarcore.worldboss.WorldBossService;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 登录后汇总世界BOSS/深渊/公会战剩余次数并推送提醒。
 */
@Service
public class DailyReminderService {

    private static final Logger log = LoggerFactory.getLogger(DailyReminderService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    public record Remain(String module, int remaining, int limit) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReminderCfg(boolean worldBoss, boolean abyss, boolean guildWar, boolean challenge) {
        static ReminderCfg defaults() {
            return new ReminderCfg(true, true, true, true);
        }
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final Path dataDir;
    private final ObjectProvider<WorldBossService> worldBossProvider;
    private final ObjectProvider<AbyssSeasonService> abyssProvider;
    private final ObjectProvider<GuildWarService> guildWarProvider;
    private final ObjectProvider<GameSessionManager> sessionProvider;
    private volatile ReminderCfg cfg = ReminderCfg.defaults();

    public DailyReminderService(JdbcTemplate jdbc,
                                ObjectMapper objectMapper,
                                @Value("${lunarcore.data-dir:data}") String dataDir,
                                ObjectProvider<WorldBossService> worldBossProvider,
                                ObjectProvider<AbyssSeasonService> abyssProvider,
                                ObjectProvider<GuildWarService> guildWarProvider,
                                ObjectProvider<GameSessionManager> sessionProvider) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.dataDir = Path.of(dataDir);
        this.worldBossProvider = worldBossProvider;
        this.abyssProvider = abyssProvider;
        this.guildWarProvider = guildWarProvider;
        this.sessionProvider = sessionProvider;
    }

    @PostConstruct
    public void loadConfig() {
        Path file = dataDir.resolve("DailyReminderConfig.json");
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            ReminderCfg loaded = objectMapper.readValue(Files.readString(file), ReminderCfg.class);
            if (loaded != null) {
                cfg = loaded;
            }
        } catch (Exception e) {
            log.warn("load DailyReminderConfig failed: {}", e.toString());
        }
    }

    public void setPlayerPref(int playerId, boolean enabled, List<String> disabledModules) {
        String disabled = disabledModules == null ? "" : String.join(",", disabledModules);
        try {
            jdbc.update("""
                    INSERT INTO player_daily_reminder_pref (player_id, enabled, disabled_modules, updated_at)
                    VALUES (?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE enabled=VALUES(enabled),
                      disabled_modules=VALUES(disabled_modules), updated_at=VALUES(updated_at)
                    """, playerId, enabled ? 1 : 0, disabled, Instant.now().toString());
        } catch (Exception e) {
            log.debug("reminder pref persist skipped: {}", e.getMessage());
        }
    }

    public void pushOnLogin(int playerId) {
        if (playerId <= 0 || !isEnabled(playerId)) {
            return;
        }
        Set<String> disabled = disabledModules(playerId);
        List<Remain> remains = collect(playerId, disabled);
        if (remains.isEmpty()) {
            return;
        }
        GameSessionManager sessions = sessionProvider.getIfAvailable();
        if (sessions == null) {
            return;
        }
        GameSession session = sessions.getOrNull(playerId);
        if (session == null) {
            return;
        }
        QolSocialSystemProto.DailyChallengeReminderScNotify.Builder b =
                QolSocialSystemProto.DailyChallengeReminderScNotify.newBuilder()
                        .setDay(LocalDate.now(ZONE).toString());
        for (Remain r : remains) {
            b.addEntries(QolSocialSystemProto.ChallengeRemainEntry.newBuilder()
                    .setModule(r.module())
                    .setRemaining(r.remaining())
                    .setLimit(r.limit())
                    .build());
        }
        session.send(new GamePacket(CmdIds.DAILY_CHALLENGE_REMINDER_SC_NOTIFY, b.build().toByteArray()));
    }

    private List<Remain> collect(int playerId, Set<String> disabled) {
        List<Remain> out = new ArrayList<>();
        ReminderCfg c = cfg;
        if (c.worldBoss() && !disabled.contains("world_boss")) {
            WorldBossService wb = worldBossProvider.getIfAvailable();
            if (wb != null) {
                WorldBossService.BossInfo info = wb.info();
                int used = wb.usedChallengesToday(playerId);
                int limit = Math.max(0, info.dailyLimit());
                out.add(new Remain("world_boss", Math.max(0, limit - used), limit));
            }
        }
        if (c.abyss() && !disabled.contains("abyss")) {
            AbyssSeasonService abyss = abyssProvider.getIfAvailable();
            if (abyss != null) {
                out.add(new Remain("abyss", 1, 1));
            }
        }
        if (c.guildWar() && !disabled.contains("guild_war")) {
            GuildWarService war = guildWarProvider.getIfAvailable();
            if (war != null) {
                out.add(new Remain("guild_war", war.remainingMatchesToday(playerId), war.dailyMatchLimit()));
            }
        }
        if (c.challenge() && !disabled.contains("challenge")) {
            out.add(new Remain("challenge", 3, 3));
        }
        return out;
    }

    private boolean isEnabled(int playerId) {
        try {
            Integer n = jdbc.queryForObject(
                    "SELECT enabled FROM player_daily_reminder_pref WHERE player_id=?",
                    Integer.class, playerId);
            return n == null || n == 1;
        } catch (Exception e) {
            return true;
        }
    }

    private Set<String> disabledModules(int playerId) {
        try {
            String s = jdbc.queryForObject(
                    "SELECT disabled_modules FROM player_daily_reminder_pref WHERE player_id=?",
                    String.class, playerId);
            if (s == null || s.isBlank()) {
                return Set.of();
            }
            Set<String> set = new HashSet<>();
            for (String part : s.split(",")) {
                if (!part.isBlank()) {
                    set.add(part.trim());
                }
            }
            return set;
        } catch (Exception e) {
            return Set.of();
        }
    }
}
