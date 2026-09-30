package cn.itcast.demo.mylunarcore.common;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.model.ActivityConfig;
import cn.itcast.demo.mylunarcore.ops.ServerClockService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 统一活动配置加载：{@code ActivityConfigs.json} 与 {@code activities/activity.{id}.json}。
 */
@Component
public class ActivityConfigService {

    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_ACTIVITY, ActivityConfigService.class);

    private final LunarCoreProperties properties;
    private final ResourceLoader resourceLoader;
    private final ConfigFileService configFileService;
    private final ServerClockService serverClockService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile Map<Integer, ActivityConfig> configsById = Collections.emptyMap();

    public ActivityConfigService(LunarCoreProperties properties,
                                 ResourceLoader resourceLoader,
                                 ConfigFileService configFileService,
                                 ServerClockService serverClockService) {
        this.properties = properties;
        this.resourceLoader = resourceLoader;
        this.configFileService = configFileService;
        this.serverClockService = serverClockService;
    }

    @PostConstruct
    public void loadOnStartup() {
        reload();
    }

    public void reload() {
        Map<Integer, ActivityConfig> next = new LinkedHashMap<>();
        loadArrayResource(next);
        loadActivityFiles(next);
        this.configsById = Collections.unmodifiableMap(next);
        log.info("Loaded {} unified activity configs", configsById.size());
    }

    public void restore(Map<Integer, ActivityConfig> previous) {
        this.configsById = previous == null ? Collections.emptyMap() : previous;
    }

    public Optional<ActivityConfig> findById(int activityId) {
        return Optional.ofNullable(configsById.get(activityId));
    }

    public Map<Integer, ActivityConfig> snapshot() {
        return configsById;
    }

    public boolean isActivityActive(int activityId) {
        return isActivityActive(activityId, 0);
    }

    public boolean isActivityActive(int activityId, int playerLevel) {
        ActivityConfig config = configsById.get(activityId);
        if (config != null) {
            long nowSec = serverClockService.nowEpochSecond();
            ConfigActiveWindow window = ConfigActiveWindow.of(
                    config.getBeginTime(), config.getEndTime(),
                    config.getDisplayStart(), config.getEffectStart(),
                    config.getDisplayEnd(), config.getEffectEnd());
            if (!window.isEffectActive(nowSec)) {
                return false;
            }
            return meetsConditions(config, playerLevel);
        }
        return false;
    }

    /** 是否处于展示窗（可下发入口，未必可交互）。 */
    public boolean isActivityDisplayable(int activityId) {
        ActivityConfig config = configsById.get(activityId);
        if (config == null) {
            return false;
        }
        ConfigActiveWindow window = ConfigActiveWindow.of(
                config.getBeginTime(), config.getEndTime(),
                config.getDisplayStart(), config.getEffectStart(),
                config.getDisplayEnd(), config.getEffectEnd());
        return window.isDisplayable(serverClockService.nowEpochSecond());
    }

    private boolean meetsConditions(ActivityConfig config, int playerLevel) {
        if (config.getConditions() != null && !config.getConditions().isEmpty()) {
            for (ActivityConfig.ActivityCondition condition : config.getConditions()) {
                if (!checkCondition(condition, playerLevel)) {
                    return false;
                }
            }
            return true;
        }
        return playerLevel >= config.getUnlockLevel();
    }

    private boolean checkCondition(ActivityConfig.ActivityCondition condition, int playerLevel) {
        if (condition == null || condition.getType() == null) {
            return true;
        }
        return switch (condition.getType().toLowerCase()) {
            case "level" -> playerLevel >= condition.getIntValue();
            case "min_level" -> playerLevel >= condition.getIntValue();
            case "max_level" -> playerLevel <= condition.getIntValue();
            default -> true;
        };
    }

    private void loadArrayResource(Map<Integer, ActivityConfig> target) {
        String loc = properties.getActivityConfigsResource();
        try {
            Resource resource = resourceLoader.getResource(loc);
            if (!resource.exists()) {
                return;
            }
            try (InputStream in = resource.getInputStream()) {
                List<ActivityConfig> list = objectMapper.readValue(in, new TypeReference<>() {});
                if (list != null) {
                    for (ActivityConfig config : list) {
                        target.put(config.getActivityId(), config);
                    }
                }
            }
        } catch (Exception e) {
            log.error("Failed to load activity configs from {}", loc, e);
        }
    }

    private void loadActivityFiles(Map<Integer, ActivityConfig> target) {
        Path dir = configFileService.resolve(properties.getActivityDetailDir());
        if (!Files.isDirectory(dir)) {
            return;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().startsWith("activity.")
                            && p.getFileName().toString().endsWith(".json"))
                    .forEach(path -> loadSingleFile(path, target));
        } catch (Exception e) {
            log.error("Failed to scan activity detail dir {}", dir, e);
        }
    }

    private void loadSingleFile(Path path, Map<Integer, ActivityConfig> target) {
        try {
            ActivityConfig config = objectMapper.readValue(path.toFile(), ActivityConfig.class);
            if (config != null && config.getActivityId() > 0) {
                target.put(config.getActivityId(), config);
            }
        } catch (Exception e) {
            log.error("Failed to load activity config file {}", path, e);
        }
    }
}
