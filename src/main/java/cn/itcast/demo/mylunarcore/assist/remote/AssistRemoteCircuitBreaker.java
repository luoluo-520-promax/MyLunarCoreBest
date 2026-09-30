package cn.itcast.demo.mylunarcore.assist.remote;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 简易熔断器（无 Resilience4j 依赖）：连续失败打开，等待后半开探测。
 */
public final class AssistRemoteCircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final LunarCoreProperties properties;
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private final AtomicLong openUntilMs = new AtomicLong();
    private final AtomicInteger halfOpenInFlight = new AtomicInteger();
    private final AtomicLong openedCount = new AtomicLong();

    public AssistRemoteCircuitBreaker(LunarCoreProperties properties) {
        this.properties = properties;
    }

    public boolean allowRequest() {
        long now = System.currentTimeMillis();
        long until = openUntilMs.get();
        if (until > now) {
            return false;
        }
        if (until > 0 && until <= now) {
            // 进入半开：只允许一个探测
            if (halfOpenInFlight.compareAndSet(0, 1)) {
                return true;
            }
            return false;
        }
        return true;
    }

    public void onSuccess() {
        consecutiveFailures.set(0);
        openUntilMs.set(0);
        halfOpenInFlight.set(0);
    }

    public void onFailure() {
        halfOpenInFlight.set(0);
        int threshold = Math.max(2, properties.getAiAssist().getRemoteCircuitFailureThreshold());
        int fails = consecutiveFailures.incrementAndGet();
        if (fails >= threshold) {
            long openMs = Math.max(1000L, properties.getAiAssist().getRemoteCircuitOpenMs());
            openUntilMs.set(System.currentTimeMillis() + openMs);
            consecutiveFailures.set(0);
            openedCount.incrementAndGet();
        }
    }

    public State state() {
        long until = openUntilMs.get();
        long now = System.currentTimeMillis();
        if (until > now) {
            return State.OPEN;
        }
        if (until > 0) {
            return State.HALF_OPEN;
        }
        return State.CLOSED;
    }

    public long getOpenedCount() {
        return openedCount.get();
    }
}
