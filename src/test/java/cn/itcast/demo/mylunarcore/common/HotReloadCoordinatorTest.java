package cn.itcast.demo.mylunarcore.common;

import cn.itcast.demo.mylunarcore.gacha.GachaConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DisplayName("HotReloadCoordinator 热重载协调器测试")
class HotReloadCoordinatorTest {

    private static final Logger log = LoggerFactory.getLogger(HotReloadCoordinatorTest.class);

    private HotfixDataService hotfixDataService;
    private ActivityScheduleService activityScheduleService;
    private GachaConfigService gachaConfigService;
    private StaticResourceRegistry staticResourceRegistry;
    private HotReloadCoordinator coordinator;

    @BeforeEach
    void setUp() {
        hotfixDataService = mock(HotfixDataService.class);
        activityScheduleService = mock(ActivityScheduleService.class);
        gachaConfigService = mock(GachaConfigService.class);
        staticResourceRegistry = mock(StaticResourceRegistry.class);
        coordinator = new HotReloadCoordinator(
                hotfixDataService, activityScheduleService, gachaConfigService, staticResourceRegistry);
        log.info("热重载协调器初始化: coordinatorClass={}", coordinator.getClass().getSimpleName());
    }

    @Test
    @DisplayName("reloadAll 应依次调用各子模块重载")
    void reloadAllShouldInvokeAllSubmodules() {
        coordinator.reloadAll();

        log.info("全量重载校验: hotfixCalled=true, activityCalled=true, gachaCalled=true, staticCalled=true");
        verify(hotfixDataService).reload();
        verify(activityScheduleService).reloadSchedule();
        verify(gachaConfigService).reload();
        verify(staticResourceRegistry).reloadAll();
    }
}
