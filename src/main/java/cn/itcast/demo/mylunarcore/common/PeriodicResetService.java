package cn.itcast.demo.mylunarcore.common;

import cn.itcast.demo.mylunarcore.arena.ArenaRatingService;
import cn.itcast.demo.mylunarcore.guild.GuildWarService;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import org.slf4j.Logger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 每日/周常刷新调度：多节点下经 {@link ClusterJobLock} 单点执行。
 */
@Service
public class PeriodicResetService {

    public interface DailyResetHandler {
        void onDailyReset(LocalDate day);
    }

    public interface WeeklyResetHandler {
        void onWeeklyReset(LocalDate weekMonday);
    }

    private static final Logger log = AppLogger.logger(LogCategory.SYSTEM, PeriodicResetService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final JdbcTemplate jdbc;
    private final ClusterJobLock clusterJobLock;
    private final GuildWarService guildWarService;
    private final ArenaRatingService arenaRatingService;
    private final LunarCoreProperties properties;
    private final List<DailyResetHandler> dailyHandlers = new CopyOnWriteArrayList<>();
    private final List<WeeklyResetHandler> weeklyHandlers = new CopyOnWriteArrayList<>();

    public PeriodicResetService(JdbcTemplate jdbc,
                                ClusterJobLock clusterJobLock,
                                GuildWarService guildWarService,
                                ArenaRatingService arenaRatingService,
                                LunarCoreProperties properties) {
        this.jdbc = jdbc;
        this.clusterJobLock = clusterJobLock;
        this.guildWarService = guildWarService;
        this.arenaRatingService = arenaRatingService;
        this.properties = properties;
        registerBuiltinHandlers();
    }

    public void registerDaily(DailyResetHandler handler) {
        if (handler != null) {
            dailyHandlers.add(handler);
        }
    }

    public void registerWeekly(WeeklyResetHandler handler) {
        if (handler != null) {
            weeklyHandlers.add(handler);
        }
    }

    /** 由游戏 Tick 调用：检测跨日并触发（本机检测 + 集群锁执行）。 */
    public void onTick(long nowMillis) {
        LocalDate today = Instant.ofEpochMilli(nowMillis).atZone(ZONE).toLocalDate();
        tryDaily(today);
        if (today.getDayOfWeek() == DayOfWeek.MONDAY) {
            tryWeekly(today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)));
        }
    }

    @Scheduled(cron = "${lunarcore.periodic-reset.cron:0 0 4 * * *}")
    public void scheduledDaily() {
        tryDaily(LocalDate.now(ZONE));
        LocalDate monday = LocalDate.now(ZONE).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        if (LocalDate.now(ZONE).equals(monday)) {
            tryWeekly(monday);
        }
        guildWarService.settleSeasonIfDue();
        arenaRatingService.rolloverSeasonIfNeeded();
    }

    private void tryDaily(LocalDate day) {
        String period = day.toString();
        if (!claimWatermark("daily", period)) {
            return;
        }
        clusterJobLock.tryRun("daily-reset:" + period, Duration.ofMinutes(5), () -> {
            log.info("Periodic daily reset day={}", day);
            for (DailyResetHandler h : dailyHandlers) {
                try {
                    h.onDailyReset(day);
                } catch (Exception e) {
                    log.warn("daily reset handler failed: {}", e.toString());
                }
            }
        });
    }

    private void tryWeekly(LocalDate monday) {
        String period = monday.toString();
        if (!claimWatermark("weekly", period)) {
            return;
        }
        clusterJobLock.tryRun("weekly-reset:" + period, Duration.ofMinutes(10), () -> {
            log.info("Periodic weekly reset week={}", monday);
            for (WeeklyResetHandler h : weeklyHandlers) {
                try {
                    h.onWeeklyReset(monday);
                } catch (Exception e) {
                    log.warn("weekly reset handler failed: {}", e.toString());
                }
            }
        });
    }

    private boolean claimWatermark(String resetKey, String period) {
        try {
            String prev = jdbc.query("""
                    SELECT last_period FROM periodic_reset_watermark WHERE reset_key = ?
                    """, rs -> rs.next() ? rs.getString(1) : null, resetKey);
            if (period.equals(prev)) {
                return false;
            }
            jdbc.update("""
                    INSERT INTO periodic_reset_watermark (reset_key, last_period) VALUES (?, ?)
                    ON DUPLICATE KEY UPDATE last_period = IF(last_period = VALUES(last_period), last_period, VALUES(last_period))
                    """, resetKey, period);
            String after = jdbc.query("""
                    SELECT last_period FROM periodic_reset_watermark WHERE reset_key = ?
                    """, rs -> rs.next() ? rs.getString(1) : null, resetKey);
            return period.equals(after);
        } catch (Exception e) {
            // 表未就绪：允许本节点执行（ClusterJobLock 仍防多节点）
            return true;
        }
    }

    private void registerBuiltinHandlers() {
        registerWeekly(weekMonday -> {
            try {
                int n = jdbc.update("UPDATE guild_member SET weekly_contrib = 0");
                log.info("Reset guild weekly_contrib rows={}", n);
            } catch (Exception e) {
                log.debug("weekly_contrib reset skipped: {}", e.getMessage());
            }
        });
        registerDaily(day -> log.info("Daily reset hooks ready day={} node={}",
                day, properties.getCenter().getLocalNodeId()));
    }

    public List<String> registeredHandlerNames() {
        List<String> names = new ArrayList<>();
        names.add("dailyHandlers=" + dailyHandlers.size());
        names.add("weeklyHandlers=" + weeklyHandlers.size());
        return names;
    }
}
