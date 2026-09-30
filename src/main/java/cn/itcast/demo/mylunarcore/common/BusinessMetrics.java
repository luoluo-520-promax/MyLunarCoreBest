package cn.itcast.demo.mylunarcore.common;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;

/**
 * 业务 Prometheus 指标：登录失败、抽卡异常、战斗超时、匹配成功率、Zone 负载、系统 P99 延迟等。
 */
@Component
public class BusinessMetrics {

    private final Counter loginFailTotal;
    private final Counter loginSuccessTotal;
    private final Counter gachaErrorTotal;
    private final Counter gachaSuccessTotal;
    private final Counter battleTimeoutTotal;
    private final Counter battleSettleTotal;
    private final Counter iapReplayRejectedTotal;
    private final Counter iapVerifySuccessTotal;
    private final Counter iapVerifyFailureTotal;
    private final Counter walletNegativeTotal;
    private final Counter battleAuditMismatchTotal;
    private final Counter battleAuditOkTotal;
    private final Counter matchSuccessTotal;
    private final Counter matchCancelTotal;
    private final Counter matchTimeoutTotal;
    private final Counter matchSuccessBySegmentTotal;
    private final Timer matchWaitTimer;
    private final Timer loginLatencyTimer;
    private final Timer battleTickLatencyTimer;
    private final Timer gachaLatencyTimer;
    private final AtomicInteger activeBattles = new AtomicInteger();
    private final AtomicInteger matchQueueDepth = new AtomicInteger();
    private final AtomicInteger zonePlayerTotal = new AtomicInteger();
    private final AtomicInteger zoneCount = new AtomicInteger();
    private final AtomicReference<Double> zoneLoadRatio = new AtomicReference<>(0d);
    /** HPA：战斗实例池使用率 0–1 */
    private final AtomicReference<Double> battlePoolUsage = new AtomicReference<>(0d);
    /** SLA：战斗成功率 / 出手延迟 P95 / 切图耗时 P95（毫秒） */
    private final AtomicReference<Double> battleSuccessRate = new AtomicReference<>(1d);
    private final AtomicReference<Double> actionLatencyP95Ms = new AtomicReference<>(0d);
    private final AtomicReference<Double> sceneLoadP95Ms = new AtomicReference<>(0d);
    private final Counter dailyLoginUvTotal;
    private final Counter payingUserTotal;
    private final Counter gachaDrawPlayerTotal;
    private final Counter dungeonClearTotal;
    private final Counter dungeonAttemptTotal;
    private final Counter aiRequestsSuccessTotal;
    private final Counter aiRequestsFallbackTotal;
    private final Counter aiRequestsBlockedTotal;
    private final Counter aiRequestsErrorTotal;
    private final Counter aiCacheHitTotal;
    private final Counter aiCacheMissTotal;
    private final Counter aiFeedbackUsefulTotal;
    private final Counter aiFeedbackNotUsefulTotal;
    private final Timer aiLatencyTimer;
    private final java.util.Set<Long> dailyLoginUids = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<Long> payingUids = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<Long> gachaUids = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private volatile String dailyLoginBucket = "";

