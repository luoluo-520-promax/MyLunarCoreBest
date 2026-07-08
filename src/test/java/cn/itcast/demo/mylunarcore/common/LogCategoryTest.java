package cn.itcast.demo.mylunarcore.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@DisplayName("LogCategory 日志分类测试")
class LogCategoryTest {

    private static final Logger log = LoggerFactory.getLogger(LogCategoryTest.class);

    @Test
    @DisplayName("SYSTEM 分类应生成 sys 前缀 logger 名")
    void systemCategoryShouldUseSysPrefix() {
        String loggerName = LogCategory.SYSTEM.loggerName(LogCategoryTest.class);
        log.info("SYSTEM logger 名校验: category={}, loggerName={}",
                LogCategory.SYSTEM, loggerName);
        assertEquals("cn.itcast.demo.mylunarcore.sys.LogCategoryTest", loggerName);
        assertNotNull(LoggerFactory.getLogger(loggerName));
    }

    @Test
    @DisplayName("BUSINESS 分类应生成 biz.{segment} 前缀 logger 名")
    void businessCategoryShouldUseBizSegmentPrefix() {
        String battleName = LogCategory.BUSINESS_BATTLE.loggerName(LogCategoryTest.class);
        String sessionName = LogCategory.BUSINESS_SESSION.loggerName(LogCategoryTest.class);

        log.info("BUSINESS logger 名校验: battleCategory={}, battleLoggerName={}, sessionCategory={}, sessionLoggerName={}",
                LogCategory.BUSINESS_BATTLE, battleName,
                LogCategory.BUSINESS_SESSION, sessionName);
        assertEquals("cn.itcast.demo.mylunarcore.biz.battle.LogCategoryTest", battleName);
        assertEquals("cn.itcast.demo.mylunarcore.biz.session.LogCategoryTest", sessionName);
    }
}
