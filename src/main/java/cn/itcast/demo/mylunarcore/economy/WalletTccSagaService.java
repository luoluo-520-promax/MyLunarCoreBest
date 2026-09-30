package cn.itcast.demo.mylunarcore.economy;

import cn.itcast.demo.mylunarcore.tx.LocalTxLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 钱包关键写路径 TCC 骨架：Try 预留 → Confirm 实扣 → Cancel 释放。
 * <p>
 * 多节点下配合 {@link LocalTxLogService} 落补偿日志；失败可走 {@link #rollback(String)}。
 * 不引入 Seata，与现有 Redisson 锁 + WAL 共存，逐步替换裸扣路径。
 */
@Service
public class WalletTccSagaService {

    private static final Logger log = LoggerFactory.getLogger(WalletTccSagaService.class);

    public enum Phase { TRY, CONFIRMED, CANCELLED, FAILED }

    public record TccTxn(String txId, int playerId, int currencyId, int amount, String reason,
                         Phase phase, long createdAtMs) {}

    public record TccResult(boolean ok, String txId, Phase phase, String message) {}

    private final WalletApplicationService wallet;
    private final LocalTxLogService txLog;
    private final Map<String, TccTxn> inflight = new ConcurrentHashMap<>();
    /** Try 阶段预留额度（playerId:currencyId → reserved） */
    private final Map<String, Integer> reservations = new ConcurrentHashMap<>();

    public WalletTccSagaService(WalletApplicationService wallet,
                                ObjectProvider<LocalTxLogService> txLogProvider) {
        this.wallet = wallet;
        this.txLog = txLogProvider == null ? null : txLogProvider.getIfAvailable();
    }

    private static String reserveKey(int playerId, int currencyId) {
        return playerId + ":" + currencyId;
    }

    /** Try：校验余额并预留，不实扣。 */
    public TccResult tryReserve(int playerId, int currencyId, int amount, String reason) {
        if (playerId <= 0 || currencyId <= 0 || amount <= 0) {
            return new TccResult(false, "", Phase.FAILED, "invalid_args");
        }
        int available = wallet.getBalance(playerId).getOrDefault(currencyId, 0);
        int reserved = reservations.getOrDefault(reserveKey(playerId, currencyId), 0);
        if (available - reserved < amount) {
            return new TccResult(false, "", Phase.FAILED, "insufficient");
        }
        String txId = "tcc-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        reservations.merge(reserveKey(playerId, currencyId), amount, Integer::sum);
        TccTxn txn = new TccTxn(txId, playerId, currencyId, amount,
                reason == null ? "tcc" : reason, Phase.TRY, System.currentTimeMillis());
        inflight.put(txId, txn);
        if (txLog != null) {
            txLog.begin("wallet_tcc_try", playerId,
                    "{\"txId\":\"" + txId + "\",\"currencyId\":" + currencyId + ",\"amount\":" + amount + "}");
        }
        return new TccResult(true, txId, Phase.TRY, "reserved");
    }

    /** Confirm：实扣并释放预留。 */
    public TccResult confirm(String txId) {
        TccTxn txn = inflight.get(txId);
        if (txn == null || txn.phase() != Phase.TRY) {
            return new TccResult(false, txId == null ? "" : txId, Phase.FAILED, "not_in_try");
        }
        WalletApplicationService.WalletChangeResult r = wallet.deduct(
                txn.playerId(), txn.currencyId(), txn.amount(), txn.reason());
        releaseReserve(txn);
        if (!r.success()) {
            TccTxn failed = new TccTxn(txn.txId(), txn.playerId(), txn.currencyId(), txn.amount(),
                    txn.reason(), Phase.FAILED, txn.createdAtMs());
            inflight.put(txId, failed);
            if (txLog != null) {
                txLog.markFailed(txId);
            }
            return new TccResult(false, txId, Phase.FAILED, r.reason());
        }
        TccTxn ok = new TccTxn(txn.txId(), txn.playerId(), txn.currencyId(), txn.amount(),
                txn.reason(), Phase.CONFIRMED, txn.createdAtMs());
        inflight.put(txId, ok);
        if (txLog != null) {
            txLog.markCommitted(txId);
        }
        return new TccResult(true, txId, Phase.CONFIRMED, "ok");
    }

    /** Cancel / 回滚 API：释放预留。 */
    public TccResult rollback(String txId) {
        TccTxn txn = inflight.get(txId);
        if (txn == null) {
            return new TccResult(false, txId == null ? "" : txId, Phase.FAILED, "not_found");
        }
        if (txn.phase() == Phase.CONFIRMED) {
            // 已确认则走补偿加回
            wallet.add(txn.playerId(), txn.currencyId(), txn.amount(), "tcc_compensate:" + txId);
            if (txLog != null) {
                txLog.markCompensated(txId);
            }
        } else if (txn.phase() == Phase.TRY) {
            releaseReserve(txn);
            if (txLog != null) {
                txLog.markCompensating(txId);
                txLog.markCompensated(txId);
            }
        }
        TccTxn cancelled = new TccTxn(txn.txId(), txn.playerId(), txn.currencyId(), txn.amount(),
                txn.reason(), Phase.CANCELLED, txn.createdAtMs());
        inflight.put(txId, cancelled);
        log.info("wallet_tcc_rollback txId={} phase={}", txId, cancelled.phase());
        return new TccResult(true, txId, Phase.CANCELLED, "rolled_back");
    }

    public TccTxn get(String txId) {
        return inflight.get(txId);
    }

    public int reservedAmount(int playerId, int currencyId) {
        return reservations.getOrDefault(reserveKey(playerId, currencyId), 0);
    }

    private void releaseReserve(TccTxn txn) {
        String k = reserveKey(txn.playerId(), txn.currencyId());
        reservations.computeIfPresent(k, (kk, v) -> {
            int next = v - txn.amount();
            return next <= 0 ? null : next;
        });
    }
}
