package cn.itcast.demo.mylunarcore.assist;

import cn.itcast.demo.mylunarcore.common.AppLogger;
import cn.itcast.demo.mylunarcore.common.ConfigFileService;
import cn.itcast.demo.mylunarcore.common.LogCategory;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.springframework.stereotype.Component;

/**
 * 外链攻略目录仓储：从 data 热更加载，失败保留旧快照。
 */
@Component
public class ExternalGuideCatalogRepository {
    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_ASSIST, ExternalGuideCatalogRepository.class);

    private final ConfigFileService configFileService;
    private final LunarCoreProperties properties;
    private volatile ExternalGuideCatalogConfig config = ExternalGuideCatalogConfig.empty();

    public ExternalGuideCatalogRepository(ConfigFileService configFileService, LunarCoreProperties properties) {
        this.configFileService = configFileService;
        this.properties = properties;
    }

    @PostConstruct
    public void load() {
        reload();
    }

    public boolean reload() {
        String relative = properties.getAiAssist().getExternalGuideCatalogResource();
        try {
            ExternalGuideCatalogConfig loaded = configFileService.readJson(relative, ExternalGuideCatalogConfig.class);
            if (loaded == null) {
                return false;
            }
            config = loaded;
            log.info("ExternalGuideCatalog loaded version={} guides={} platforms={} enabled={} path={}",
                    config.version(), config.guides().size(), config.platforms().size(), config.enabled(), relative);
            return true;
        } catch (Exception e) {
            log.warn("ExternalGuideCatalog reload failed path={}", relative, e);
            return false;
        }
    }

    public ExternalGuideCatalogConfig current() {
        return config;
    }

    public void restore(ExternalGuideCatalogConfig previous) {
        if (previous != null) {
            config = previous;
        }
    }
}
