package cn.itcast.demo.mylunarcore.economy;

import cn.itcast.demo.mylunarcore.common.AppLogger;
import cn.itcast.demo.mylunarcore.common.LogCategory;
import cn.itcast.demo.mylunarcore.repo.WalletRepository;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 本地预扣 + 异步落盘（WAL + 内存合并）：
 * <ul>
 *   <li>扣币先改 {@link ConcurrentHashMap} 缓存，延迟 &lt;1ms 返回</li>
 *   <li>生成 {@link WalletDeltaLog} 写入本地磁盘队列</li>
 *   <li>{@link #flushOnce} 每 200ms 或积压 50 条时批量合并 UPDATE + CAS 重试</li>
 * </ul>
 */
@Service
public class WalletWalService {

    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_SYNC, WalletWalService.class);
    private static final int FLUSH_BATCH = 50;
    private static final long FLUSH_INTERVAL_MS = 200L;
    private static final int CAS_RETRIES = 8;

    private final WalletRepository walletRepository;
    private final TransactionTemplate txTemplate;
    private final ConcurrentHashMap<Integer, ConcurrentHashMap<Integer, Integer>> cache = new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<WalletDeltaLog> pending = new ConcurrentLinkedQueue<>();
    private final AtomicLong seqGen = new AtomicLong();
    private final Path walDir;
    private ScheduledExecutorService flushExecutor;
    private volatile boolean enabled = true;

    public WalletWalService(WalletRepository walletRepository,
                            PlatformTransactionManager transactionManager,
                            @org.springframework.beans.factory.annotation.Value("${lunarcore.data-dir:data}") String dataDir) {
        this.walletRepository = walletRepository;
        this.txTemplate = new TransactionTemplate(transactionManager);
        this.walDir = Path.of(dataDir, "wal");
    }

    @PostConstruct
    public void start() {
        try {
            Files.createDirectories(walDir);
        } catch (Exception e) {
            log.warn("wallet wal dir create failed: {}", e.toString());
        }
        flushExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "wallet-wal-flush");
            t.setDaemon(true);
            return t;
        });
        flushExecutor.scheduleWithFixedDelay(this::safeFlush, FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
        log.info("WalletWalService started, flushIntervalMs={}, batch={}", FLUSH_INTERVAL_MS, FLUSH_BATCH);
    }

    @PreDestroy
    public void stop() {
        enabled = false;
        safeFlush();
        if (flushExecutor != null) {
            flushExecutor.shutdown();
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 确保缓存已加载；返回当前余额视图。 */
    public Map<Integer, Integer> ensureLoaded(int playerId) {
        return cache.computeIfAbsent(playerId, id -> {
            Map<Integer, Integer> fromDb = walletRepository.loadCurrency(id);
            return new ConcurrentHashMap<>(fromDb);
        });
    }

    public Map<Integer, Integer> peekBalance(int playerId) {
        ConcurrentHashMap<Integer, Integer> m = cache.get(playerId);
        if (m == null) {
            return ensureLoaded(playerId);
        }
        return new HashMap<>(m);
    }

    /**
     * 内存预扣/预加；成功后入 WAL 队列。
     */
    public WalletApplicationService.WalletChangeResult applyLocal(int playerId, int currencyId, int delta, String reason) {
        ConcurrentHashMap<Integer, Integer> bal = (ConcurrentHashMap<Integer, Integer>) ensureLoaded(playerId);
        synchronized (bal) {
            int current = bal.getOrDefault(currencyId, 0);
            long next = (long) current + delta;
            if (delta < 0 && current < -delta) {
                return new WalletApplicationService.WalletChangeResult(false, new HashMap<>(bal), reason);
            }
            if (next > Integer.MAX_VALUE || next < 0) {
                return new WalletApplicationService.WalletChangeResult(false, new HashMap<>(bal), reason);
            }
            bal.put(currencyId, (int) next);
            WalletDeltaLog entry = new WalletDeltaLog(seqGen.incrementAndGet(), playerId, currencyId, delta,
                    reason == null ? "" : reason, System.currentTimeMillis());
            pending.offer(entry);
            appendWalFile(entry);
            if (pending.size() >= FLUSH_BATCH) {
                flushExecutor.execute(this::safeFlush);
            }
            return new WalletApplicationService.WalletChangeResult(true, new HashMap<>(bal), reason);
        }
    }

    void safeFlush() {
        try {
            flushOnce();
        } catch (Exception e) {
            log.warn("wallet wal flush failed: {}", e.toString());
        }
    }

    /**
     * 批量合并：同 uid+currency 的 delta 求和后一次 CAS 更新。
     */
    public int flushOnce() {
        List<WalletDeltaLog> batch = new ArrayList<>(FLUSH_BATCH);
        WalletDeltaLog item;
        while (batch.size() < FLUSH_BATCH && (item = pending.poll()) != null) {
            batch.add(item);
        }
        if (batch.isEmpty()) {
            return 0;
        }
        // key = playerId << 32 | currencyId
        Map<Long, Integer> merged = new HashMap<>();
        Map<Long, String> reasons = new HashMap<>();
        for (WalletDeltaLog d : batch) {
            long key = (((long) d.playerId()) << 32) | (d.currencyId() & 0xffffffffL);
            merged.merge(key, d.delta(), Integer::sum);
            reasons.putIfAbsent(key, d.reason());
        }
        int ok = 0;
        for (Map.Entry<Long, Integer> e : merged.entrySet()) {
            int playerId = (int) (e.getKey() >>> 32);
            int currencyId = (int) (e.getKey() & 0xffffffffL);
            int sum = e.getValue();
            if (flushMerged(playerId, currencyId, sum, reasons.getOrDefault(e.getKey(), "wal"))) {
                ok++;
            } else {
                // 失败回队尾，下次重试；内存缓存已是权威视图，DB 最终一致
                pending.offer(new WalletDeltaLog(seqGen.incrementAndGet(), playerId, currencyId, sum,
                        "wal_retry", System.currentTimeMillis()));
            }
        }
        return ok;
    }

    private boolean flushMerged(int playerId, int currencyId, int sum, String reason) {
        Boolean result = txTemplate.execute(status -> {
            for (int attempt = 1; attempt <= CAS_RETRIES; attempt++) {
                WalletRepository.CurrencyLockRow row = walletRepository.lockCurrency(playerId);
                if (row == null) {
                    return false;
                }
                Map<Integer, Integer> balance = new HashMap<>(row.balance());
                int current = balance.getOrDefault(currencyId, 0);
                long next = (long) current + sum;
                if (sum < 0 && current < -sum && next < 0) {
                    ConcurrentHashMap<Integer, Integer> mem = cache.get(playerId);
                    if (mem != null) {
                        balance.put(currencyId, mem.getOrDefault(currencyId, Math.max(0, (int) next)));
                    } else {
                        return false;
                    }
                } else {
                    if (next > Integer.MAX_VALUE) {
                        return false;
                    }
                    balance.put(currencyId, (int) Math.max(0, next));
                }
                int after = balance.getOrDefault(currencyId, 0);
                boolean updated = walletRepository.updateCurrencyOptimistic(playerId, balance, row.dataVersion());
                if (updated) {
                    walletRepository.insertLedger(playerId, currencyId, sum, reason, current, after,
                            "wal-" + playerId + "-" + System.nanoTime());
                    return true;
                }
            }
            return false;
        });
        return Boolean.TRUE.equals(result);
    }

    private void appendWalFile(WalletDeltaLog entry) {
        Path file = walDir.resolve("wallet-" + java.time.LocalDate.now() + ".log");
        String line = entry.seq() + "|" + entry.playerId() + "|" + entry.currencyId() + "|"
                + entry.delta() + "|" + entry.reason().replace('|', '/') + "|" + entry.createdAtMs() + "\n";
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            w.write(line);
        } catch (Exception e) {
            log.debug("wallet wal file append skipped: {}", e.toString());
        }
    }

    public int pendingSize() {
        return pending.size();
    }
}
