package cn.itcast.demo.mylunarcore.common;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 统一配置读取：按 {@link ConfigGrayRelease#inGray} 自动切换 canary/stable，
 * 并提供回滚影响分析（依赖 {@link ConfigReleaseService} 指纹表）。
 */
@Service
public class UnifiedConfigAccessor {

    private final ConfigGrayReader grayReader;
    private final ConfigReleaseService releaseService;

    public UnifiedConfigAccessor(ConfigGrayReader grayReader, ConfigReleaseService releaseService) {
        this.grayReader = grayReader;
        this.releaseService = releaseService;
    }

    /** 按玩家/区服灰度选择配置快照。 */
    public <T> T get(long uid, String serverId, T canary, T stable) {
        return grayReader.select(uid, serverId, canary, stable);
    }

    public <T> T get(long uid, String serverId, Supplier<T> canary, Supplier<T> stable) {
        return grayReader.select(uid, serverId, canary, stable);
    }

    public boolean inGray(long uid, String serverId) {
        return grayReader.useCanary(uid, serverId);
    }

    public ConfigGrayRelease.GrayPolicy grayPolicy() {
        return grayReader.policy();
    }

    /**
     * 回滚影响：对比目标 release 与最新 release 的 hash/版本差异，并列出同名配置的近期发布链。
     */
    public Map<String, Object> analyzeRollbackImpact(String configName, long targetReleaseId) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("configName", configName);
        out.put("targetReleaseId", targetReleaseId);
        List<Map<String, Object>> history = releaseService.list(configName, 20);
        out.put("recentReleases", history);
        if (history.isEmpty()) {
            out.put("ok", false);
            out.put("message", "no release history");
            return out;
        }
        Map<String, Object> latest = history.get(0);
        long latestId = toLong(latest.get("release_id"), toLong(latest.get("id"), 0L));
        out.put("latestReleaseId", latestId);
        Map<String, Object> diff = releaseService.diff(configName, targetReleaseId, latestId);
        out.put("diffVsLatest", diff);
        List<String> impacted = new ArrayList<>();
        impacted.add(configName);
        Object sameHash = diff.get("sameHash");
        if (Boolean.FALSE.equals(sameHash)) {
            impacted.add("clients_reading_" + configName);
            if (Boolean.TRUE.equals(grayReader.policy().enabled())) {
                impacted.add("gray_cohort_may_still_see_canary");
            }
        }
        out.put("impactedConfigKeys", impacted);
        out.put("ok", true);
        return out;
    }

    private static long toLong(Object v, long def) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        if (v != null) {
            try {
                return Long.parseLong(String.valueOf(v));
            } catch (NumberFormatException ignored) {
            }
        }
        return def;
    }
}
