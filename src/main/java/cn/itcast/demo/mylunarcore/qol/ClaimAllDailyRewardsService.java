package cn.itcast.demo.mylunarcore.qol;

import cn.itcast.demo.mylunarcore.achievement.AchievementService;
import cn.itcast.demo.mylunarcore.activity.ActivityTemplateService;
import cn.itcast.demo.mylunarcore.battlepass.BattlePassService;
import cn.itcast.demo.mylunarcore.hall.MailApplicationService;
import cn.itcast.demo.mylunarcore.model.MailEntity;
import cn.itcast.demo.mylunarcore.quest.DailyMissionService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 一键领取日常奖励：按配置聚合每日任务、战令、邮件、成就、签到等模块，失败项跳过并汇总。
 */
@Service
public class ClaimAllDailyRewardsService {

    private static final Logger log = LoggerFactory.getLogger(ClaimAllDailyRewardsService.class);

    public record ModuleSummary(String module, int successCount, int skipCount, String detail) {}

    public record ClaimAllResult(boolean ok, int retcode, List<ModuleSummary> summaries, int totalClaimed) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ModuleSwitch(boolean dailyMission, boolean battlePass, boolean mail,
                               boolean achievement, boolean signIn, boolean excludeHighValueMail) {
        static ModuleSwitch defaults() {
            return new ModuleSwitch(true, true, true, true, true, true);
        }
    }

    private final ObjectMapper objectMapper;
    private final Path dataDir;
    private final ObjectProvider<DailyMissionService> dailyMissionProvider;
    private final ObjectProvider<BattlePassService> battlePassProvider;
    private final ObjectProvider<MailApplicationService> mailProvider;
    private final ObjectProvider<AchievementService> achievementProvider;
    private final ObjectProvider<ActivityTemplateService> activityTemplateProvider;
    private volatile ModuleSwitch switches = ModuleSwitch.defaults();

    public ClaimAllDailyRewardsService(ObjectMapper objectMapper,
                                       @Value("${lunarcore.data-dir:data}") String dataDir,
                                       ObjectProvider<DailyMissionService> dailyMissionProvider,
                                       ObjectProvider<BattlePassService> battlePassProvider,
                                       ObjectProvider<MailApplicationService> mailProvider,
                                       ObjectProvider<AchievementService> achievementProvider,
                                       ObjectProvider<ActivityTemplateService> activityTemplateProvider) {
        this.objectMapper = objectMapper;
        this.dataDir = Path.of(dataDir);
        this.dailyMissionProvider = dailyMissionProvider;
        this.battlePassProvider = battlePassProvider;
        this.mailProvider = mailProvider;
        this.achievementProvider = achievementProvider;
        this.activityTemplateProvider = activityTemplateProvider;
    }

