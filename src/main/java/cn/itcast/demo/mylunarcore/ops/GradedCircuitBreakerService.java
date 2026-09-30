package cn.itcast.demo.mylunarcore.ops;

import cn.itcast.demo.mylunarcore.activity.ActivityCircuitBreakerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 分级熔断：按活动 ID / 渠道 / 服务器分片独立熔断，并切换保底奖励邮件。
 * 兼容原有全局 {@link ActivityCircuitBreakerService}。
 */
@Service
public class GradedCircuitBreakerService {

    private static final Logger log = LoggerFactory.getLogger(GradedCircuitBreakerService.class);

    public enum Scope { ACTIVITY, CHANNEL, SHARD, GLOBAL }

    public record BreakerKey(Scope scope, String id) {}

    public record BreakerState(boolean tripped, String reason, Instant since, boolean mailFallback) {}

    private final Map<BreakerKey, BreakerState> states = new ConcurrentHashMap<>();
    private final ActivityCircuitBreakerService global;

    public GradedCircuitBreakerService(ActivityCircuitBreakerService global) {
        this.global = global;
    }

    public BreakerState trip(Scope scope, String id, String reason) {
        BreakerKey key = new BreakerKey(scope, id == null ? "*" : id);
        BreakerState st = new BreakerState(true, reason == null ? "ops" : reason, Instant.now(), true);
        states.put(key, st);
        if (scope == Scope.GLOBAL) {
            global.trip(reason);
        }
        log.error("graded_circuit_trip scope={} id={} reason={}", scope, id, reason);
        return st;
    }

    public BreakerState reset(Scope scope, String id) {
        BreakerKey key = new BreakerKey(scope, id == null ? "*" : id);
        BreakerState st = new BreakerState(false, "", null, false);
        states.put(key, st);
        if (scope == Scope.GLOBAL) {
            global.reset();
        }
        log.warn("graded_circuit_reset scope={} id={}", scope, id);
        return st;
    }

    /** 任一匹配维度熔断则阻断；优先细粒度。 */
    public boolean isBlocked(String activityId, String channel, String shardId) {
        if (global.isTripped()) {
            return true;
        }
        if (tripped(Scope.ACTIVITY, activityId) || tripped(Scope.CHANNEL, channel) || tripped(Scope.SHARD, shardId)) {
            return true;
        }
        return tripped(Scope.GLOBAL, "*");
    }

    public boolean shouldMailFallback(String activityId, String channel, String shardId) {
        if (global.shouldDegradeToMail()) {
            return true;
        }
        return mail(Scope.ACTIVITY, activityId) || mail(Scope.CHANNEL, channel) || mail(Scope.SHARD, shardId);
    }

    public Map<String, Object> statusSnapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("global", global.status());
        Map<String, Object> graded = new LinkedHashMap<>();
        for (Map.Entry<BreakerKey, BreakerState> e : states.entrySet()) {
            graded.put(e.getKey().scope() + ":" + e.getKey().id(), Map.of(
                    "tripped", e.getValue().tripped(),
                    "reason", e.getValue().reason(),
                    "since", e.getValue().since() == null ? "" : e.getValue().since().toString(),
                    "mailFallback", e.getValue().mailFallback()));
        }
        out.put("graded", graded);
        return out;
    }

    private boolean tripped(Scope scope, String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        BreakerState st = states.get(new BreakerKey(scope, id));
        return st != null && st.tripped();
    }

    private boolean mail(Scope scope, String id) {
        if (id == null || id.isBlank()) {
            return false;
        }
        BreakerState st = states.get(new BreakerKey(scope, id));
        return st != null && st.tripped() && st.mailFallback();
    }
}
