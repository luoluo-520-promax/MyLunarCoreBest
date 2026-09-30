package cn.itcast.demo.mylunarcore.assist;

import cn.itcast.demo.mylunarcore.common.AppLogger;
import cn.itcast.demo.mylunarcore.common.ConfigFileService;
import cn.itcast.demo.mylunarcore.common.LogCategory;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.springframework.stereotype.Component;

@Component
public class AssistPersonaRepository {

    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_ASSIST, AssistPersonaRepository.class);

    private final ConfigFileService configFileService;
    private final LunarCoreProperties properties;
    private volatile AssistPersonaConfig config = AssistPersonaConfig.empty();

    public AssistPersonaRepository(ConfigFileService configFileService, LunarCoreProperties properties) {
        this.configFileService = configFileService;
        this.properties = properties;
    }

    @PostConstruct
    public void load() {
        reload();
    }

    public boolean reload() {
        String relative = properties.getAiAssist().getPersonaConfigResource();
        try {
            AssistPersonaConfig loaded = configFileService.readJson(relative, AssistPersonaConfig.class);
            if (loaded == null) {
                log.warn("AssistPersonaConfig empty, keep previous, path={}", relative);
                return false;
            }
            config = loaded;
            log.info("AssistPersonaConfig loaded version={} personas={} path={}",
                    loaded.version(),
                    loaded.personas() == null ? 0 : loaded.personas().size(),
                    relative);
            return true;
        } catch (Exception e) {
            log.warn("AssistPersonaConfig reload failed path={}", relative, e);
            return false;
        }
    }

    public AssistPersonaConfig current() {
        return config;
    }

    public void restore(AssistPersonaConfig previous) {
        if (previous != null) {
            config = previous;
        }
    }
}
