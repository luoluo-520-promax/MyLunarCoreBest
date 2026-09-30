package cn.itcast.demo.mylunarcore.economy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 钱包对账：比对 MySQL 余额与 wallet_ledger 流水汇总，标记异常并支持管理员一键修正。
 * 默认每日凌晨 02:15 运行（可用配置覆盖 cron）。
 */
@Service
public class WalletReconciliationJob {

    private static final Logger log = LoggerFactory.getLogger(WalletReconciliationJob.class);

    public enum AnomalyStatus { OPEN, RESOLVED, IGNORED }

    public record Anomaly(String anomalyId, int playerId, int currencyId,
                          int balanceDb, int ledgerSum, int delta, AnomalyStatus status,
                          long detectedAtMs) {}

    private final JdbcTemplate jdbc;
    private final WalletApplicationService wallet;
    private final Map<String, Anomaly> anomalies = new ConcurrentHashMap<>();
    private final AtomicInteger lastScanCount = new AtomicInteger();
    private volatile long lastRunAtMs;

    public WalletReconciliationJob(ObjectProvider<JdbcTemplate> jdbcProvider,
                                   ObjectProvider<WalletApplicationService> walletProvider) {
        this.jdbc = jdbcProvider == null ? null : jdbcProvider.getIfAvailable();
        this.wallet = walletProvider == null ? null : walletProvider.getIfAvailable();
    }

    /** 供测试直接注入。 */
    public WalletReconciliationJob(JdbcTemplate jdbc, WalletApplicationService wallet) {
        this.jdbc = jdbc;
        this.wallet = wallet;
    }

    @Scheduled(cron = "${lunarcore.wallet.reconcile-cron:0 15 2 * * *}")
    public void scheduledRun() {
        runOnce(500);
    }

    /**
     * 扫描最多 {@code limit} 名玩家；无 ledger 表时用内存 wallet peek 与空流水差分为 0 跳过。
     */
    public List<Anomaly> runOnce(int limit) {
        lastRunAtMs = System.currentTimeMillis();
        List<Anomaly> found = new ArrayList<>();
        if (jdbc == null) {
            lastScanCount.set(0);
            return found;
        }
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT player_id, currency_id, balance FROM player_wallet
                    ORDER BY player_id ASC LIMIT ?
                    """, Math.max(1, limit));
            for (Map<String, Object> row : rows) {
                int playerId = ((Number) row.get("player_id")).intValue();
                int currencyId = ((Number) row.get("currency_id")).intValue();
                int balance = ((Number) row.get("balance")).intValue();
                int ledgerSum = sumLedger(playerId, currencyId);
                int delta = balance - ledgerSum;
                if (delta != 0) {
                    String id = "wr-" + playerId + "-" + currencyId + "-" + lastRunAtMs;
                    Anomaly a = new Anomaly(id, playerId, currencyId, balance, ledgerSum, delta,
                            AnomalyStatus.OPEN, lastRunAtMs);
                    anomalies.put(id, a);
                    found.add(a);
                    log.error("wallet_reconcile_anomaly player={} currency={} balance={} ledger={} delta={}",
                            playerId, currencyId, balance, ledgerSum, delta);
                }
            }
            lastScanCount.set(rows.size());
        } catch (Exception e) {
            log.warn("wallet_reconcile_skip reason={}", e.getMessage());
            lastScanCount.set(0);
        }
        return found;
    }

    /** 管理员一键修正：以 ledger 为准回写余额。 */
    public boolean correct(String anomalyId, String operator) {
        Anomaly a = anomalies.get(anomalyId);
        if (a == null || a.status() != AnomalyStatus.OPEN || wallet == null) {
            return false;
        }
        int target = a.ledgerSum();
        int current = wallet.getBalance(a.playerId()).getOrDefault(a.currencyId(), 0);
        int diff = target - current;
        if (diff > 0) {
            wallet.add(a.playerId(), a.currencyId(), diff, "reconcile_fix:" + operator);
        } else if (diff < 0) {
            wallet.deduct(a.playerId(), a.currencyId(), -diff, "reconcile_fix:" + operator);
        }
        Anomaly done = new Anomaly(a.anomalyId(), a.playerId(), a.currencyId(),
                target, a.ledgerSum(), 0, AnomalyStatus.RESOLVED, a.detectedAtMs());
        anomalies.put(anomalyId, done);
        log.warn("wallet_reconcile_corrected id={} by={}", anomalyId, operator);
        return true;
    }

    /** 测试注入异常。 */
    public void putAnomaly(Anomaly a) {
        if (a != null) {
            anomalies.put(a.anomalyId(), a);
        }
    }

    public List<Anomaly> listOpen() {
        List<Anomaly> out = new ArrayList<>();
        for (Anomaly a : anomalies.values()) {
            if (a.status() == AnomalyStatus.OPEN) {
                out.add(a);
            }
        }
        return out;
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("lastRunAt", lastRunAtMs == 0 ? "" : Instant.ofEpochMilli(lastRunAtMs).toString());
        m.put("lastScanCount", lastScanCount.get());
        m.put("openAnomalies", listOpen().size());
        return m;
    }

    private int sumLedger(int playerId, int currencyId) {
        try {
            Integer sum = jdbc.queryForObject("""
                    SELECT COALESCE(SUM(delta), 0) FROM wallet_ledger
                    WHERE player_id = ? AND currency_id = ?
                    """, Integer.class, playerId, currencyId);
            return sum == null ? 0 : sum;
        } catch (Exception e) {
            return 0;
        }
    }
}
