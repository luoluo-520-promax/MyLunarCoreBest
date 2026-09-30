package cn.itcast.demo.mylunarcore.party;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 组队迁移 Saga 增强：重试队列 + DeadLetter；配合混沌注入验证补偿完整性。
 */
@Service
public class PartyMigrateSagaDeadLetterService {

    private static final Logger log = LoggerFactory.getLogger(PartyMigrateSagaDeadLetterService.class);
    public static final int MAX_RETRY = 3;

    public record DeadLetter(String txnId, String reason, int attempts, long enqueuedAtMs) {}

    public record RetryItem(String txnId, MigrateState target, String ticketOrHint, int attempts) {}

    private final PartyMigrateSagaService saga;
    private final Deque<RetryItem> retryQueue = new ArrayDeque<>();
    private final Map<String, DeadLetter> deadLetters = new ConcurrentHashMap<>();
    private final AtomicInteger chaosInjectCount = new AtomicInteger();
    /** 混沌：下一次 advance 强制失败 */
    private volatile boolean chaosForceFailNext;

    public PartyMigrateSagaDeadLetterService(ObjectProvider<PartyMigrateSagaService> sagaProvider) {
        this.saga = sagaProvider == null ? null : sagaProvider.getIfAvailable();
    }

    public PartyMigrateSagaDeadLetterService(PartyMigrateSagaService saga) {
        this.saga = saga;
    }

    public void enqueueRetry(String txnId, MigrateState target, String ticketOrHint, int attempts) {
        retryQueue.addLast(new RetryItem(txnId, target, ticketOrHint, attempts));
    }

    public void markDeadLetter(String txnId, String reason, int attempts) {
        deadLetters.put(txnId, new DeadLetter(txnId, reason == null ? "unknown" : reason,
                attempts, System.currentTimeMillis()));
        log.error("party_migrate_dead_letter txn={} reason={} attempts={}", txnId, reason, attempts);
    }

    /**
     * 带重试的推进：失败入队；超过 {@link #MAX_RETRY} 进 DeadLetter 并 rollback。
     */
    public PartyMigrateSagaService.MigrateTxn advanceWithRetry(String txnId, MigrateState next, String ticketOrHint) {
        if (saga == null) {
            return null;
        }
        if (chaosForceFailNext) {
            chaosForceFailNext = false;
            chaosInjectCount.incrementAndGet();
            enqueueRetry(txnId, next, ticketOrHint, 1);
            return saga.rollback(txnId, "chaos_injected_fail");
        }
        PartyMigrateSagaService.MigrateTxn result = saga.advance(txnId, next, ticketOrHint);
        if (result == null || result.state() == MigrateState.ROLLBACK) {
            enqueueRetry(txnId, next, ticketOrHint, 1);
        }
        return result;
    }

    @Scheduled(fixedDelay = 5_000L)
    public void drainRetryQueue() {
        if (saga == null) {
            return;
        }
        RetryItem item;
        while ((item = retryQueue.pollFirst()) != null) {
            if (item.attempts() > MAX_RETRY) {
                markDeadLetter(item.txnId(), "max_retry_exceeded", item.attempts());
                saga.rollback(item.txnId(), "dead_letter");
                continue;
            }
            PartyMigrateSagaService.MigrateTxn r = saga.advance(item.txnId(), item.target(), item.ticketOrHint());
            if (r == null || r.state() == MigrateState.ROLLBACK) {
                enqueueRetry(item.txnId(), item.target(), item.ticketOrHint(), item.attempts() + 1);
            }
        }
    }

    /** 混沌：模拟节点宕机 / 网络分区 —— 下次 advance 失败。 */
    public void injectChaosFailNext() {
        chaosForceFailNext = true;
    }

    /** 混沌：对进行中事务直接 rollback，验证补偿。 */
    public PartyMigrateSagaService.MigrateTxn chaosKillTxn(String txnId, String reason) {
        if (saga == null) {
            return null;
        }
        chaosInjectCount.incrementAndGet();
        return saga.rollback(txnId, reason == null ? "chaos_kill" : reason);
    }

    public List<DeadLetter> listDeadLetters() {
        return new ArrayList<>(deadLetters.values());
    }

    public int chaosInjectCount() {
        return chaosInjectCount.get();
    }

    public int retryQueueSize() {
        return retryQueue.size();
    }
}
