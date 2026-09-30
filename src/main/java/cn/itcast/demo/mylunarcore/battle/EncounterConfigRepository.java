package cn.itcast.demo.mylunarcore.battle;

import cn.itcast.demo.mylunarcore.common.AppLogger;
import cn.itcast.demo.mylunarcore.common.ConfigFileService;
import cn.itcast.demo.mylunarcore.common.LogCategory;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.springframework.stereotype.Component;

/**
 * 遭遇配置仓库：从 data/EncounterConfigs.json 加载，支持热更回滚。
 */
@Component
public class EncounterConfigRepository {

    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_BATTLE, EncounterConfigRepository.class);

    private final ConfigFileService configFileService;
    private final LunarCoreProperties properties;
    private volatile EncounterConfig config = EncounterConfig.empty();

    public EncounterConfigRepository(ConfigFileService configFileService, LunarCoreProperties properties) {
        this.configFileService = configFileService;
        this.properties = properties;
    }

    @PostConstruct
    public void load() {
        reload();
    }

    public boolean reload() {
        String relative = properties.getEncounterConfigsResource();
        try {
            EncounterConfig loaded = configFileService.readJson(relative, EncounterConfig.class);
            if (loaded == null) {
                log.warn("EncounterConfigs empty, keep previous, path={}", relative);
                return false;
            }
            config = loaded;
            log.info("EncounterConfigs loaded, version={}, encounterCount={}, path={}",
                    loaded.version(),
                    loaded.encounters() == null ? 0 : loaded.encounters().size(),
                    relative);
            return true;
        } catch (Exception e) {
            log.warn("EncounterConfigs reload failed, path={}, keep previous", relative, e);
            return false;
        }
    }

    public EncounterConfig current() {
        return config;
    }

    public void restore(EncounterConfig previous) {
        if (previous != null) {
            config = previous;
        }
    }
}
