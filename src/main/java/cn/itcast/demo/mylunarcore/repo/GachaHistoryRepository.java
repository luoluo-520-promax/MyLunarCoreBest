package cn.itcast.demo.mylunarcore.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 抽卡历史仓储：落库审计与分页查询。
 */
@Repository
public class GachaHistoryRepository {

    public record HistoryRow(long timestampMillis, int bannerType, int itemId, int count, boolean isNew) {}

    private final JdbcTemplate jdbcTemplate;

    public GachaHistoryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insertBatch(int playerId, int bannerType, List<ItemGrant> grants,
                            int costCurrencyId, int costPerDraw, String txId) {
        if (grants == null || grants.isEmpty()) {
            return;
        }
        String sql = """
                INSERT INTO gacha_draw_history
                (uid, banner_type, item_id, count, is_new, cost_currency_id, cost_amount, tx_id)
                VALUES (?, ?, ?, 1, ?, ?, ?, ?)
                """;
        for (ItemGrant grant : grants) {
            jdbcTemplate.update(sql, playerId, bannerType, grant.itemId(), grant.isNew() ? 1 : 0,
                    costCurrencyId, Math.max(0, costPerDraw), txId);
        }
    }

    public int count(int playerId, int bannerType) {
        if (bannerType <= 0) {
            Integer n = jdbcTemplate.queryForObject(
                    "SELECT COUNT(1) FROM gacha_draw_history WHERE uid = ?", Integer.class, playerId);
            return n == null ? 0 : n;
        }
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(1) FROM gacha_draw_history WHERE uid = ? AND banner_type = ?",
                Integer.class, playerId, bannerType);
        return n == null ? 0 : n;
    }

    public List<HistoryRow> page(int playerId, int bannerType, int page, int pageSize) {
        int size = Math.min(Math.max(pageSize, 1), 50);
        int p = Math.max(page, 1);
        int offset = (p - 1) * size;
        String sql;
        Object[] args;
        if (bannerType <= 0) {
            sql = """
                    SELECT UNIX_TIMESTAMP(created_at) * 1000 AS ts, banner_type, item_id, count, is_new
                    FROM gacha_draw_history WHERE uid = ?
                    ORDER BY id DESC LIMIT ? OFFSET ?
                    """;
            args = new Object[]{playerId, size, offset};
        } else {
            sql = """
                    SELECT UNIX_TIMESTAMP(created_at) * 1000 AS ts, banner_type, item_id, count, is_new
                    FROM gacha_draw_history WHERE uid = ? AND banner_type = ?
                    ORDER BY id DESC LIMIT ? OFFSET ?
                    """;
            args = new Object[]{playerId, bannerType, size, offset};
        }
        return jdbcTemplate.query(sql, (rs, rowNum) -> new HistoryRow(
                rs.getLong("ts"),
                rs.getInt("banner_type"),
                rs.getInt("item_id"),
                rs.getInt("count"),
                rs.getInt("is_new") == 1
        ), args);
    }

    public record ItemGrant(int itemId, boolean isNew) {}
}
