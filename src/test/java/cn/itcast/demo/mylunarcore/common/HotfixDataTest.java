package cn.itcast.demo.mylunarcore.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HotfixData 热修复数据测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code HotfixDataTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("HotfixData 热修复数据测试")
class HotfixDataTest {

    private static final Logger log = LoggerFactory.getLogger(HotfixDataTest.class);

    /**
     * 验证点：empty 应返回全默认值。
     * <p>测试方法 {@code emptyShouldReturnDefaultValues}：
     * <ul>
     *   <li>{@code assertEquals("", data.getClientResourceBaseUrl());}</li>
     *   <li>{@code assertEquals("0.0.0", data.getHotfixVersion());}</li>
     *   <li>{@code assertEquals(0L, data.getPatchVersion());}</li>
     * </ul>
     */
    @Test
    @DisplayName("empty 应返回全默认值")
    void emptyShouldReturnDefaultValues() {
        HotfixData data = HotfixData.empty();

        log.info("默认值校验: baseUrl={}, hotfixVersion={}, patchVersion={}",
                data.getClientResourceBaseUrl(), data.getHotfixVersion(), data.getPatchVersion());
        assertEquals("", data.getClientResourceBaseUrl());
        assertEquals("0.0.0", data.getHotfixVersion());
        assertEquals(0L, data.getPatchVersion());
    }

    /**
     * 验证点：setter 应正确保存字段。
     * <p>测试方法 {@code settersShouldPersistValues}：
     * <ul>
     *   <li>{@code assertEquals("https://cdn.test.com/", data.getClientResourceBaseUrl());}</li>
     *   <li>{@code assertEquals("2.1.0", data.getHotfixVersion());}</li>
     *   <li>{@code assertEquals(42L, data.getPatchVersion());}</li>
     * </ul>
     */
    @Test
    @DisplayName("setter 应正确保存字段")
    void settersShouldPersistValues() {
        HotfixData data = new HotfixData();
        data.setClientResourceBaseUrl("https://cdn.test.com/");
        data.setHotfixVersion("2.1.0");
        data.setPatchVersion(42L);

        log.info("字段赋值校验: baseUrl={}, hotfixVersion={}, patchVersion={}",
                data.getClientResourceBaseUrl(), data.getHotfixVersion(), data.getPatchVersion());
        assertEquals("https://cdn.test.com/", data.getClientResourceBaseUrl());
        assertEquals("2.1.0", data.getHotfixVersion());
        assertEquals(42L, data.getPatchVersion());
    }
}
