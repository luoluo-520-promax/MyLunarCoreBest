package cn.itcast.demo.mylunarcore.economy;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * 首充双倍与年度重置：支付回调时叠加额外赠送比例。
 */
@Service
public class TopUpBonusService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /** 首充双倍：赠送基数 100% */
    public static final double FIRST_TOPUP_BONUS_RATIO = 1.0;
    /** 非首充：无额外（可配活动加码） */
    public static final double NORMAL_BONUS_RATIO = 0.0;

    public record BonusDecision(boolean firstTopup, double bonusRatio, int bonusAmount, String yearCycle) {}

    private final JdbcTemplate jdbc;

    public TopUpBonusService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param baseGrantAmount 礼包基础货币量
     * @return 应额外赠送的数量与是否首充
     */
    @Transactional
    public BonusDecision decideAndRecord(int playerId, int baseGrantAmount, long payCents) {
        ensureRow(playerId);
        maybeYearReset(playerId);
        boolean first = !isFirstTopupDone(playerId);
        double ratio = first ? FIRST_TOPUP_BONUS_RATIO : NORMAL_BONUS_RATIO;
        int bonus = (int) Math.floor(Math.max(0, baseGrantAmount) * ratio);
        String year = currentYearCycle();
        jdbc.update("""
                UPDATE player_topup_bonus SET
                  first_topup_done = 1,
                  year_cycle = ?,
                  total_topup_cents = total_topup_cents + ?,
                  updated_at = NOW(3)
                WHERE player_id = ?
                """, year, Math.max(0L, payCents), playerId);
        return new BonusDecision(first, ratio, bonus, year);
    }

    public boolean isFirstTopupDone(int playerId) {
        try {
            Integer v = jdbc.queryForObject(
                    "SELECT first_topup_done FROM player_topup_bonus WHERE player_id=?",
                    Integer.class, playerId);
            return v != null && v == 1;
        } catch (Exception e) {
            return false;
        }
    }

    /** 年度重置：每年 1 月 1 日清空首充标记，恢复「首充双倍」。 */
    public void maybeYearReset(int playerId) {
        String year = currentYearCycle();
        try {
            List<String> rows = jdbc.query(
                    "SELECT year_cycle FROM player_topup_bonus WHERE player_id=?",
                    (rs, i) -> rs.getString(1), playerId);
            if (rows.isEmpty()) {
                return;
            }
            String prev = rows.get(0);
            if (prev != null && !prev.isBlank() && !year.equals(prev)) {
                jdbc.update("""
                        UPDATE player_topup_bonus SET first_topup_done=0, year_cycle=?,
                          year_reset_at=NOW(3), updated_at=NOW(3)
                        WHERE player_id=?
                        """, year, playerId);
            }
        } catch (Exception ignored) {
        }
    }

    private void ensureRow(int playerId) {
        try {
            jdbc.update("""
                    INSERT IGNORE INTO player_topup_bonus (player_id, first_topup_done, year_cycle)
                    VALUES (?, 0, ?)
                    """, playerId, currentYearCycle());
        } catch (Exception ignored) {
        }
    }

    static String currentYearCycle() {
        return String.valueOf(LocalDate.now(ZONE).getYear());
    }
}
