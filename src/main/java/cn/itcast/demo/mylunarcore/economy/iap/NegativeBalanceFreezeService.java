package cn.itcast.demo.mylunarcore.economy.iap;

import cn.itcast.demo.mylunarcore.common.AppLogger;
import cn.itcast.demo.mylunarcore.common.LogCategory;
import cn.itcast.demo.mylunarcore.economy.WalletApplicationService;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 充值退款后的负资产冻结：余额允许为负，但禁止抽卡/交易直至补款。
 */
@Service
public class NegativeBalanceFreezeService {

    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_DATA, NegativeBalanceFreezeService.class);

    public enum FreezeReason {
        IAP_REFUND,
        CHARGEBACK,
        MANUAL
    }

    public record FreezeState(boolean frozen, FreezeReason reason, int debtCurrencyId, int debtAmount,
                              Instant since, String channelTxId) {}

    private final ConcurrentHashMap<Integer, FreezeState> freezes = new ConcurrentHashMap<>();
    private final WalletApplicationService walletApplicationService;

    public NegativeBalanceFreezeService(WalletApplicationService walletApplicationService) {
        this.walletApplicationService = walletApplicationService;
    }

    public FreezeState applyRefundClawback(int playerId, int currencyId, int amount, String channelTxId,
                                           FreezeReason reason) {
        if (playerId <= 0 || amount <= 0) {
            return freezes.get(playerId);
        }
        walletApplicationService.forceDeductAllowNegative(playerId, currencyId, amount, "iap_refund:" + channelTxId);
        FreezeState state = new FreezeState(true, reason == null ? FreezeReason.IAP_REFUND : reason,
                currencyId, amount, Instant.now(), channelTxId == null ? "" : channelTxId);
        freezes.put(playerId, state);
        log.warn("player frozen after refund playerId={} currency={} amount={} tx={}",
                playerId, currencyId, amount, channelTxId);
        return state;
    }

    public boolean isFrozen(int playerId) {
        FreezeState s = freezes.get(playerId);
        return s != null && s.frozen();
    }

    /** 抽卡/交易门禁：冻结则拒绝。 */
    public boolean assertCanSpend(int playerId) {
        return !isFrozen(playerId);
    }

    public FreezeState clearIfNonNegative(int playerId, int currencyId) {
        Map<Integer, Integer> bal = walletApplicationService.getBalance(playerId);
        int cur = bal.getOrDefault(currencyId, 0);
        if (cur >= 0) {
            FreezeState removed = freezes.remove(playerId);
            if (removed != null) {
                log.info("player unfrozen after repay playerId={}", playerId);
            }
            return new FreezeState(false, FreezeReason.MANUAL, currencyId, 0, Instant.now(), "");
        }
        return freezes.get(playerId);
    }

    public FreezeState get(int playerId) {
        return freezes.get(playerId);
    }

    public Set<Integer> frozenPlayerIds() {
        return Set.copyOf(freezes.keySet());
    }
}
