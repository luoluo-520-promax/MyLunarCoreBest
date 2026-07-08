package cn.itcast.demo.mylunarcore.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@DisplayName("AppLogger 日志门面测试")
class AppLoggerTest {

    private static final Logger log = org.slf4j.LoggerFactory.getLogger(AppLoggerTest.class);

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
