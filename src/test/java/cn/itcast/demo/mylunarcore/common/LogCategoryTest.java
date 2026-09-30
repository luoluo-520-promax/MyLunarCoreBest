package cn.itcast.demo.mylunarcore.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * LogCategory 日志分类测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code LogCategoryTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("LogCategory 日志分类测试")
class LogCategoryTest {

    private static final Logger log = LoggerFactory.getLogger(LogCategoryTest.class);

    /**
     * 验证点：SYSTEM 分类应生成 sys 前缀 logger 名。
     * <p>测试方法 {@code systemCategoryShouldUseSysPrefix}：
     * <ul>
     *   <li>{@code assertEquals("cn.itcast.demo.mylunarcore.sys.LogCategoryTest", loggerName);}</li>
     *   <li>{@code assertNotNull(LoggerFactory.getLogger(loggerName));}</li>
     * </ul>
     */
    @Test
    @DisplayName("SYSTEM 分类应生成 sys 前缀 logger 名")
    void systemCategoryShouldUseSysPrefix() {
        String loggerName = LogCategory.SYSTEM.loggerName(LogCategoryTest.class);
        log.info("SYSTEM logger 名校验: category={}, loggerName={}",
                LogCategory.SYSTEM, loggerName);
        assertEquals("cn.itcast.demo.mylunarcore.sys.LogCategoryTest", loggerName);
        assertNotNull(LoggerFactory.getLogger(loggerName));
    }

    /**
     * 验证点：BUSINESS 分类应生成 biz.{segment} 前缀 logger 名。
     * <p>测试方法 {@code businessCategoryShouldUseBizSegmentPrefix}：
     * <ul>
     *   <li>{@code assertEquals("cn.itcast.demo.mylunarcore.biz.battle.LogCategoryTest", battleName);}</li>
     *   <li>{@code assertEquals("cn.itcast.demo.mylunarcore.biz.session.LogCategoryTest", sessionName);}</li>
     * </ul>
     */
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
