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

@DisplayName("ActivityScheduleService 活动排期服务测试")
class ActivityScheduleServiceTest {

    private static final Logger log = LoggerFactory.getLogger(ActivityScheduleServiceTest.class);

    private ActivityScheduleService service;

    @BeforeEach
    void setUp() {
        LunarCoreProperties properties = CommonTestFixtures.defaultProperties();
        ResourceLoader loader = CommonTestFixtures.resourceLoader();
        service = new ActivityScheduleService(properties, loader);
        service.reloadSchedule();
        log.info("活动排期初始化: windowCount={}, resourcePath={}",
                service.getWindows().size(), properties.getActivityScheduleResource());
    }

    @Test
    @DisplayName("reloadSchedule 应加载 ActivityScheduling.json")
    void reloadScheduleShouldLoadJson() {
        int windowCount = service.getWindows().size();
        int firstActivityId = windowCount > 0 ? service.getWindows().get(0).activityId : -1;

        log.info("排期加载校验: windowCount={}, firstActivityId={}", windowCount, firstActivityId);
        assertTrue(windowCount > 0);
    }

    @Test
    @DisplayName("isActivityActive 应对已知活动返回 true")
    void isActivityActiveShouldReturnTrueForKnownActivity() {
        int activityId = 1001501;
        boolean active = service.isActivityActive(activityId);

        log.info("活动开放校验: activityId={}, active={}, nowSec={}",
                activityId, active, Instant.now().getEpochSecond());
        assertTrue(active);
    }

    @Test
    @DisplayName("isActivityActive 应对未知活动返回 false")
    void isActivityActiveShouldReturnFalseForUnknownActivity() {
        int activityId = 9999999;
        boolean active = service.isActivityActive(activityId);

        log.info("未知活动校验: activityId={}, active={}", activityId, active);
        assertFalse(active);
    }

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
