package cn.itcast.demo.mylunarcore.common;

import cn.itcast.demo.mylunarcore.activity.ActivityVisibilityService;
import cn.itcast.demo.mylunarcore.model.ActivityConfig;
import cn.itcast.demo.mylunarcore.ops.ServerClockService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 活动配置查询应用服务：生效窗 + QA 可见性掩码。
 */
@Service
public class ActivityQueryService {

    private final ActivityConfigService activityConfigService;
    private final ActivityVisibilityService visibilityService;
    private final ServerClockService clockService;

    public ActivityQueryService(ActivityConfigService activityConfigService,
                                ActivityVisibilityService visibilityService,
                                ServerClockService clockService) {
        this.activityConfigService = activityConfigService;
        this.visibilityService = visibilityService;
        this.clockService = clockService;
    }

    /**
     * activityId&gt;0 时按 ID 查询；否则返回当前可见活动。
     */
    public List<ActivityConfig> resolveActivities(int activityId) {
        return resolveActivities(activityId, false);
    }

    public List<ActivityConfig> resolveActivities(int activityId, boolean qaTester) {
        if (activityId > 0) {
            return activityConfigService.findById(activityId)
                    .filter(c -> visibilityService.canSee(c, qaTester))
                    .map(List::of)
                    .orElse(List.of());
        }
        return listCurrentlyActiveConfigs(qaTester);
    }

    public List<ActivityConfig> listCurrentlyActiveConfigs() {
        return listCurrentlyActiveConfigs(false);
    }

    public List<ActivityConfig> listCurrentlyActiveConfigs(boolean qaTester) {
        List<ActivityConfig> active = new ArrayList<>();
        for (Map.Entry<Integer, ActivityConfig> entry : activityConfigService.snapshot().entrySet()) {
            ActivityConfig config = entry.getValue();
            if (visibilityService.canSee(config, qaTester)) {
                active.add(config);
            }
        }
        return active;
    }

    public long nowEpochSecond() {
        return clockService.nowEpochSecond();
    }
}
