package cn.itcast.demo.mylunarcore.admin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 玩家操作时间线：聚合 wallet_ledger 等流水，按秒排序供客服回溯。
 */
@Service
public class PlayerTimelineService {

    public record TimelineEntry(long id, Instant at, String source, String kind,
                                int currencyId, int delta, int balanceBefore, int balanceAfter,
                                String reason, String txId) {}

    private final JdbcTemplate jdbc;

    public PlayerTimelineService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<TimelineEntry> load(int uid, Instant from, Instant to, int limit) {
        int lim = Math.min(Math.max(limit, 1), 2000);
        Timestamp fromTs = Timestamp.from(from == null ? Instant.EPOCH : from);
        Timestamp toTs = Timestamp.from(to == null ? Instant.now() : to);
        List<TimelineEntry> out = new ArrayList<>();
        try {
            out.addAll(jdbc.query("""
                    SELECT id, created_at, currency_id, delta, balance_before, balance_after, reason, tx_id
                    FROM wallet_ledger
                    WHERE uid=? AND created_at >= ? AND created_at <= ?
                    ORDER BY created_at ASC, id ASC
                    LIMIT ?
                    """, (rs, i) -> new TimelineEntry(
                    rs.getLong("id"),
                    rs.getTimestamp("created_at").toInstant(),
                    "wallet_ledger",
                    rs.getInt("delta") >= 0 ? "INCOME" : "EXPENSE",
                    rs.getInt("currency_id"),
                    rs.getInt("delta"),
                    rs.getInt("balance_before"),
                    rs.getInt("balance_after"),
                    rs.getString("reason"),
                    rs.getString("tx_id")
            ), uid, fromTs, toTs, lim));
        } catch (Exception ignored) {
        }
        try {
            List<TimelineEntry> gacha = jdbc.query("""
                    SELECT id, created_at, banner_type, item_id, cost_currency_id, cost_amount, tx_id
                    FROM gacha_draw_history
                    WHERE player_id=? AND created_at >= ? AND created_at <= ?
                    ORDER BY created_at ASC, id ASC
                    LIMIT ?
                    """, (rs, i) -> new TimelineEntry(
                    rs.getLong("id"),
                    rs.getTimestamp("created_at").toInstant(),
                    "gacha_draw_history",
                    "GACHA",
                    rs.getInt("cost_currency_id"),
                    -Math.abs(rs.getInt("cost_amount")),
                    0, 0,
                    "gacha_item:" + rs.getInt("item_id") + ":banner:" + rs.getInt("banner_type"),
                    rs.getString("tx_id")
            ), uid, fromTs, toTs, lim);
            out.addAll(gacha);
        } catch (Exception ignored) {
        }
        out.sort((a, b) -> {
            int c = a.at().compareTo(b.at());
            return c != 0 ? c : Long.compare(a.id(), b.id());
        });
        if (out.size() > lim) {
            return out.subList(0, lim);
        }
        return out;
    }

    public Map<String, Object> asTree(int uid, Instant from, Instant to, int limit) {
        List<TimelineEntry> entries = load(uid, from, to, limit);
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (TimelineEntry e : entries) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("id", e.id());
            n.put("at", e.at().toString());
            n.put("source", e.source());
            n.put("kind", e.kind());
            n.put("currencyId", e.currencyId());
            n.put("delta", e.delta());
            n.put("balanceBefore", e.balanceBefore());
            n.put("balanceAfter", e.balanceAfter());
            n.put("reason", e.reason());
            n.put("txId", e.txId());
            nodes.add(n);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("uid", uid);
        root.put("count", nodes.size());
        root.put("entries", nodes);
        return root;
    }
}