    @PostConstruct
    public void loadConfig() {
        Path file = dataDir.resolve("ClaimAllDailyRewardsConfig.json");
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            ModuleSwitch cfg = objectMapper.readValue(Files.readString(file), ModuleSwitch.class);
            if (cfg != null) {
                switches = cfg;
            }
        } catch (Exception e) {
            log.warn("load ClaimAllDailyRewardsConfig failed: {}", e.toString());
        }
    }

    public ClaimAllResult claimAll(int playerId) {
        if (playerId <= 0) {
            return new ClaimAllResult(false, 1, List.of(), 0);
        }
        List<ModuleSummary> summaries = new ArrayList<>();
        int total = 0;
        ModuleSwitch sw = switches;

        if (sw.dailyMission()) {
            ModuleSummary s = claimDailyMissions(playerId);
            summaries.add(s);
            total += s.successCount();
        }
        if (sw.battlePass()) {
            ModuleSummary s = claimBattlePass(playerId);
            summaries.add(s);
            total += s.successCount();
        }
        if (sw.mail()) {
            ModuleSummary s = claimMails(playerId, sw.excludeHighValueMail());
            summaries.add(s);
            total += s.successCount();
        }
        if (sw.achievement()) {
            ModuleSummary s = claimAchievements(playerId);
            summaries.add(s);
            total += s.successCount();
        }
        if (sw.signIn()) {
            ModuleSummary s = claimSignIn(playerId);
            summaries.add(s);
            total += s.successCount();
        }
        return new ClaimAllResult(true, 0, summaries, total);
    }

    private ModuleSummary claimDailyMissions(int playerId) {
        DailyMissionService svc = dailyMissionProvider.getIfAvailable();
        if (svc == null) {
            return new ModuleSummary("daily_mission", 0, 0, "unavailable");
        }
        int ok = 0;
        int skip = 0;
        for (DailyMissionService.MissionView m : svc.listToday(playerId)) {
            if (!m.completed() || m.claimed()) {
                continue;
            }
            DailyMissionService.ClaimResult r = svc.claim(playerId, m.missionId());
            if (r.ok()) {
                ok++;
            } else {
                skip++;
            }
        }
        return new ModuleSummary("daily_mission", ok, skip, "claimed=" + ok);
    }

    private ModuleSummary claimBattlePass(int playerId) {
        BattlePassService svc = battlePassProvider.getIfAvailable();
        if (svc == null) {
            return new ModuleSummary("battle_pass", 0, 0, "unavailable");
        }
        BattlePassService.ProgressView progress = svc.getProgress(playerId);
        int ok = 0;
        int skip = 0;
        for (int lv = 1; lv <= progress.level(); lv++) {
            if (!progress.claimedFree().contains(lv)) {
                BattlePassService.ClaimResult r = svc.claimLevel(playerId, lv, false);
                if (r.ok()) {
                    ok++;
                } else {
                    skip++;
                }
            }
            if (progress.premium() && !progress.claimedPremium().contains(lv)) {
                BattlePassService.ClaimResult r = svc.claimLevel(playerId, lv, true);
                if (r.ok()) {
                    ok++;
                } else {
                    skip++;
                }
            }
        }
        return new ModuleSummary("battle_pass", ok, skip, "level=" + progress.level());
    }

    private ModuleSummary claimMails(int playerId, boolean excludeHighValue) {
        MailApplicationService svc = mailProvider.getIfAvailable();
        if (svc == null) {
            return new ModuleSummary("mail", 0, 0, "unavailable");
        }
        int ok = 0;
        int skip = 0;
        List<MailEntity> mails = svc.listMails(playerId, 1, 50);
        for (MailEntity mail : mails) {
            if (mail == null || mail.getStatus() >= 2) {
                continue;
            }
            if (excludeHighValue && looksHighValue(mail)) {
                skip++;
                continue;
            }
            MailApplicationService.ClaimResult r = svc.claim(playerId, mail.getId());
            if (r.success()) {
                ok++;
            } else {
                skip++;
            }
        }
        return new ModuleSummary("mail", ok, skip, excludeHighValue ? "exclude_high_value" : "all");
    }

    private boolean looksHighValue(MailEntity mail) {
        String title = mail.getTitle() == null ? "" : mail.getTitle().toLowerCase();
        String json = mail.getAttachmentsJson() == null ? "" : mail.getAttachmentsJson();
        return title.contains("充值") || title.contains("补偿") || title.contains("vip")
                || json.contains("\"currencyAmount\":") && json.matches("(?s).*\"currencyAmount\"\\s*:\\s*[1-9]\\d{3,}.*");
    }

    private ModuleSummary claimAchievements(int playerId) {
        AchievementService svc = achievementProvider.getIfAvailable();
        if (svc == null) {
            return new ModuleSummary("achievement", 0, 0, "unavailable");
        }
        int ok = 0;
        int skip = 0;
        for (Map<String, Object> row : svc.listForPlayer(playerId)) {
            boolean claimed = Boolean.TRUE.equals(row.get("claimed"));
            int progress = ((Number) row.getOrDefault("progress", 0)).intValue();
            int target = ((Number) row.getOrDefault("target", 1)).intValue();
            if (claimed || progress < target) {
                continue;
            }
            String id = String.valueOf(row.get("id"));
            if (svc.claim(playerId, id)) {
                ok++;
            } else {
                skip++;
            }
        }
        return new ModuleSummary("achievement", ok, skip, "ok");
    }

    private ModuleSummary claimSignIn(int playerId) {
        ActivityTemplateService svc = activityTemplateProvider.getIfAvailable();
        if (svc == null) {
            return new ModuleSummary("sign_in", 0, 0, "unavailable");
        }
        try {
            ActivityTemplateService.OpResult r = svc.invokeScript("sign_in_bonus", playerId, 0,
                    Map.of("action", "claim_today"));
            if (r.success()) {
                return new ModuleSummary("sign_in", 1, 0, "claimed");
            }
            return new ModuleSummary("sign_in", 0, 1, "ret=" + r.retcode());
        } catch (Exception e) {
            return new ModuleSummary("sign_in", 0, 1, e.getMessage());
        }
    }
}
