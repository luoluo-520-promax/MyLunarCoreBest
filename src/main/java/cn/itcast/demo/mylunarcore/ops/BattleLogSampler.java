package cn.itcast.demo.mylunarcore.ops;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 战斗详细日志采样：生产默认只对白名单 UID 打 DEBUG；其余按采样率丢弃。
 */
@Component
public class BattleLogSampler {

    private final LunarCoreProperties properties;
    private final Set<Long> allowUids = ConcurrentHashMap.newKeySet();
    private final AtomicLong dropped = new AtomicLong();
    private final AtomicLong allowed = new AtomicLong();

    public BattleLogSampler(LunarCoreProperties properties) {
        this.properties = properties;
        refreshAllowlist();
    }

    public void refreshAllowlist() {
        allowUids.clear();
        String raw = properties.getLogSampling().getBattleDebugUidAllowlist();
        if (raw == null || raw.isBlank()) {
            return;
        }
        for (String part : raw.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            try {
                allowUids.add(Long.parseLong(p));
            } catch (NumberFormatException ignored) {
            }
        }
    }

    /** 是否允许输出战斗 DEBUG 细节。 */
    public boolean allowBattleDebug(long uid) {
        if (!properties.getLogSampling().isEnabled()) {
            allowed.incrementAndGet();
            return true;
        }
        if (allowUids.contains(uid)) {
            allowed.incrementAndGet();
            return true;
        }
        double rate = properties.getLogSampling().getBattleDebugSampleRate();
        if (rate >= 1.0) {
            allowed.incrementAndGet();
            return true;
        }
        if (rate <= 0.0) {
            dropped.incrementAndGet();
            return false;
        }
        boolean ok = ThreadLocalRandom.current().nextDouble() < rate;
        if (ok) {
            allowed.incrementAndGet();
        } else {
            dropped.incrementAndGet();
        }
        return ok;
    }

    public long droppedCount() {
        return dropped.get();
    }

    public long allowedCount() {
        return allowed.get();
    }
}
