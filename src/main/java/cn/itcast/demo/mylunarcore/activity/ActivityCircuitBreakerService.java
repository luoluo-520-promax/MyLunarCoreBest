package cn.itcast.demo.mylunarcore.activity;

import cn.itcast.demo.mylunarcore.common.AppLogger;
import cn.itcast.demo.mylunarcore.common.LogCategory;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 活动全局熔断：一键关闭限时玩法，奖励降级为「基础邮件发放」，优先保服。
 */
@Service
public class ActivityCircuitBreakerService {

    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_ACTIVITY, ActivityCircuitBreakerService.class);

    private final AtomicBoolean tripped = new AtomicBoolean(false);
    private final AtomicReference<String> reason = new AtomicReference<>("");
    private final AtomicReference<Instant> since = new AtomicReference<>(null);

    public boolean isTripped() {
        return tripped.get();
    }

    /** 熔断开启后：限时玩法不可交互，奖励走基础邮件。 */
    public boolean shouldDegradeToMail() {
        return tripped.get();
    }

    public boolean shouldBlockTimedPlay() {
        return tripped.get();
    }

    public synchronized Map<String, Object> trip(String reasonText) {
        tripped.set(true);
        reason.set(reasonText == null ? "ops_circuit" : reasonText);
        since.set(Instant.now());
        log.error("activity circuit BREAKER ON reason={}", reason.get());
        return status();
    }

    public synchronized Map<String, Object> reset() {
        tripped.set(false);
        reason.set("");
        since.set(null);
        log.warn("activity circuit BREAKER OFF");
        return status();
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tripped", tripped.get());
        m.put("reason", reason.get());
        m.put("since", since.get() == null ? "" : since.get().toString());
        m.put("rewardMode", tripped.get() ? "basic_mail" : "normal");
        m.put("timedPlayEnabled", !tripped.get());
        return m;
    }
}
