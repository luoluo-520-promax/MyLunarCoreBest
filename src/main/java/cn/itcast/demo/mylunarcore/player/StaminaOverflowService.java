package cn.itcast.demo.mylunarcore.player;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 体力溢出储备池：体力已满时，后续自然恢复按比例写入后备池，可在当前体力不足时提取。
 */
@Service
public class StaminaOverflowService {

    public static final double DEFAULT_OVERFLOW_RATIO = 0.30;
    public static final int DEFAULT_RESERVE_CAP = 240;

    public record ReserveSnapshot(int reserve, int reserveCap) {}

    private final JdbcTemplate jdbc;

    public StaminaOverflowService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public int readReserve(int playerId) {
        if (playerId <= 0 || jdbc == null) {
            return 0;
        }
        try {
            Integer n = jdbc.queryForObject(
                    "SELECT reserve_stamina FROM player_stamina_meta WHERE player_id = ?",
                    Integer.class, playerId);
            return n == null ? 0 : Math.max(0, n);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 体力已满时，把本应浪费的恢复量按比例写入后备池。
     *
     * @return 实际写入后备池的点数
     */
    public int absorbOverflowRegen(int playerId, int wastedGain) {
        if (playerId <= 0 || wastedGain <= 0) {
            return 0;
        }
        int deposit = (int) Math.floor(wastedGain * DEFAULT_OVERFLOW_RATIO);
        if (deposit <= 0) {
            return 0;
        }
        return addReserve(playerId, deposit);
    }

    public int addReserve(int playerId, int amount) {
        if (playerId <= 0 || amount <= 0) {
            return 0;
        }
        int cur = readReserve(playerId);
        int next = Math.min(DEFAULT_RESERVE_CAP, cur + amount);
        int added = next - cur;
        if (added <= 0) {
            return 0;
        }
        writeReserve(playerId, next);
        return added;
    }

    /**
     * 从后备池提取到当前体力槽。
     *
     * @return 实际提取量
     */
    public int withdraw(int playerId, int amount) {
        if (playerId <= 0 || amount <= 0) {
            return 0;
        }
        int cur = readReserve(playerId);
        int take = Math.min(cur, amount);
        if (take <= 0) {
            return 0;
        }
        writeReserve(playerId, cur - take);
        return take;
    }

    public ReserveSnapshot snapshot(int playerId) {
        return new ReserveSnapshot(readReserve(playerId), DEFAULT_RESERVE_CAP);
    }

    private void writeReserve(int playerId, int reserve) {
        if (jdbc == null) {
            return;
        }
        try {
            jdbc.update("""
                    INSERT INTO player_stamina_meta (player_id, last_regen_at_ms, daily_buy_count, buy_day, reserve_stamina)
                    VALUES (?, ?, 0, '', ?)
                    ON DUPLICATE KEY UPDATE reserve_stamina = VALUES(reserve_stamina)
                    """, playerId, System.currentTimeMillis(), Math.max(0, reserve));
        } catch (Exception ignored) {
            try {
                jdbc.update("UPDATE player_stamina_meta SET reserve_stamina = ? WHERE player_id = ?",
                        Math.max(0, reserve), playerId);
            } catch (Exception ignored2) {
                // 列未就绪时仅跳过
            }
        }
    }
}
