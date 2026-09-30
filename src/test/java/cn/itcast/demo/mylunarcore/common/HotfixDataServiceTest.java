package cn.itcast.demo.mylunarcore.common;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ResourceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HotfixDataService 热修复加载服务测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code HotfixDataServiceTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("HotfixDataService 热修复加载服务测试")
class HotfixDataServiceTest {

    private static final Logger log = LoggerFactory.getLogger(HotfixDataServiceTest.class);

    private HotfixDataService service;

    @BeforeEach
    void setUp() {
        LunarCoreProperties properties = CommonTestFixtures.defaultProperties();
        ResourceLoader loader = CommonTestFixtures.resourceLoader();
        service = new HotfixDataService(properties, loader);
        log.info("热修复服务初始化: resourcePath={}", properties.getHotfix().getResource());
    }

    /**
     * 验证点：reload 应从 classpath 加载 hotfix.json。
     * <p>测试方法 {@code reloadShouldLoadHotfixJson}：
     * <ul>
     *   <li>{@code assertTrue(ok);}</li>
     *   <li>{@code assertEquals("https://cdn.example.com/lunar/static/", data.getClientResourceBaseUrl());}</li>
     *   <li>{@code assertEquals("1.0.0", data.getHotfixVersion());}</li>
     *   <li>{@code assertEquals(1L, data.getPatchVersion());}</li>
     * </ul>
     */
    @Test
    @DisplayName("reload 应从 classpath 加载 hotfix.json")
    void reloadShouldLoadHotfixJson() {
        boolean ok = service.reload();
        HotfixData data = service.current();

        log.info("热修复加载校验: reloadOk={}, baseUrl={}, hotfixVersion={}, patchVersion={}",
                ok, data.getClientResourceBaseUrl(), data.getHotfixVersion(), data.getPatchVersion());
        assertTrue(ok);
        assertEquals("https://cdn.example.com/lunar/static/", data.getClientResourceBaseUrl());
        assertEquals("1.0.0", data.getHotfixVersion());
        assertEquals(1L, data.getPatchVersion());
    }

    /**
     * 验证点：资源不存在时 reload 应返回 false 并保留旧数据。
     * <p>测试方法 {@code reloadMissingResourceShouldKeepPreviousData}：
     * <ul>
     *   <li>{@code assertTrue(reloadableService.reload());}</li>
     *   <li>{@code assertEquals(before.getHotfixVersion(), after.getHotfixVersion());}</li>
     *   <li>{@code assertEquals("1.0.0", after.getHotfixVersion());}</li>
     *   <li>{@code assertFalse(reloadOk);}</li>
     * </ul>
     */
    @Test
    @DisplayName("资源不存在时 reload 应返回 false 并保留旧数据")
    void reloadMissingResourceShouldKeepPreviousData() {
        LunarCoreProperties properties = CommonTestFixtures.defaultProperties();
        ResourceLoader loader = CommonTestFixtures.resourceLoader();
        HotfixDataService reloadableService = new HotfixDataService(properties, loader);

        assertTrue(reloadableService.reload());
        HotfixData before = reloadableService.current();
        properties.getHotfix().setResource("classpath:data/not_exist_hotfix.json");
        boolean reloadOk = reloadableService.reload();
        HotfixData after = reloadableService.current();

        log.info("缺失资源容错: beforeVersion={}, afterVersion={}, reloadOk={}",
                before.getHotfixVersion(), after.getHotfixVersion(), reloadOk);
        assertEquals(before.getHotfixVersion(), after.getHotfixVersion());
        assertEquals("1.0.0", after.getHotfixVersion());
        assertFalse(reloadOk);
    }
}
