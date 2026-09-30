package cn.itcast.demo.mylunarcore.quest;

import cn.itcast.demo.mylunarcore.battlepass.BattlePassService;
import cn.itcast.demo.mylunarcore.common.PeriodicResetService;
import cn.itcast.demo.mylunarcore.economy.WalletApplicationService;
import cn.itcast.demo.mylunarcore.repo.ItemRepository;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每日任务：目标进度 + 领奖；与 {@link PeriodicResetService} 日切耦合（按 mission_day 分区，零点不重复发奖）。
 */
@Service
public class DailyMissionService {

    private static final Logger log = LoggerFactory.getLogger(DailyMissionService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    public record MissionView(int missionId, String title, String description, int progress, int required,
                              boolean completed, boolean claimed) {}

    public record ClaimResult(boolean ok, int retcode) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RewardCfg(Integer currencyId, Integer amount, Integer itemId, Integer count) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record MissionCfg(int missionId, String title, String description, int triggerType, int targetId,
                             int required, List<RewardCfg> rewards, int battlePassXp, String timePeriod) {
        public MissionCfg {
            if (timePeriod == null || timePeriod.isBlank()) {
                timePeriod = "any";
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RootCfg(List<MissionCfg> missions) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final WalletApplicationService walletApplicationService;
    private final ItemRepository itemRepository;
    private final ObjectProvider<BattlePassService> battlePassProvider;
    private final Path dataDir;
    private final Map<Integer, MissionCfg> missions = new ConcurrentHashMap<>();
    private final ObjectProvider<cn.itcast.demo.mylunarcore.scene.WorldTimeService> worldTimeProvider;

    public DailyMissionService(JdbcTemplate jdbc,
                               ObjectMapper objectMapper,
                               WalletApplicationService walletApplicationService,
                               ItemRepository itemRepository,
                               ObjectProvider<BattlePassService> battlePassProvider,
                               PeriodicResetService periodicResetService,
                               @Value("${lunarcore.data-dir:data}") String dataDir) {
        this(jdbc, objectMapper, walletApplicationService, itemRepository, battlePassProvider,
                periodicResetService, dataDir, null);
    }

    public DailyMissionService(JdbcTemplate jdbc,
                               ObjectMapper objectMapper,
                               WalletApplicationService walletApplicationService,
                               ItemRepository itemRepository,
                               ObjectProvider<BattlePassService> battlePassProvider,
                               PeriodicResetService periodicResetService,
                               @Value("${lunarcore.data-dir:data}") String dataDir,
                               ObjectProvider<cn.itcast.demo.mylunarcore.scene.WorldTimeService> worldTimeProvider) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.walletApplicationService = walletApplicationService;
        this.itemRepository = itemRepository;
        this.battlePassProvider = battlePassProvider;
        this.dataDir = Path.of(dataDir);
        this.worldTimeProvider = worldTimeProvider;
        // 日切时仅打水位日志；进度按 mission_day 自然隔离，避免跨日重复发奖
        periodicResetService.registerDaily(day ->
                log.info("DailyMission day-roll day={} catalogSize={}", day, missions.size()));
    }

    @PostConstruct
    public void loadConfig() {
        Path file = dataDir.resolve("DailyMissionConfigs.json");
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            RootCfg root = objectMapper.readValue(Files.readString(file), RootCfg.class);
            missions.clear();
            if (root != null && root.missions() != null) {
                for (MissionCfg m : root.missions()) {
                    missions.put(m.missionId(), m);
                }
            }
        } catch (Exception e) {
            log.warn("load DailyMissionConfigs failed: {}", e.toString());
        }
    }

    public List<MissionView> listToday(int playerId) {
        String day = today();
        List<MissionView> out = new ArrayList<>();
        for (MissionCfg cfg : missions.values()) {
            if (!matchesWorldTime(cfg)) {
                continue;
            }
            Row row = loadRow(playerId, day, cfg.missionId());
            int progress = row == null ? 0 : row.progress;
            boolean claimed = row != null && row.claimed;
            int required = Math.max(1, cfg.required());
            out.add(new MissionView(cfg.missionId(), cfg.title(), cfg.description(),
                    progress, required, progress >= required, claimed));
        }
        return out;
    }

    /** 与 QuestTriggerEngine 同源触发：按 triggerType/targetId 推进当日任务。 */
    public void onTrigger(int playerId, int triggerType, long p1) {
        if (playerId <= 0 || missions.isEmpty()) {
            return;
        }
        String day = today();
        for (MissionCfg cfg : missions.values()) {
            if (cfg.triggerType() != triggerType) {
                continue;
            }
            if (!matchesWorldTime(cfg)) {
                continue;
            }
            if (cfg.targetId() > 0 && cfg.targetId() != (int) p1) {
                continue;
            }
            bump(playerId, day, cfg.missionId(), Math.max(1, cfg.required()));
        }
    }

    private boolean matchesWorldTime(MissionCfg cfg) {
        cn.itcast.demo.mylunarcore.scene.WorldTimeService wts =
                worldTimeProvider == null ? null : worldTimeProvider.getIfAvailable();
        if (wts == null) {
            return true;
        }
        return wts.matchesPeriodFilter(cfg.timePeriod());
    }

    @Transactional
    public ClaimResult claim(int playerId, int missionId) {
        MissionCfg cfg = missions.get(missionId);
        if (cfg == null || playerId <= 0) {
            return new ClaimResult(false, 2);
        }
        String day = today();
        Row row = loadRow(playerId, day, missionId);
        if (row == null || row.progress < Math.max(1, cfg.required())) {
            return new ClaimResult(false, 3);
        }
        if (row.claimed) {
            return new ClaimResult(false, 4);
        }
        try {
            int n = jdbc.update("""
                    UPDATE daily_mission_progress SET claimed = 1, updated_at = NOW(3)
                    WHERE player_id = ? AND mission_day = ? AND mission_id = ? AND claimed = 0
                    """, playerId, day, missionId);
            if (n <= 0) {
                return new ClaimResult(false, 4);
            }
        } catch (Exception e) {
            return new ClaimResult(false, 5);
        }
        grantRewards(playerId, cfg);
        BattlePassService bp = battlePassProvider.getIfAvailable();
        if (bp != null && cfg.battlePassXp() > 0) {
            bp.addXp(playerId, cfg.battlePassXp());
        }
        return new ClaimResult(true, 0);
    }

    private void grantRewards(int playerId, MissionCfg cfg) {
        if (cfg.rewards() == null) {
            return;
        }
        for (RewardCfg r : cfg.rewards()) {
            try {
                if (r.currencyId() != null && r.amount() != null && r.amount() > 0) {
                    walletApplicationService.add(playerId, r.currencyId(), r.amount(), "daily_mission");
                }
                if (r.itemId() != null && r.count() != null && r.count() > 0) {
                    itemRepository.addSimpleItem(playerId, r.itemId(), 3, r.count());
                }
            } catch (Exception e) {
                log.debug("daily mission reward skipped: {}", e.getMessage());
            }
        }
    }

    private void bump(int playerId, String day, int missionId, int required) {
        try {
            jdbc.update("""
                    INSERT INTO daily_mission_progress (player_id, mission_day, mission_id, progress, claimed)
                    VALUES (?, ?, ?, 1, 0)
                    ON DUPLICATE KEY UPDATE progress = LEAST(?, progress + 1)
                    """, playerId, day, missionId, required);
        } catch (Exception e) {
            log.debug("daily mission bump skipped: {}", e.getMessage());
        }
    }

    private Row loadRow(int playerId, String day, int missionId) {
        try {
            List<Row> list = jdbc.query("""
                    SELECT progress, claimed FROM daily_mission_progress
                    WHERE player_id = ? AND mission_day = ? AND mission_id = ?
                    """, (rs, i) -> new Row(rs.getInt(1), rs.getInt(2) != 0), playerId, day, missionId);
            return list.isEmpty() ? null : list.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    private static String today() {
        return LocalDate.now(ZONE).toString();
    }

    private record Row(int progress, boolean claimed) {}
}
