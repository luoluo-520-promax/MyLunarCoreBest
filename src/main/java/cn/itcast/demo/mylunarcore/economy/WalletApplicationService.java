package cn.itcast.demo.mylunarcore.economy;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.repo.WalletRepository;
import cn.itcast.demo.mylunarcore.tx.DistributedLockService;
import cn.itcast.demo.mylunarcore.tx.LocalTxLogService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 玩家多币种钱包应用服务。
 * 默认走 {@link WalletWalService} 本地预扣 + 异步落盘；关闭 WAL 时回退行锁事务路径。
 */
@Service
public class WalletApplicationService {

    public record WalletChangeResult(boolean success, Map<Integer, Integer> balance, String reason) {}

    private final WalletRepository walletRepository;
    private final DistributedLockService distributedLockService;
    private final LocalTxLogService localTxLogService;
    private final ObjectProvider<WalletWalService> walProvider;
    private final ObjectProvider<HotWalletCacheService> hotCacheProvider;
    private final LunarCoreProperties properties;

    public WalletApplicationService(WalletRepository walletRepository,
                                    DistributedLockService distributedLockService,
                                    ObjectProvider<LocalTxLogService> localTxLogServiceProvider) {
        this(walletRepository, distributedLockService, localTxLogServiceProvider, emptyProvider(), emptyProvider(),
                new LunarCoreProperties());
    }

    public WalletApplicationService(WalletRepository walletRepository,
                                    DistributedLockService distributedLockService,
                                    ObjectProvider<LocalTxLogService> localTxLogServiceProvider,
                                    ObjectProvider<WalletWalService> walProvider,
                                    LunarCoreProperties properties) {
        this(walletRepository, distributedLockService, localTxLogServiceProvider, walProvider, emptyProvider(), properties);
    }

