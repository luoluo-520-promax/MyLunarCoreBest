package cn.itcast.demo.mylunarcore.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * AppLogger 日志门面测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code AppLoggerTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("AppLogger 日志门面测试")
class AppLoggerTest {

    private static final Logger log = org.slf4j.LoggerFactory.getLogger(AppLoggerTest.class);

    /**
     * 验证点：logger 应绑定到 LogCategory 对应的 SLF4J 名称。
     * <p>测试方法 {@code loggerShouldBindToCategoryName}：
     * <ul>
     *   <li>{@code assertNotNull(systemLogger);}</li>
     *   <li>{@code assertNotNull(battleLogger);}</li>
     *   <li>{@code assertEquals("cn.itcast.demo.mylunarcore.sys.AppLoggerTest", systemLogger.getName());}</li>
     *   <li>{@code assertEquals("cn.itcast.demo.mylunarcore.biz.battle.AppLoggerTest", battleLogger.getName());}</li>
     * </ul>
     */
    @Test
    @DisplayName("logger 应绑定到 LogCategory 对应的 SLF4J 名称")
    void loggerShouldBindToCategoryName() {
        Logger systemLogger = AppLogger.logger(LogCategory.SYSTEM, AppLoggerTest.class);
        Logger battleLogger = AppLogger.logger(LogCategory.BUSINESS_BATTLE, AppLoggerTest.class);

        log.info("AppLogger 绑定校验: systemLoggerName={}, battleLoggerName={}",
                systemLogger.getName(), battleLogger.getName());
        assertNotNull(systemLogger);
        assertNotNull(battleLogger);
        assertEquals("cn.itcast.demo.mylunarcore.sys.AppLoggerTest", systemLogger.getName());
        assertEquals("cn.itcast.demo.mylunarcore.biz.battle.AppLoggerTest", battleLogger.getName());
    }
}
