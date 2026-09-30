package cn.itcast.demo.mylunarcore.gacha;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 抽卡返利积分（星尘）：抽一返一，可用于兑换限定道具，形成消费闭环。
 */
@Service
public class GachaRebateService {

    public record Balance(int points, int lifetimeDraws) {}

    public record ExchangeResult(boolean ok, int retcode, int remainingPoints) {
        public static ExchangeResult fail(int retcode, int bal) {
            return new ExchangeResult(false, retcode, bal);
        }
    }

    private final JdbcTemplate jdbc;
    private final int pointsPerDraw;

    public GachaRebateService(JdbcTemplate jdbc,
                              @Value("${lunarcore.gacha.rebate-points-per-draw:1}") int pointsPerDraw) {
        this.jdbc = jdbc;
        this.pointsPerDraw = Math.max(0, pointsPerDraw);
    }

    @Transactional
    public Balance grantForDraws(int playerId, int times) {
        if (playerId <= 0 || times <= 0 || pointsPerDraw <= 0) {
            return balance(playerId);
        }
        int gain = pointsPerDraw * times;
        jdbc.update("""
                INSERT INTO player_gacha_rebate (player_id, points, lifetime_draws)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE points = points + VALUES(points),
                  lifetime_draws = lifetime_draws + VALUES(lifetime_draws),
                  updated_at = NOW(3)
                """, playerId, gain, times);
        return balance(playerId);
    }

    public Balance balance(int playerId) {
        try {
            return jdbc.query("""
                    SELECT points, lifetime_draws FROM player_gacha_rebate WHERE player_id=?
                    """, rs -> {
                if (!rs.next()) {
                    return new Balance(0, 0);
                }
                return new Balance(rs.getInt(1), rs.getInt(2));
            }, playerId);
        } catch (Exception e) {
            return new Balance(0, 0);
        }
    }

    /** 用积分兑换道具（costPoints）；实际发奖由调用方负责。 */
    @Transactional
    public ExchangeResult trySpend(int playerId, int costPoints) {
        if (playerId <= 0 || costPoints <= 0) {
            return ExchangeResult.fail(2, 0);
        }
        Balance bal = balance(playerId);
        if (bal.points() < costPoints) {
            return ExchangeResult.fail(3, bal.points());
        }
        int updated = 0;
        try {
            updated = jdbc.update("""
                    UPDATE player_gacha_rebate SET points = points - ?, updated_at = NOW(3)
                    WHERE player_id = ? AND points >= ?
                    """, costPoints, playerId, costPoints);
        } catch (Exception e) {
            return ExchangeResult.fail(5, bal.points());
        }
        if (updated == 0) {
            return ExchangeResult.fail(3, bal.points());
        }
        return new ExchangeResult(true, 0, balance(playerId).points());
    }
}
