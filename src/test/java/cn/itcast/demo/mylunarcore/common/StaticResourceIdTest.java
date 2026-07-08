package cn.itcast.demo.mylunarcore.common;

import cn.itcast.demo.mylunarcore.config.ResourceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@DisplayName("StaticResourceId 静态资源枚举测试")
class StaticResourceIdTest {

    private static final Logger log = LoggerFactory.getLogger(StaticResourceIdTest.class);

    @Test
    @DisplayName("ITEM_CONFIG 应绑定 classpath 路径与 ResourceType 注解")
    void itemConfigShouldExposeLocationAndAnnotation() {
        StaticResourceId id = StaticResourceId.ITEM_CONFIG;
        ResourceType resourceType = id.resourceType();

        log.info("静态资源枚举校验: name={}, location={}, resourceType={}",
                id.name(), id.getLocation(), resourceType == null ? null : resourceType.value());
        assertEquals("classpath:data/items_config.csv", id.getLocation());
        assertNotNull(resourceType);
        assertEquals("item_config", resourceType.value());
    }
}
