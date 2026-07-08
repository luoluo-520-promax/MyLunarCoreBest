package cn.itcast.demo.mylunarcore.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ResourceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@DisplayName("GameResourceFactory 静态资源工厂测试")
class GameResourceFactoryTest {

    private static final Logger log = LoggerFactory.getLogger(GameResourceFactoryTest.class);

    private GameResourceFactory factory;
    private ResourceLoader resourceLoader;

    @BeforeEach
    void setUp() {
        factory = new GameResourceFactory();
        resourceLoader = CommonTestFixtures.resourceLoader();
        log.info("工厂初始化: factoryClass={}, resourceLoaderClass={}",
                factory.getClass().getSimpleName(), resourceLoader.getClass().getSimpleName());
    }

    @Test
    @DisplayName("createTabularResource 应创建 TabularStaticResource")
    void createTabularResourceShouldReturnTabularInstance() {
        TabularStaticResource resource = factory.createTabularResource(
                CommonTestFixtures.ITEM_CONFIG_CSV, resourceLoader);

        log.info("表格资源创建校验: location={}, resourceClass={}",
                resource.getResourceLocation(), resource.getClass().getSimpleName());
        assertNotNull(resource);
        assertEquals(CommonTestFixtures.ITEM_CONFIG_CSV, resource.getResourceLocation());
    }

    @Test
    @DisplayName("createGameResource TABULAR 分支应委托表格工厂")
    void createGameResourceTabularShouldDelegate() {
        TabularStaticResource resource = factory.createGameResource(
                GameResourceKind.TABULAR, CommonTestFixtures.ITEM_CONFIG_CSV, resourceLoader);

        log.info("工厂方法校验: kind={}, location={}, rowCount={}",
                GameResourceKind.TABULAR, resource.getResourceLocation(), resource.getRows().size());
        assertNotNull(resource);
        assertEquals(2, resource.getRows().size());
    }
}
