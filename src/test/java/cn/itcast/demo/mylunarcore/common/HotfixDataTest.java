package cn.itcast.demo.mylunarcore.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("HotfixData 热修复数据测试")
class HotfixDataTest {

    private static final Logger log = LoggerFactory.getLogger(HotfixDataTest.class);

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
