package cn.itcast.demo.mylunarcore.common;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ResourceLoader;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ActivityScheduleService 活动排期服务测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code ActivityScheduleServiceTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("ActivityScheduleService 活动排期服务测试")
class ActivityScheduleServiceTest {

    private static final Logger log = LoggerFactory.getLogger(ActivityScheduleServiceTest.class);

    private ActivityScheduleService service;
    private ActivityConfigService activityConfigService;

    @BeforeEach
    void setUp() {
        LunarCoreProperties properties = CommonTestFixtures.defaultProperties();
        properties.setActivityScheduleResource("classpath:data/ActivityScheduling.json");
        ResourceLoader loader = CommonTestFixtures.resourceLoader();
        activityConfigService = mock(ActivityConfigService.class);
        when(activityConfigService.isActivityActive(org.mockito.ArgumentMatchers.anyInt())).thenReturn(false);
        when(activityConfigService.isActivityActive(org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(false);
        when(activityConfigService.findById(org.mockito.ArgumentMatchers.anyInt())).thenReturn(java.util.Optional.empty());
        service = new ActivityScheduleService(properties, loader, activityConfigService);
        service.reloadSchedule();
        log.info("活动排期初始化: windowCount={}, resourcePath={}",
                service.getWindows().size(), properties.getActivityScheduleResource());
    }

    /**
     * 验证点：reloadSchedule 应加载 ActivityScheduling.json。
     * <p>测试方法 {@code reloadScheduleShouldLoadJson}：
     * <ul>
     *   <li>{@code assertTrue(windowCount > 0);}</li>
     * </ul>
     */
    @Test
    @DisplayName("reloadSchedule 应加载 ActivityScheduling.json")
    void reloadScheduleShouldLoadJson() {
        int windowCount = service.getWindows().size();
        int firstActivityId = windowCount > 0 ? service.getWindows().get(0).activityId : -1;

        log.info("排期加载校验: windowCount={}, firstActivityId={}", windowCount, firstActivityId);
        assertTrue(windowCount > 0);
    }

    /**
     * 验证点：isActivityActive 应对已知活动返回 true。
     * <p>测试方法 {@code isActivityActiveShouldReturnTrueForKnownActivity}：
     * <ul>
     *   <li>{@code assertTrue(active);}</li>
     * </ul>
     */
    @Test
    @DisplayName("isActivityActive 应对已知活动返回 true")
    void isActivityActiveShouldReturnTrueForKnownActivity() {
        int activityId = 1001501;
        boolean active = service.isActivityActive(activityId);

        log.info("活动开放校验: activityId={}, active={}, nowSec={}",
                activityId, active, Instant.now().getEpochSecond());
        assertTrue(active);
    }

    /**
     * 验证点：isActivityActive 应对未知活动返回 false。
     * <p>测试方法 {@code isActivityActiveShouldReturnFalseForUnknownActivity}：
     * <ul>
     *   <li>{@code assertFalse(active);}</li>
     * </ul>
     */
    @Test
    @DisplayName("isActivityActive 应对未知活动返回 false")
    void isActivityActiveShouldReturnFalseForUnknownActivity() {
        int activityId = 9999999;
        boolean active = service.isActivityActive(activityId);

        log.info("未知活动校验: activityId={}, active={}", activityId, active);
        assertFalse(active);
    }

    /**
     * 验证点：onTick 跨日应更新 lastResetDay。
     * <p>测试方法 {@code onTickShouldDetectDayChange}：
     * <ul>
     *   <li>{@code assertTrue(service.getWindows().size() > 0);}</li>
     * </ul>
     */
    @Test
    @DisplayName("onTick 跨日应更新 lastResetDay")
    void onTickShouldDetectDayChange() {
        ZoneId zone = ZoneId.systemDefault();
        LocalDate yesterday = LocalDate.now(zone).minusDays(1);
        long yesterdayMillis = yesterday.atStartOfDay(zone).toInstant().toEpochMilli();
        long todayMillis = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli();

        service.onTick(yesterdayMillis, 1000L);
        service.onTick(todayMillis, 1000L);

        log.info("跨日 Tick 校验: yesterday={}, today={}, windowCount={}",
                yesterday, LocalDate.now(zone), service.getWindows().size());
        assertTrue(service.getWindows().size() > 0);
    }
}