    public WalletApplicationService(WalletRepository walletRepository,
                                    DistributedLockService distributedLockService,
                                    ObjectProvider<LocalTxLogService> localTxLogServiceProvider,
                                    ObjectProvider<WalletWalService> walProvider,
                                    ObjectProvider<HotWalletCacheService> hotCacheProvider,
                                    LunarCoreProperties properties) {
        this.walletRepository = walletRepository;
        this.distributedLockService = distributedLockService;
        this.localTxLogService = localTxLogServiceProvider.getIfAvailable();
        this.walProvider = walProvider;
        this.hotCacheProvider = hotCacheProvider;
        this.properties = properties == null ? new LunarCoreProperties() : properties;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> emptyProvider() {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                throw new UnsupportedOperationException("empty");
            }

            @Override
            public T getIfAvailable() {
                return null;
            }

            @Override
            public T getIfUnique() {
                return null;
            }
        };
    }

    private boolean walEnabled() {
        WalletWalService wal = walProvider.getIfAvailable();
        return wal != null && properties.getWalletWal().isEnabled() && wal.isEnabled();
    }

    @Transactional(readOnly = true)
    public Map<Integer, Integer> getBalance(int playerId) {
        HotWalletCacheService cache = hotCache();
        if (cache != null) {
            Map<Integer, Integer> cached = cache.get(playerId);
            if (cached != null) {
                return cached;
            }
        }
        Map<Integer, Integer> balance;
        if (walEnabled()) {
            balance = walProvider.getObject().peekBalance(playerId);
        } else {
            balance = walletRepository.loadCurrency(playerId);
        }
        if (cache != null && balance != null) {
            cache.put(playerId, balance);
        }
        return balance;
    }

    private HotWalletCacheService hotCache() {
        return hotCacheProvider == null ? null : hotCacheProvider.getIfAvailable();
    }

    private void afterWrite(int playerId, Map<Integer, Integer> balance, boolean success) {
        HotWalletCacheService cache = hotCache();
        if (cache == null || !success) {
            return;
        }
        cache.put(playerId, balance);
        cache.invalidateWithDelayDoubleDelete(playerId);
        cache.put(playerId, balance);
    }

    public boolean canAfford(int playerId, int currencyId, int amount) {
        if (amount <= 0) {
            return true;
        }
        Map<Integer, Integer> balance = getBalance(playerId);
        return balance.getOrDefault(currencyId, 0) >= amount;
    }

    private static final int OPTIMISTIC_CAS_RETRIES = 8;

    @Transactional
    public WalletChangeResult add(int playerId, int currencyId, int amount, String reason) {
        if (amount <= 0) {
            return new WalletChangeResult(false, getBalance(playerId), reason);
        }
        if (walEnabled()) {
            return walProvider.getObject().applyLocal(playerId, currencyId, amount, reason);
        }
        return distributedLockService.withPlayerWalletLock(playerId,
                () -> applyDeltaWithRetry(playerId, currencyId, amount, reason));
    }

    @Transactional
    public WalletChangeResult deduct(int playerId, int currencyId, int amount, String reason) {
        if (amount <= 0) {
            return new WalletChangeResult(true, getBalance(playerId), reason);
        }
        if (walEnabled()) {
            return walProvider.getObject().applyLocal(playerId, currencyId, -amount, reason);
        }
        return distributedLockService.withPlayerWalletLock(playerId,
                () -> applyDeltaWithRetry(playerId, currencyId, -amount, reason));
    }

    @Transactional
    public WalletChangeResult forceDeductAllowNegative(int playerId, int currencyId, int amount, String reason) {
        if (amount <= 0) {
            return new WalletChangeResult(true, getBalance(playerId), reason);
        }
        return distributedLockService.withPlayerWalletLock(playerId,
                () -> applyDeltaAllowNegative(playerId, currencyId, -amount, reason));
    }

    private WalletChangeResult applyDeltaAllowNegative(int playerId, int currencyId, int delta, String reason) {
        WalletRepository.CurrencyLockRow row = walletRepository.lockCurrency(playerId);
        if (row == null) {
            return new WalletChangeResult(false, Map.of(), reason);
        }
        Map<Integer, Integer> balance = new HashMap<>(row.balance());
        int current = balance.getOrDefault(currencyId, 0);
        long next = (long) current + delta;
        if (next > Integer.MAX_VALUE || next < Integer.MIN_VALUE) {
            return new WalletChangeResult(false, balance, reason);
        }
        balance.put(currencyId, (int) next);
        boolean ok = walletRepository.updateCurrencyOptimistic(playerId, balance, row.dataVersion());
        if (ok) {
            walletRepository.insertLedger(
                    playerId, currencyId, delta, reason, current, (int) next, UUID.randomUUID().toString());
            afterWrite(playerId, balance, true);
        }
        return new WalletChangeResult(ok, balance, reason);
    }

    private WalletChangeResult applyDeltaWithRetry(int playerId, int currencyId, int delta, String reason) {
        WalletChangeResult last = new WalletChangeResult(false, Map.of(), reason);
        for (int attempt = 1; attempt <= OPTIMISTIC_CAS_RETRIES; attempt++) {
            last = applyDelta(playerId, currencyId, delta, reason);
            if (last.success()) {
                return last;
            }
            if (delta < 0) {
                int bal = last.balance().getOrDefault(currencyId, 0);
                if (bal < -delta) {
                    return last;
                }
            }
            if (last.balance().isEmpty()) {
                return last;
            }
            try {
                Thread.sleep(Math.min(16L, attempt));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return last;
            }
        }
        return last;
    }

    private WalletChangeResult applyDelta(int playerId, int currencyId, int delta, String reason) {
        String txId = null;
        if (localTxLogService != null) {
            txId = localTxLogService.begin(
                    delta < 0 ? "wallet_deduct" : "wallet_add",
                    playerId,
                    "{\"currencyId\":" + currencyId + ",\"delta\":" + delta
                            + ",\"reason\":\"" + (reason == null ? "" : reason.replace("\"", "")) + "\"}");
        }
        WalletRepository.CurrencyLockRow row = walletRepository.lockCurrency(playerId);
        if (row == null) {
            if (txId != null) {
                localTxLogService.markFailed(txId);
            }
            return new WalletChangeResult(false, Map.of(), reason);
        }
        Map<Integer, Integer> balance = new HashMap<>(row.balance());
        int current = balance.getOrDefault(currencyId, 0);
        long next = (long) current + delta;
        if (delta < 0 && current < -delta) {
            if (txId != null) {
                localTxLogService.markFailed(txId);
            }
            return new WalletChangeResult(false, balance, reason);
        }
        if (next > Integer.MAX_VALUE || next < 0) {
            if (txId != null) {
                localTxLogService.markFailed(txId);
            }
            return new WalletChangeResult(false, balance, reason);
        }
        balance.put(currencyId, (int) next);
        boolean ok = walletRepository.updateCurrencyOptimistic(playerId, balance, row.dataVersion());
        if (ok) {
            walletRepository.insertLedger(
                    playerId, currencyId, delta, reason, current, (int) next, UUID.randomUUID().toString());
            if (txId != null) {
                localTxLogService.markCommitted(txId);
            }
            afterWrite(playerId, balance, true);
        } else if (txId != null) {
            localTxLogService.markFailed(txId);
        }
        return new WalletChangeResult(ok, balance, reason);
    }
}
