package cn.itcast.demo.mylunarcore.qol;

import cn.itcast.demo.mylunarcore.achievement.AchievementService;
import cn.itcast.demo.mylunarcore.activity.ActivityTemplateService;
import cn.itcast.demo.mylunarcore.battlepass.BattlePassService;
import cn.itcast.demo.mylunarcore.hall.MailApplicationService;
import cn.itcast.demo.mylunarcore.quest.DailyMissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClaimAllDailyRewardsServiceTest {

    @Test
    void claimAllAggregatesDailyMissions() throws Exception {
        Path dir = Files.createTempDirectory("claim-all");
        Files.writeString(dir.resolve("ClaimAllDailyRewardsConfig.json"), """
                {"dailyMission":true,"battlePass":false,"mail":false,"achievement":false,"signIn":false,"excludeHighValueMail":true}
                """);

        DailyMissionService daily = mock(DailyMissionService.class);
        when(daily.listToday(1001)).thenReturn(List.of(
                new DailyMissionService.MissionView(1, "t", "d", 1, 1, true, false)));
        when(daily.claim(1001, 1)).thenReturn(new DailyMissionService.ClaimResult(true, 0));

        @SuppressWarnings("unchecked")
        ObjectProvider<DailyMissionService> dailyProvider = mock(ObjectProvider.class);
        when(dailyProvider.getIfAvailable()).thenReturn(daily);
        @SuppressWarnings("unchecked")
        ObjectProvider<BattlePassService> bp = mock(ObjectProvider.class);
        when(bp.getIfAvailable()).thenReturn(null);
        @SuppressWarnings("unchecked")
        ObjectProvider<MailApplicationService> mail = mock(ObjectProvider.class);
        when(mail.getIfAvailable()).thenReturn(null);
        @SuppressWarnings("unchecked")
        ObjectProvider<AchievementService> ach = mock(ObjectProvider.class);
        when(ach.getIfAvailable()).thenReturn(null);
        @SuppressWarnings("unchecked")
        ObjectProvider<ActivityTemplateService> act = mock(ObjectProvider.class);
        when(act.getIfAvailable()).thenReturn(null);

        ClaimAllDailyRewardsService svc = new ClaimAllDailyRewardsService(
                new ObjectMapper(), dir.toString(), dailyProvider, bp, mail, ach, act);
        svc.loadConfig();
        ClaimAllDailyRewardsService.ClaimAllResult r = svc.claimAll(1001);
        assertTrue(r.ok());
        assertEquals(0, r.retcode());
        assertEquals(1, r.totalClaimed());
        assertEquals("daily_mission", r.summaries().get(0).module());
    }
}
