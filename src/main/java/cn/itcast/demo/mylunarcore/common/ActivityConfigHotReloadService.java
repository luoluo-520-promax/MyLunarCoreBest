package cn.itcast.demo.mylunarcore.common;

import cn.itcast.demo.mylunarcore.assist.RagKnowledgeService;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 监听 ActivityConfigs.json 变更，重载活动配置并向在线玩家推送 ACTIVITY_CONFIG_UPDATE_SC_NOTIFY。
 */
@Component
public class ActivityConfigHotReloadService {

    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_ACTIVITY, ActivityConfigHotReloadService.class);

    private final ActivityConfigService activityConfigService;
    private final UpdateNotifyBroadcaster updateNotifyBroadcaster;
    private final LunarCoreProperties properties;
    private final RagKnowledgeService ragKnowledgeService;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private volatile long lastSeenModifiedMillis = -1L;

    public ActivityConfigHotReloadService(ActivityConfigService activityConfigService,
                                          UpdateNotifyBroadcaster updateNotifyBroadcaster,
                                          LunarCoreProperties properties,
                                          RagKnowledgeService ragKnowledgeService) {
        this.activityConfigService = activityConfigService;
        this.updateNotifyBroadcaster = updateNotifyBroadcaster;
        this.properties = properties;
        this.ragKnowledgeService = ragKnowledgeService;
    }

    @PostConstruct
    public void start() {
        File file = resolveActivityConfigsFile();
        lastSeenModifiedMillis = safeLastModified(file);
        if (file.isFile()) {
            scheduler.scheduleAtFixedRate(this::tick, 2, 2, TimeUnit.SECONDS);
            log.info("Activity config hot-reload enabled, path={}", file.getPath());
        } else {
            log.info("Activity config hot-reload skipped (file not found: {})", file.getPath());
        }
    }

    private void tick() {
        try {
            File file = resolveActivityConfigsFile();
            long modified = safeLastModified(file);
            if (modified <= 0) {
                return;
            }
            long prev = lastSeenModifiedMillis;
            if (prev > 0 && modified <= prev) {
                return;
            }
            lastSeenModifiedMillis = modified;
            activityConfigService.reload();
            ragKnowledgeService.rebuild();
            int pushed = updateNotifyBroadcaster.broadcastActivityUpdate();
            log.info("ActivityConfigs.json reloaded (mtime={}), pushed 652 to {} sessions", modified, pushed);
        } catch (Exception e) {
            log.debug("Activity config hot-reload tick failed", e);
        }
    }

    private File resolveActivityConfigsFile() {
        String resource = properties.getActivityConfigsResource();
        if (resource != null && resource.startsWith("file:")) {
            return new File(resource.substring("file:".length()));
        }
        return new File(properties.getDataDir(), "ActivityConfigs.json");
    }

    private long safeLastModified(File file) {
        try {
            if (!file.exists() || !file.isFile()) {
                return 0L;
            }
            return file.lastModified();
        } catch (Exception e) {
            return 0L;
        }
    }

    @PreDestroy
    public void stop() {
        scheduler.shutdownNow();
    }
}
