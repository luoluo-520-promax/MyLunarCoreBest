package cn.itcast.demo.mylunarcore.gacha;

import cn.itcast.demo.mylunarcore.repo.GachaHistoryRepository;
import cn.itcast.demo.mylunarcore.repo.MailRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 消费记录导出：组装 CSV，经邮件下发给玩家（标题「消费记录导出」）。
 */
@Service
public class TransactionHistoryExportService {

    private static final Logger log = LoggerFactory.getLogger(TransactionHistoryExportService.class);
    private static final int MAIL_CONTENT_MAX = 12_000;

    public record ExportResult(boolean ok, int retcode, int recordCount, long mailId) {
        static ExportResult fail(int retcode) {
            return new ExportResult(false, retcode, 0, 0L);
        }
    }

    private final JdbcTemplate jdbc;
    private final MailRepository mailRepository;
    private final ObjectProvider<GachaHistoryRepository> gachaHistoryProvider;

    public TransactionHistoryExportService(JdbcTemplate jdbc,
                                           MailRepository mailRepository,
                                           ObjectProvider<GachaHistoryRepository> gachaHistoryProvider) {
        this.jdbc = jdbc;
        this.mailRepository = mailRepository;
        this.gachaHistoryProvider = gachaHistoryProvider;
    }

    /**
     * @param kind gacha | shop | all
     * @param days 回溯天数，默认 30
     */
    public ExportResult export(int playerId, String kind, int days) {
        if (playerId <= 0) {
            return ExportResult.fail(1);
        }
        String k = kind == null || kind.isBlank() ? "all" : kind.trim().toLowerCase(Locale.ROOT);
        int d = days <= 0 ? 30 : Math.min(days, 365);
        Instant since = Instant.now().minus(d, ChronoUnit.DAYS);
        List<String> rows = new ArrayList<>();
        rows.add("kind,time,detail,amount");
        int count = 0;
        if ("gacha".equals(k) || "all".equals(k)) {
            count += appendGacha(playerId, since, rows);
        }
        if ("shop".equals(k) || "all".equals(k)) {
            count += appendShopOrWallet(playerId, since, rows);
        }
        if (count == 0 && rows.size() <= 1) {
            rows.add("none," + Instant.now() + ",no_records,0");
        }
        String csv = String.join("\n", rows);
        String content = csv;
        if (content.length() > MAIL_CONTENT_MAX) {
            content = content.substring(0, MAIL_CONTENT_MAX) + "\n...(truncated)";
        }
        Timestamp expire = Timestamp.from(Instant.now().plus(14, ChronoUnit.DAYS));
        long mailId = mailRepository.insertMail(playerId, "消费记录导出", content, null, expire);
        if (mailId <= 0) {
            return ExportResult.fail(5);
        }
        return new ExportResult(true, 0, count, mailId);
    }

    private int appendGacha(int playerId, Instant since, List<String> rows) {
        GachaHistoryRepository hist = gachaHistoryProvider.getIfAvailable();
        int added = 0;
        try {
            if (hist != null) {
                // 分页拉取近期（按天近似：最多 500 条）
                List<GachaHistoryRepository.HistoryRow> page = hist.page(playerId, 0, 1, 500);
                long sinceMs = since.toEpochMilli();
                for (GachaHistoryRepository.HistoryRow r : page) {
                    if (r.timestampMillis() < sinceMs) {
                        continue;
                    }
                    rows.add("gacha," + Instant.ofEpochMilli(r.timestampMillis())
                            + ",banner=" + r.bannerType() + ";item=" + r.itemId()
                            + ";new=" + r.isNew() + "," + r.count());
                    added++;
                }
                return added;
            }
            List<String> dbRows = jdbc.query("""
                    SELECT UNIX_TIMESTAMP(created_at)*1000 AS ts, banner_type, item_id, count, is_new
                    FROM gacha_draw_history
                    WHERE uid=? AND created_at >= ?
                    ORDER BY id DESC LIMIT 500
                    """, (rs, i) -> "gacha," + Instant.ofEpochMilli(rs.getLong("ts"))
                    + ",banner=" + rs.getInt("banner_type") + ";item=" + rs.getInt("item_id")
                    + ";new=" + (rs.getInt("is_new") == 1) + "," + rs.getInt("count"),
                    playerId, Timestamp.from(since));
            rows.addAll(dbRows);
            return dbRows.size();
        } catch (Exception e) {
            log.debug("export gacha skipped: {}", e.getMessage());
            return 0;
        }
    }

    private int appendShopOrWallet(int playerId, Instant since, List<String> rows) {
        // 优先 shop_buy_log；不存在则回退 wallet_ledger
        try {
            List<String> shop = jdbc.query("""
                    SELECT created_at, product_id, currency_id, amount, count
                    FROM shop_buy_log WHERE player_id=? AND created_at >= ?
                    ORDER BY id DESC LIMIT 500
                    """, (rs, i) -> {
                Timestamp ts = rs.getTimestamp("created_at");
                return "shop," + (ts == null ? Instant.EPOCH : ts.toInstant())
                        + ",product=" + rs.getInt("product_id") + ";currency=" + rs.getInt("currency_id")
                        + ";count=" + rs.getInt("count") + "," + rs.getInt("amount");
            }, playerId, Timestamp.from(since));
            if (!shop.isEmpty()) {
                rows.addAll(shop);
                return shop.size();
            }
        } catch (Exception e) {
            log.debug("shop_buy_log missing, fallback wallet: {}", e.getMessage());
        }
        try {
            List<String> ledger = jdbc.query("""
                    SELECT created_at, currency_id, delta, reason
                    FROM wallet_ledger WHERE uid=? AND created_at >= ?
                      AND (reason LIKE 'shop%' OR reason LIKE 'buy%' OR delta < 0)
                    ORDER BY id DESC LIMIT 500
                    """, (rs, i) -> {
                Timestamp ts = rs.getTimestamp("created_at");
                return "shop," + (ts == null ? Instant.EPOCH : ts.toInstant())
                        + ",currency=" + rs.getInt("currency_id") + ";reason="
                        + sanitize(rs.getString("reason")) + "," + rs.getInt("delta");
            }, playerId, Timestamp.from(since));
            rows.addAll(ledger);
            return ledger.size();
        } catch (Exception e) {
            log.debug("wallet_ledger export skipped: {}", e.getMessage());
            return 0;
        }
    }

    private static String sanitize(String s) {
        if (s == null) {
            return "";
        }
        return s.replace(',', ';').replace('\n', ' ');
    }
}
