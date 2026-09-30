package cn.itcast.demo.mylunarcore.party;

import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 组队迁移超时与降级：最大超时 5s 自动回滚；失败率超阈值拒绝新迁移，控制在 1% 以下。
 */
@Service
public class MigrateFallbackService {

    private static final Logger log = LoggerFactory.getLogger(MigrateFallbackService.class);

    public static final long DEFAULT_TIMEOUT_MS = 5_000L;
    /** 滑动窗口内失败率阈值：超过则拒绝新迁移 */
    public static final double FAIL_RATE_THRESHOLD = 0.01;
    public static final int MIN_SAMPLES = 50;

    public record AdmitResult(boolean admitted, String reason) {
        public static AdmitResult ok() {
            return new AdmitResult(true, "");
        }

        public static AdmitResult reject(String reason) {
            return new AdmitResult(false, reason == null ? "rejected" : reason);
        }
    }

    private final PartyMigrateSagaService saga;
    private final GameSessionManager sessionManager;
    private final AtomicLong totalAttempts = new AtomicLong();
    private final AtomicLong totalFailures = new AtomicLong();
    private final AtomicInteger openCircuits = new AtomicInteger();
    private final Map<String, ScheduledFuture<?>> timeouts = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "party-migrate-timeout");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean circuitOpen;

    public MigrateFallbackService(ObjectProvider<PartyMigrateSagaService> sagaProvider,
                                  ObjectProvider<GameSessionManager> sessionProvider) {
        this.saga = sagaProvider == null ? null : sagaProvider.getIfAvailable();
        this.sessionManager = sessionProvider == null ? null : sessionProvider.getIfAvailable();
    }

    public AdmitResult tryAdmitNewMigrate() {
        maybeRefreshCircuit();
        if (circuitOpen) {
            openCircuits.incrementAndGet();
            return AdmitResult.reject("migrate_circuit_open");
        }
        return AdmitResult.ok();
    }

    /** 开启超时守护：超时后自动 rollback 并通知客户端重试。 */
    public void armTimeout(String txnId, long memberUid) {
        armTimeout(txnId, memberUid, DEFAULT_TIMEOUT_MS);
    }

    public void armTimeout(String txnId, long memberUid, long timeoutMs) {
        if (txnId == null || txnId.isBlank() || saga == null) {
            return;
        }
        cancelTimeout(txnId);
        long wait = Math.max(500L, timeoutMs);
        ScheduledFuture<?> future = scheduler.schedule(() -> onTimeout(txnId, memberUid), wait, TimeUnit.MILLISECONDS);
        timeouts.put(txnId, future);
        totalAttempts.incrementAndGet();
    }

    public void markSuccess(String txnId) {
        cancelTimeout(txnId);
        maybeRefreshCircuit();
    }

    public void markFailure(String txnId) {
        cancelTimeout(txnId);
        totalFailures.incrementAndGet();
        maybeRefreshCircuit();
    }

    private void onTimeout(String txnId, long memberUid) {
        timeouts.remove(txnId);
        if (saga == null) {
            return;
        }
        PartyMigrateSagaService.MigrateTxn rolled = saga.rollback(txnId, "migrate_timeout");
        totalFailures.incrementAndGet();
        maybeRefreshCircuit();
        notifyClientRetry(memberUid, txnId, rolled == null ? 0 : percentOf(rolled));
        log.warn("party_migrate_timeout txn={} member={}", txnId, memberUid);
    }

    private void notifyClientRetry(long uid, String txnId, int percent) {
        if (sessionManager == null || uid <= 0) {
            return;
        }
        GameSession s = sessionManager.getOrNull(uid);
        if (s == null) {
            return;
        }
        String json = "{\"txnId\":\"" + txnId + "\",\"state\":\"TIMEOUT\",\"percent\":" + percent
                + ",\"phase\":\"timeout_rollback\",\"action\":\"retry\",\"hint\":\"正在迁移队伍数据超时，请重试\"}";
        s.send(new GamePacket(CmdIds.PARTY_MIGRATE_PROGRESS_SC_NOTIFY, json.getBytes(StandardCharsets.UTF_8)));
    }

    private static int percentOf(PartyMigrateSagaService.MigrateTxn txn) {
        return switch (txn.state()) {
            case SERIALIZING -> 25;
            case TRANSFERRING -> 60;
            case CONFIRMED -> 100;
            default -> 0;
        };
    }

    private void maybeRefreshCircuit() {
        long attempts = totalAttempts.get();
        if (attempts < MIN_SAMPLES) {
            circuitOpen = false;
            return;
        }
        double rate = totalFailures.get() * 1.0 / attempts;
        circuitOpen = rate > FAIL_RATE_THRESHOLD;
    }

    public void cancelTimeout(String txnId) {
        ScheduledFuture<?> f = timeouts.remove(txnId);
        if (f != null) {
            f.cancel(false);
        }
    }

    public double failureRate() {
        long a = totalAttempts.get();
        return a <= 0 ? 0 : totalFailures.get() * 1.0 / a;
    }

    public boolean isCircuitOpen() {
        return circuitOpen;
    }

    public Map<String, Object> snapshot() {
        return Map.of(
                "attempts", totalAttempts.get(),
                "failures", totalFailures.get(),
                "failureRate", failureRate(),
                "circuitOpen", circuitOpen,
                "openCircuitRejects", openCircuits.get(),
                "armedTimeouts", timeouts.size());
    }

    /** 测试用：重置滑动计数。 */
    public void resetCounters() {
        totalAttempts.set(0);
        totalFailures.set(0);
        openCircuits.set(0);
        circuitOpen = false;
    }
}
