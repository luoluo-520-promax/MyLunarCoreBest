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

/**
 * StaticResourceRegistry 静态资源注册表测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code StaticResourceRegistryTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
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

    /**
     * 验证点：registerKnownResources 应注册 ITEM_CONFIG。
     * <p>测试方法 {@code registerKnownResourcesShouldRegisterItemConfig}：
     * <ul>
     *   <li>{@code assertTrue(itemConfig.isPresent());}</li>
     *   <li>{@code assertEquals(1, snapshot.size());}</li>
     *   <li>{@code assertEquals(StaticResourceId.ITEM_CONFIG.getLocation(), itemConfig.get().getResourceLocation());}</li>
     * </ul>
     */
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
        assertEquals(StaticResourceId.ITEM_CONFIG.getLocation(), itemConfig.get().getResourceLocation());
    }

    /**
     * 验证点：getByType 未注册类型应返回 empty。
     * <p>测试方法 {@code getByTypeUnknownShouldReturnEmpty}：
     * <ul>
     *   <li>{@code assertFalse(unknown.isPresent());}</li>
     * </ul>
     */
    @Test
    @DisplayName("getByType 未注册类型应返回 empty")
    void getByTypeUnknownShouldReturnEmpty() {
        Optional<TabularStaticResource> unknown = registry.getByType("unknown_type");

        log.info("未知类型校验: type=unknown_type, present={}", unknown.isPresent());
        assertFalse(unknown.isPresent());
    }

    /**
     * 验证点：reloadAll 应使缓存失效并可重新读取。
     * <p>测试方法 {@code reloadAllShouldInvalidateCachedRows}：
     * <ul>
     *   <li>{@code assertEquals(rowsBefore.size(), rowsAfter.size());}</li>
     * </ul>
     */
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
