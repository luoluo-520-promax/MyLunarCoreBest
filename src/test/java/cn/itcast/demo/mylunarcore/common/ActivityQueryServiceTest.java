package cn.itcast.demo.mylunarcore.common;

import cn.itcast.demo.mylunarcore.model.ActivityConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ActivityQueryService 活动查询分层测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code ActivityQueryServiceTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("ActivityQueryService 活动查询分层测试")
class ActivityQueryServiceTest {

    /**
     * 验证点：应按时间窗筛选当前活动。
     * <p>测试方法 {@code shouldFilterByTimeWindow}：
     * <ul>
     *   <li>{@code when(configService.snapshot()).thenReturn(snapshot);}</li>
     *   <li>{@code when(configService.findById(1)).thenReturn(Optional.of(active));}</li>
     *   <li>{@code assertEquals(1, allActive.size());}</li>
     *   <li>{@code assertEquals(1, allActive.get(0).getActivityId());}</li>
     *   <li>{@code assertEquals(1, byId.size());}</li>
     *   <li>{@code assertTrue(query.resolveActivities(999).isEmpty());}</li>
     * </ul>
     */
    @Test
    @DisplayName("应按时间窗筛选当前活动")
    void shouldFilterByTimeWindow() {
        long now = Instant.now().getEpochSecond();
        ActivityConfig active = new ActivityConfig();
        active.setActivityId(1);
        active.setBeginTime(now - 10);
        active.setEndTime(now + 100);
        ActivityConfig expired = new ActivityConfig();
        expired.setActivityId(2);
        expired.setBeginTime(now - 200);
        expired.setEndTime(now - 100);

        Map<Integer, ActivityConfig> snapshot = new ConcurrentHashMap<>();
        snapshot.put(1, active);
        snapshot.put(2, expired);

        ActivityConfigService configService = mock(ActivityConfigService.class);
        when(configService.snapshot()).thenReturn(snapshot);
        when(configService.findById(1)).thenReturn(Optional.of(active));

        cn.itcast.demo.mylunarcore.ops.ServerClockService clock =
                new cn.itcast.demo.mylunarcore.ops.ServerClockService();
        cn.itcast.demo.mylunarcore.activity.ActivityVisibilityService visibility =
                new cn.itcast.demo.mylunarcore.activity.ActivityVisibilityService(
                        clock, new cn.itcast.demo.mylunarcore.config.LunarCoreProperties());
        ActivityQueryService query = new ActivityQueryService(configService, visibility, clock);
        List<ActivityConfig> allActive = query.listCurrentlyActiveConfigs();
        List<ActivityConfig> byId = query.resolveActivities(1);

        assertEquals(1, allActive.size());
        assertEquals(1, allActive.get(0).getActivityId());
        assertEquals(1, byId.size());
        assertTrue(query.resolveActivities(999).isEmpty());
    }
}
