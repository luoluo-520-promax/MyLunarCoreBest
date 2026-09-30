package cn.itcast.demo.mylunarcore.achievement;

import cn.itcast.demo.mylunarcore.common.AppLogger;
import cn.itcast.demo.mylunarcore.common.LogCategory;
import org.slf4j.Logger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 离线成就补偿：登录时根据归档/离线期间累计（经验/金币/通关数）异步补发成就进度。
 */
@Service
public class OfflineAchievementCompensator {

    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_DATA, OfflineAchievementCompensator.class);

    private final JdbcTemplate jdbc;
    private final AchievementService achievementService;

    public OfflineAchievementCompensator(JdbcTemplate jdbc, AchievementService achievementService) {
        this.jdbc = jdbc;
        this.achievementService = achievementService;
    }

    /**
     * 玩家登录后调用：读取离线窗口内可回溯指标并补进度。
     *
     * @return 各成就实际补发的 delta
     */
    public Map<String, Integer> compensateOnLogin(int playerId, Instant lastLogoutAt) {
        Map<String, Integer> applied = new HashMap<>();
        if (playerId <= 0) {
            return applied;
        }
        Instant since = lastLogoutAt == null ? Instant.EPOCH : lastLogoutAt;
        try {
            int battleWins = countSince(playerId, since, """
                    SELECT COUNT(1) FROM battle_record
                    WHERE player_id = ? AND result = 'WIN' AND ended_at >= ?
                    """);
            if (battleWins > 0) {
                achievementService.addProgress(playerId, "battle_win_20", battleWins);
                applied.put("battle_win_20", battleWins);
            }

            int gachaDraws = countSince(playerId, since, """
                    SELECT COALESCE(SUM(draw_count), 0) FROM gacha_history
                    WHERE player_id = ? AND created_at >= ?
                    """);
            if (gachaDraws > 0) {
                achievementService.addProgress(playerId, "gacha_10", gachaDraws);
                achievementService.addProgress(playerId, "gacha_100", gachaDraws);
                applied.put("gacha", gachaDraws);
            }

            // 归档表可选：player_archive 上的离线收益摘要
            List<Map<String, Object>> archives = jdbc.queryForList("""
                    SELECT offline_battle_wins, offline_gacha_draws FROM player_archive
                    WHERE player_id = ? AND archived_at >= ? LIMIT 1
                    """, playerId, since.toString());
            if (!archives.isEmpty()) {
                Map<String, Object> row = archives.get(0);
                int ab = toInt(row.get("offline_battle_wins"));
                int ag = toInt(row.get("offline_gacha_draws"));
                if (ab > 0) {
                    achievementService.addProgress(playerId, "battle_win_20", ab);
                    applied.merge("battle_win_20", ab, Integer::sum);
                }
                if (ag > 0) {
                    achievementService.addProgress(playerId, "gacha_10", ag);
                    achievementService.addProgress(playerId, "gacha_100", ag);
                    applied.merge("gacha", ag, Integer::sum);
                }
            }
        } catch (Exception e) {
            log.debug("offline achievement compensate skipped playerId={}: {}", playerId, e.toString());
        }
        if (!applied.isEmpty()) {
            log.info("offline achievement compensated playerId={} deltas={}", playerId, applied);
        }
        return applied;
    }

    private int countSince(int playerId, Instant since, String sql) {
        try {
            Integer n = jdbc.queryForObject(sql, Integer.class, playerId, since.toString());
            return n == null ? 0 : Math.max(0, n);
        } catch (Exception e) {
            return 0;
        }
    }

    private static int toInt(Object v) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(v));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
