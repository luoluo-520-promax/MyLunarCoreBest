package cn.itcast.demo.mylunarcore.qol;

import cn.itcast.demo.mylunarcore.activity.ActivityReminderService;
import cn.itcast.demo.mylunarcore.activity.ReturnCheckService;
import cn.itcast.demo.mylunarcore.economy.MonthlyCardService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 登录后体验类推送聚合：回流、每日次数提醒、活动倒计时、月卡补发。
 */
@Service
public class QolLoginHookService {

    private final ObjectProvider<ReturnCheckService> returnCheckProvider;
    private final ObjectProvider<DailyReminderService> dailyReminderProvider;
    private final ObjectProvider<ActivityReminderService> activityReminderProvider;
    private final ObjectProvider<MonthlyCardService> monthlyCardProvider;

    public QolLoginHookService(ObjectProvider<ReturnCheckService> returnCheckProvider,
                               ObjectProvider<DailyReminderService> dailyReminderProvider,
                               ObjectProvider<ActivityReminderService> activityReminderProvider,
                               ObjectProvider<MonthlyCardService> monthlyCardProvider) {
        this.returnCheckProvider = returnCheckProvider;
        this.dailyReminderProvider = dailyReminderProvider;
        this.activityReminderProvider = activityReminderProvider;
        this.monthlyCardProvider = monthlyCardProvider;
    }

    public void onLoginSuccess(int playerId, long lastLogoutAtMs) {
        if (playerId <= 0) {
            return;
        }
        ReturnCheckService ret = returnCheckProvider.getIfAvailable();
        if (ret != null) {
            try {
                ret.checkAndActivate(playerId, lastLogoutAtMs);
            } catch (Exception ignored) {
            }
        }
        DailyReminderService reminder = dailyReminderProvider.getIfAvailable();
        if (reminder != null) {
            try {
                reminder.pushOnLogin(playerId);
            } catch (Exception ignored) {
            }
        }
        ActivityReminderService activity = activityReminderProvider.getIfAvailable();
        if (activity != null) {
            try {
                activity.pushOnLogin(playerId);
            } catch (Exception ignored) {
            }
        }
        MonthlyCardService monthly = monthlyCardProvider.getIfAvailable();
        if (monthly != null) {
            try {
                monthly.grantTodayIfNeeded(playerId);
                monthly.grantTodayIfNeeded(playerId, MonthlyCardService.KIND_MINI);
            } catch (Exception ignored) {
            }
        }
    }
}
