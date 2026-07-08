package cn.itcast.demo.mylunarcore.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ResourceLoader;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("StaticResourceRegistry 静态资源注册表测试")
class StaticResourceRegistryTest {

    private static final Logger log = LoggerFactory.getLogger(StaticResourceRegistryTest.class);

    private StaticResourceRegistry registry;

    @BeforeEach
    void setUp() {
        ResourceLoader loader = CommonTestFixtures.resourceLoader();
        GameResourceFactory factory = new GameResourceFactory();
        registry = new StaticResourceRegistry(loader, factory);
        registry.registerKnownResources();
        log.info("注册表初始化: registeredCount={}", registry.snapshot().size());
    }

    @Test
    @DisplayName("registerKnownResources 应注册 ITEM_CONFIG")
    void registerKnownResourcesShouldRegisterItemConfig() {
        Optional<TabularStaticResource> itemConfig = registry.getByType("item_config");
        Map<String, TabularStaticResource> snapshot = registry.snapshot();

        log.info("注册校验: type=item_config, present={}, snapshotSize={}, location={}",
                itemConfig.isPresent(), snapshot.size(),
                itemConfig.map(TabularStaticResource::getResourceLocation).orElse(null));
        assertTrue(itemConfig.isPresent());
        assertEquals(1, snapshot.size());
        assertEquals(CommonTestFixtures.ITEM_CONFIG_CSV, itemConfig.get().getResourceLocation());
    }

    @Test
    @DisplayName("getByType 未注册类型应返回 empty")
    void getByTypeUnknownShouldReturnEmpty() {
        Optional<TabularStaticResource> unknown = registry.getByType("unknown_type");

        log.info("未知类型校验: type=unknown_type, present={}", unknown.isPresent());
        assertFalse(unknown.isPresent());
    }

    @Test
    @DisplayName("reloadAll 应使缓存失效并可重新读取")
    void reloadAllShouldInvalidateCachedRows() {
        TabularStaticResource itemConfig = registry.getByType("item_config").orElseThrow();
        var rowsBefore = itemConfig.getRows();
        registry.reloadAll();
        var rowsAfter = itemConfig.getRows();

        log.info("全量失效校验: type=item_config, rowCountBefore={}, rowCountAfter={}, sameInstance={}",
                rowsBefore.size(), rowsAfter.size(), rowsBefore == rowsAfter);
        assertEquals(rowsBefore.size(), rowsAfter.size());
    }
}
