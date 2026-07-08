package cn.itcast.demo.mylunarcore.common;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.ResourceLoader;

/**
 * common 包单元测试共享构造器与常量。
 */
final class CommonTestFixtures {

    static final long PLAYER_UID = 77L;
    static final String ITEM_CONFIG_CSV = "classpath:data/items_config.csv";
    static final String HOTFIX_JSON = "classpath:hotfix.json";
    static final String ACTIVITY_SCHEDULE_JSON = "classpath:data/ActivityScheduling.json";

    private CommonTestFixtures() {
    }

    static ResourceLoader resourceLoader() {
        return new DefaultResourceLoader();
    }

    static LunarCoreProperties defaultProperties() {
        LunarCoreProperties properties = new LunarCoreProperties();
        properties.getHotfix().setResource(HOTFIX_JSON);
        properties.setActivityScheduleResource(ACTIVITY_SCHEDULE_JSON);
        properties.getGameLoop().setEnabled(false);
        return properties;
    }

    static TabularStaticResource itemConfigResource(ResourceLoader loader) {
        return new TabularStaticResource(ITEM_CONFIG_CSV, loader);
    }

    static BattleStartedEvent battleStartedEvent() {
        return new BattleStartedEvent(9001L, 77, 1001, 1, 2, 1_700_000_000L);
    }

    static BattleEndedEvent battleEndedEvent() {
        return new BattleEndedEvent(9001L, 77, 1, "victory", 1_700_000_100L);
    }
}
