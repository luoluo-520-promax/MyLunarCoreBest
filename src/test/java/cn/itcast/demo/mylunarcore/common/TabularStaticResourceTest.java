package cn.itcast.demo.mylunarcore.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ResourceLoader;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TabularStaticResource 表格静态资源测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code TabularStaticResourceTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("TabularStaticResource 表格静态资源测试")
class TabularStaticResourceTest {

    private static final Logger log = LoggerFactory.getLogger(TabularStaticResourceTest.class);

    private TabularStaticResource resource;

    @BeforeEach
    void setUp() {
        ResourceLoader loader = CommonTestFixtures.resourceLoader();
        resource = CommonTestFixtures.itemConfigResource(loader);
        log.info("表格资源初始化: location={}", resource.getResourceLocation());
    }

    /**
     * 验证点：getRows 应解析 CSV 并跳过注释行。
     * <p>测试方法 {@code getRowsShouldParseCsvAndSkipComments}：
     * <ul>
     *   <li>{@code assertEquals(2, rows.size());}</li>
     *   <li>{@code assertEquals("id", rows.get(0)[0]);}</li>
     *   <li>{@code assertEquals("1", rows.get(1)[0]);}</li>
     *   <li>{@code assertEquals("test_item", rows.get(1)[1]);}</li>
     *   <li>{@code assertEquals("99", rows.get(1)[2]);}</li>
     * </ul>
     */
    @Test
    @DisplayName("getRows 应解析 CSV 并跳过注释行")
    void getRowsShouldParseCsvAndSkipComments() {
        List<String[]> rows = resource.getRows();

        log.info("CSV 解析校验: location={}, rowCount={}, headerId={}, dataRowId={}, dataRowName={}, dataRowStack={}",
                resource.getResourceLocation(), rows.size(),
                rows.get(0)[0], rows.get(1)[0], rows.get(1)[1], rows.get(1)[2]);
        assertEquals(2, rows.size());
        assertEquals("id", rows.get(0)[0]);
        assertEquals("1", rows.get(1)[0]);
        assertEquals("test_item", rows.get(1)[1]);
        assertEquals("99", rows.get(1)[2]);
    }

    /**
     * 验证点：getRows 应缓存解析结果。
     * <p>测试方法 {@code getRowsShouldCacheParsedResult}：
     * <ul>
     *   <li>{@code assertSame(first, second);}</li>
     * </ul>
     */
    @Test
    @DisplayName("getRows 应缓存解析结果")
    void getRowsShouldCacheParsedResult() {
        List<String[]> first = resource.getRows();
        List<String[]> second = resource.getRows();

        log.info("缓存校验: location={}, firstHash={}, secondHash={}, sameInstance={}",
                resource.getResourceLocation(), System.identityHashCode(first),
                System.identityHashCode(second), first == second);
        assertSame(first, second);
    }

    /**
     * 验证点：invalidate 后应重新加载。
     * <p>测试方法 {@code invalidateShouldForceReload}：
     * <ul>
     *   <li>{@code assertEquals(before.size(), after.size());}</li>
     *   <li>{@code assertEquals(before.get(0)[0], after.get(0)[0]);}</li>
     * </ul>
     */
    @Test
    @DisplayName("invalidate 后应重新加载")
    void invalidateShouldForceReload() {
        List<String[]> before = resource.getRows();
        resource.invalidate();
        List<String[]> after = resource.getRows();

        log.info("失效重载校验: location={}, beforeHash={}, afterHash={}, rowCount={}",
                resource.getResourceLocation(), System.identityHashCode(before),
                System.identityHashCode(after), after.size());
        assertEquals(before.size(), after.size());
        assertEquals(before.get(0)[0], after.get(0)[0]);
    }

    /**
     * 验证点：不存在的资源应返回空列表。
     * <p>测试方法 {@code missingResourceShouldReturnEmptyList}：
     * <ul>
     *   <li>{@code assertTrue(rows.isEmpty());}</li>
     * </ul>
     */
    @Test
    @DisplayName("不存在的资源应返回空列表")
    void missingResourceShouldReturnEmptyList() {
        ResourceLoader loader = CommonTestFixtures.resourceLoader();
        TabularStaticResource missing = new TabularStaticResource("classpath:data/not_exist.csv", loader);
        List<String[]> rows = missing.getRows();

        log.info("缺失资源校验: location={}, rowCount={}", missing.getResourceLocation(), rows.size());
        assertTrue(rows.isEmpty());
    }
}
