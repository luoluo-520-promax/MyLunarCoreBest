package cn.itcast.demo.mylunarcore.ops;

import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 服务端逻辑时钟：支持 Time Travel，供内部机预演未来版本配置生效窗。
 * 生产默认跟随系统时钟；运维可偏移到指定 Instant。
 */
@Service
public class ServerClockService {

    private final AtomicReference<Clock> clock = new AtomicReference<>(Clock.systemUTC());
    private volatile Instant travelTarget;
    private volatile String operator = "";

    public Instant now() {
        return Instant.now(clock.get());
    }

    public long nowEpochSecond() {
        return now().getEpochSecond();
    }

    public Clock clock() {
        return clock.get();
    }

    /** 将逻辑时钟固定到 target（内部测试机）。传 null 则恢复系统时钟。 */
    public synchronized Map<String, Object> travelTo(Instant target, String op) {
        this.operator = op == null ? "" : op;
        if (target == null) {
            clock.set(Clock.systemUTC());
            travelTarget = null;
        } else {
            travelTarget = target;
            Duration offset = Duration.between(Instant.now(), target);
            clock.set(Clock.offset(Clock.systemUTC(), offset));
        }
        return status();
    }

    public synchronized Map<String, Object> reset() {
        return travelTo(null, operator);
    }

    public boolean isTraveling() {
        return travelTarget != null;
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("traveling", isTraveling());
        m.put("now", now().toString());
        m.put("nowEpochSecond", nowEpochSecond());
        m.put("travelTarget", travelTarget == null ? "" : travelTarget.toString());
        m.put("zone", ZoneId.of("UTC").toString());
        m.put("operator", operator);
        return m;
    }
}