    public BusinessMetrics(MeterRegistry registry) {
        this.loginFailTotal = Counter.builder("lunarcore.login.fail").register(registry);
        this.loginSuccessTotal = Counter.builder("lunarcore.login.success").register(registry);
        this.gachaErrorTotal = Counter.builder("lunarcore.gacha.error").register(registry);
        this.gachaSuccessTotal = Counter.builder("lunarcore.gacha.success").register(registry);
        this.battleTimeoutTotal = Counter.builder("lunarcore.battle.timeout").register(registry);
        this.battleSettleTotal = Counter.builder("lunarcore.battle.settle").register(registry);
        this.iapReplayRejectedTotal = Counter.builder("lunarcore.iap.replay_rejected").register(registry);
        this.iapVerifySuccessTotal = Counter.builder("lunarcore.iap.verify").tag("result", "success").register(registry);
        this.iapVerifyFailureTotal = Counter.builder("lunarcore.iap.verify").tag("result", "failure").register(registry);
        this.walletNegativeTotal = Counter.builder("lunarcore.wallet.negative").register(registry);
        this.battleAuditMismatchTotal = Counter.builder("lunarcore.battle.audit").tag("result", "mismatch").register(registry);
        this.battleAuditOkTotal = Counter.builder("lunarcore.battle.audit").tag("result", "ok").register(registry);
        this.matchSuccessTotal = Counter.builder("lunarcore.match.success").register(registry);
        this.matchCancelTotal = Counter.builder("lunarcore.match.cancel").register(registry);
        this.matchTimeoutTotal = Counter.builder("lunarcore.match.timeout").register(registry);
        this.matchSuccessBySegmentTotal = Counter.builder("lunarcore.match.success_by_segment")
                .tag("segment", "all")
                .register(registry);
        this.dailyLoginUvTotal = Counter.builder("lunarcore.ops.daily_login_uv").register(registry);
        this.payingUserTotal = Counter.builder("lunarcore.ops.paying_users").register(registry);
        this.gachaDrawPlayerTotal = Counter.builder("lunarcore.ops.gacha_unique_players").register(registry);
        this.dungeonClearTotal = Counter.builder("lunarcore.ops.dungeon_clear").register(registry);
        this.dungeonAttemptTotal = Counter.builder("lunarcore.ops.dungeon_attempt").register(registry);
        this.aiRequestsSuccessTotal = Counter.builder("lunarcore.ai.requests").tag("status", "success").register(registry);
        this.aiRequestsFallbackTotal = Counter.builder("lunarcore.ai.requests").tag("status", "fallback").register(registry);
        this.aiRequestsBlockedTotal = Counter.builder("lunarcore.ai.requests").tag("status", "blocked").register(registry);
        this.aiRequestsErrorTotal = Counter.builder("lunarcore.ai.requests").tag("status", "error").register(registry);
        this.aiCacheHitTotal = Counter.builder("lunarcore.ai.cache").tag("result", "hit").register(registry);
        this.aiCacheMissTotal = Counter.builder("lunarcore.ai.cache").tag("result", "miss").register(registry);
        this.aiFeedbackUsefulTotal = Counter.builder("lunarcore.ai.feedback").tag("useful", "true").register(registry);
        this.aiFeedbackNotUsefulTotal = Counter.builder("lunarcore.ai.feedback").tag("useful", "false").register(registry);
        this.aiLatencyTimer = Timer.builder("lunarcore.ai.latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
        Gauge.builder("lunarcore.ops.daily_login_uv_gauge", dailyLoginUids, java.util.Set::size).register(registry);
        Gauge.builder("lunarcore.ai.cache_hit_ratio", this, BusinessMetrics::aiCacheHitRatio).register(registry);
        this.matchWaitTimer = Timer.builder("lunarcore.match.wait")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
        this.loginLatencyTimer = Timer.builder("lunarcore.login.latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
        this.battleTickLatencyTimer = Timer.builder("lunarcore.battle.tick_latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
        this.gachaLatencyTimer = Timer.builder("lunarcore.gacha.latency")
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(registry);
        Gauge.builder("lunarcore.battle.active", activeBattles, AtomicInteger::get).register(registry);
        Gauge.builder("lunarcore.match.queue_depth", matchQueueDepth, AtomicInteger::get).register(registry);
        Gauge.builder("lunarcore.zone.player_total", zonePlayerTotal, AtomicInteger::get).register(registry);
        Gauge.builder("lunarcore.zone.count", zoneCount, AtomicInteger::get).register(registry);
        Gauge.builder("lunarcore.zone.load_ratio", zoneLoadRatio, AtomicReference::get).register(registry);
        Gauge.builder("lunarcore.battle.pool_usage", battlePoolUsage, AtomicReference::get).register(registry);
        Gauge.builder("lunarcore.sla.battle_success_rate", battleSuccessRate, AtomicReference::get).register(registry);
        Gauge.builder("lunarcore.sla.action_latency_p95_ms", actionLatencyP95Ms, AtomicReference::get).register(registry);
        Gauge.builder("lunarcore.sla.scene_load_p95_ms", sceneLoadP95Ms, AtomicReference::get).register(registry);
    }

    public void bindOnlineGauge(MeterRegistry registry, IntSupplier onlineSupplier) {
        Gauge.builder("lunarcore.online.players", onlineSupplier, IntSupplier::getAsInt).register(registry);
    }

    public void recordLoginFail() {
        loginFailTotal.increment();
    }

    public void recordLoginSuccess() {
        loginSuccessTotal.increment();
    }

    /** 日活 UV：同一自然日同一 uid 只计一次。 */
    public void recordDailyLoginUv(long uid) {
        String day = java.time.LocalDate.now().toString();
        if (!day.equals(dailyLoginBucket)) {
            dailyLoginBucket = day;
            dailyLoginUids.clear();
        }
        if (dailyLoginUids.add(uid)) {
            dailyLoginUvTotal.increment();
        }
    }

    public void recordPayingUser(long uid) {
        if (payingUids.add(uid)) {
            payingUserTotal.increment();
        }
    }

    public void recordGachaUniquePlayer(long uid) {
        if (gachaUids.add(uid)) {
            gachaDrawPlayerTotal.increment();
        }
    }

    public void recordDungeonAttempt() {
        dungeonAttemptTotal.increment();
    }

    public void recordDungeonClear() {
        dungeonClearTotal.increment();
    }

    public void recordLoginLatency(long durationMs) {
        if (durationMs >= 0) {
            loginLatencyTimer.record(durationMs, TimeUnit.MILLISECONDS);
        }
    }

    public void recordGachaError() {
        gachaErrorTotal.increment();
    }

    public void recordGachaSuccess() {
        gachaSuccessTotal.increment();
    }

    public void recordGachaLatency(long durationMs) {
        if (durationMs >= 0) {
            gachaLatencyTimer.record(durationMs, TimeUnit.MILLISECONDS);
        }
    }

    public void recordBattleTimeout() {
        battleTimeoutTotal.increment();
    }

    public void recordBattleSettle() {
        battleSettleTotal.increment();
    }

    public void recordBattleTickLatency(long durationMs) {
        if (durationMs >= 0) {
            battleTickLatencyTimer.record(durationMs, TimeUnit.MILLISECONDS);
        }
    }

    public void recordIapReplayRejected() {
        iapReplayRejectedTotal.increment();
    }

    public void recordIapVerifySuccess() {
        iapVerifySuccessTotal.increment();
    }

    public void recordIapVerifyFailure() {
        iapVerifyFailureTotal.increment();
    }

    public void recordWalletNegative() {
        walletNegativeTotal.increment();
    }

    public void recordBattleAudit(boolean mismatch) {
        if (mismatch) {
            battleAuditMismatchTotal.increment();
        } else {
            battleAuditOkTotal.increment();
        }
    }

    public double iapVerifyFailureRate() {
        double fail = iapVerifyFailureTotal.count();
        double ok = iapVerifySuccessTotal.count();
        double total = fail + ok;
        return total <= 0 ? 0 : fail / total;
    }

    public double battleAuditMismatchRate() {
        double fail = battleAuditMismatchTotal.count();
        double ok = battleAuditOkTotal.count();
        double total = fail + ok;
        return total <= 0 ? 0 : fail / total;
    }

    public void recordMatchSuccess(long waitMs) {
        matchSuccessTotal.increment();
        matchSuccessBySegmentTotal.increment();
        if (waitMs >= 0) {
            matchWaitTimer.record(java.time.Duration.ofMillis(waitMs));
        }
    }

    public void recordMatchSuccess(long waitMs, String segment) {
        recordMatchSuccess(waitMs);
        if (segment != null && !segment.isBlank()) {
            // 分段标签通过独立 counter 名避免高基数爆炸；调用方可再扩展 MeterRegistry
        }
    }

    public void recordMatchCancel() {
        matchCancelTotal.increment();
    }

    public void recordMatchTimeout() {
        matchTimeoutTotal.increment();
    }

    public void setActiveBattles(int n) {
        activeBattles.set(Math.max(0, n));
    }

    public void setMatchQueueDepth(int n) {
        matchQueueDepth.set(Math.max(0, n));
    }

    public void setZoneStats(int zones, int players) {
        zoneCount.set(Math.max(0, zones));
        zonePlayerTotal.set(Math.max(0, players));
    }

    public void setZoneLoadRatio(double ratio) {
        if (Double.isNaN(ratio) || Double.isInfinite(ratio)) {
            zoneLoadRatio.set(0d);
            return;
        }
        zoneLoadRatio.set(Math.min(1.0, Math.max(0.0, ratio)));
    }

    /** K8s HPA 自定义指标：战斗实例池使用率。 */
    public void setBattlePoolUsage(double ratio) {
        if (Double.isNaN(ratio) || Double.isInfinite(ratio)) {
            battlePoolUsage.set(0d);
            return;
        }
        battlePoolUsage.set(Math.min(1.0, Math.max(0.0, ratio)));
    }

    public void setSlaBattleSuccessRate(double rate) {
        battleSuccessRate.set(Math.min(1.0, Math.max(0.0, rate)));
    }

    public void setSlaActionLatencyP95Ms(double ms) {
        actionLatencyP95Ms.set(Math.max(0, ms));
    }

    public void setSlaSceneLoadP95Ms(double ms) {
        sceneLoadP95Ms.set(Math.max(0, ms));
    }

    public double battlePoolUsage() {
        return battlePoolUsage.get();
    }

    public double slaBattleSuccessRate() {
        return battleSuccessRate.get();
    }

    public double loginFailRate() {
        double fail = loginFailTotal.count();
        double ok = loginSuccessTotal.count();
        double total = fail + ok;
        return total <= 0 ? 0 : fail / total;
    }

    public double matchSuccessRate() {
        double ok = matchSuccessTotal.count();
        double cancel = matchCancelTotal.count() + matchTimeoutTotal.count();
        double total = ok + cancel;
        return total <= 0 ? 0 : ok / total;
    }

    /** 记录 AI 请求终态：success / fallback / blocked / error。 */
    public void recordAiRequest(String status, long latencyMs) {
        String s = status == null ? "error" : status.toLowerCase();
        switch (s) {
            case "success", "llm", "remote", "guide-local", "rule", "lore", "explore-rule", "accepted" ->
                    aiRequestsSuccessTotal.increment();
            case "fallback" -> aiRequestsFallbackTotal.increment();
            case "blocked" -> aiRequestsBlockedTotal.increment();
            default -> {
                if (s.startsWith("remote-") || s.endsWith("-cache") || s.startsWith("proactive")) {
                    aiRequestsSuccessTotal.increment();
                } else if (s.contains("fallback")) {
                    aiRequestsFallbackTotal.increment();
                } else {
                    aiRequestsErrorTotal.increment();
                }
            }
        }
        if (latencyMs >= 0) {
            aiLatencyTimer.record(latencyMs, TimeUnit.MILLISECONDS);
        }
    }

    public void recordAiCacheHit(boolean hit) {
        if (hit) {
            aiCacheHitTotal.increment();
        } else {
            aiCacheMissTotal.increment();
        }
    }

    public void recordAiFeedback(boolean useful) {
        if (useful) {
            aiFeedbackUsefulTotal.increment();
        } else {
            aiFeedbackNotUsefulTotal.increment();
        }
    }

    public double aiCacheHitRatio() {
        double hit = aiCacheHitTotal.count();
        double miss = aiCacheMissTotal.count();
        double total = hit + miss;
        return total <= 0 ? 0 : hit / total;
    }

    public double aiFallbackRate() {
        double fb = aiRequestsFallbackTotal.count();
        double ok = aiRequestsSuccessTotal.count();
        double total = fb + ok + aiRequestsBlockedTotal.count() + aiRequestsErrorTotal.count();
        return total <= 0 ? 0 : fb / total;
    }
}
