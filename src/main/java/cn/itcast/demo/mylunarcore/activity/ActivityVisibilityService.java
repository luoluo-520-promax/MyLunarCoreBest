package cn.itcast.demo.mylunarcore.activity;

import cn.itcast.demo.mylunarcore.common.AppLogger;
import cn.itcast.demo.mylunarcore.common.ConfigActiveWindow;
import cn.itcast.demo.mylunarcore.common.LogCategory;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.model.ActivityConfig;
import cn.itcast.demo.mylunarcore.ops.ServerClockService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 运营可见性掩码：QA 隐身账号可看到未正式开启的活动，操作不记入正式排行榜。
 */
@Service
public class ActivityVisibilityService {

    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_ACTIVITY, ActivityVisibilityService.class);

    private final ServerClockService clockService;
    private final Path qaFile;
    private final Set<Long> qaUids = ConcurrentHashMap.newKeySet();

    public ActivityVisibilityService(ServerClockService clockService, LunarCoreProperties properties) {
        this.clockService = clockService;
        this.qaFile = Path.of(properties.getDataDir(), "QaTesterUids.json");
    }

    @PostConstruct
    public void reload() {
        qaUids.clear();
        if (!Files.isRegularFile(qaFile)) {
            return;
        }
        try {
            String raw = Files.readString(qaFile, StandardCharsets.UTF_8).trim();
            // 支持简单 JSON 数组 [1,2,3] 或换行分隔
            if (raw.startsWith("[")) {
                raw = raw.substring(1, raw.lastIndexOf(']'));
                for (String part : raw.split(",")) {
                    String t = part.trim().replace("\"", "");
                    if (!t.isEmpty()) {
                        qaUids.add(Long.parseLong(t));
                    }
                }
            } else {
                for (String line : raw.split("\\R")) {
                    String t = line.trim();
                    if (!t.isEmpty() && !t.startsWith("#")) {
                        qaUids.add(Long.parseLong(t));
                    }
                }
            }
            log.info("QA tester uids loaded count={}", qaUids.size());
        } catch (Exception e) {
            log.warn("load QaTesterUids failed: {}", e.toString());
        }
    }

    public boolean isQaTester(long uid) {
        return qaUids.contains(uid);
    }

    public void markQa(long uid, boolean qa) {
        if (qa) {
            qaUids.add(uid);
        } else {
            qaUids.remove(uid);
        }
    }

    public Set<Long> snapshotQaUids() {
        return Collections.unmodifiableSet(new HashSet<>(qaUids));
    }

    /**
     * 普通玩家：仅展示 display 窗内活动；QA：可看预加载/未生效配置。
     */
    public boolean canSee(ActivityConfig config, boolean qaTester) {
        if (config == null) {
            return false;
        }
        ConfigActiveWindow window = windowOf(config);
        long now = clockService.nowEpochSecond();
        if (qaTester) {
            // QA 可看：已加载且未过 displayEnd（含未来预演）
            return now <= window.displayEnd() || window.displayEnd() <= 0;
        }
        return window.isDisplayable(now);
    }

    public boolean canInteract(ActivityConfig config, boolean qaTester) {
        if (config == null) {
            return false;
        }
        ConfigActiveWindow window = windowOf(config);
        long now = clockService.nowEpochSecond();
        if (qaTester) {
            return true;
        }
        return window.isEffectActive(now);
    }

    /** QA 操作不进正式排行榜。 */
    public boolean excludeFromLeaderboard(boolean qaTester) {
        return qaTester;
    }

    public ConfigActiveWindow windowOf(ActivityConfig config) {
        return ConfigActiveWindow.of(
                config.getBeginTime(), config.getEndTime(),
                config.getDisplayStart(), config.getEffectStart(),
                config.getDisplayEnd(), config.getEffectEnd());
    }

    public Map<String, Object> status() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("qaCount", qaUids.size());
        m.put("qaUids", qaUids.stream().sorted().map(String::valueOf).collect(Collectors.toList()));
        m.put("clock", clockService.status());
        return m;
    }
}
