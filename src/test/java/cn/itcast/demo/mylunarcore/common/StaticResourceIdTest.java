package cn.itcast.demo.mylunarcore.common;

import cn.itcast.demo.mylunarcore.config.ResourceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * StaticResourceId 静态资源枚举测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code StaticResourceIdTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("StaticResourceId 静态资源枚举测试")
class StaticResourceIdTest {

    private static final Logger log = LoggerFactory.getLogger(StaticResourceIdTest.class);

    /**
     * 验证点：ITEM_CONFIG 应绑定 data 路径与 ResourceType 注解。
     * <p>测试方法 {@code itemConfigShouldExposeLocationAndAnnotation}：
     * <ul>
     *   <li>{@code assertEquals("file:data/items_config.csv", id.getLocation());}</li>
     *   <li>{@code assertNotNull(resourceType);}</li>
     *   <li>{@code assertEquals("item_config", resourceType.value());}</li>
     * </ul>
     */
    @Test
    @DisplayName("ITEM_CONFIG 应绑定 data 路径与 ResourceType 注解")
    void itemConfigShouldExposeLocationAndAnnotation() {
        StaticResourceId id = StaticResourceId.ITEM_CONFIG;
        ResourceType resourceType = id.resourceType();

        log.info("静态资源枚举校验: name={}, location={}, resourceType={}",
                id.name(), id.getLocation(), resourceType == null ? null : resourceType.value());
        assertEquals("file:data/items_config.csv", id.getLocation());
        assertNotNull(resourceType);
        assertEquals("item_config", resourceType.value());
    }
}
