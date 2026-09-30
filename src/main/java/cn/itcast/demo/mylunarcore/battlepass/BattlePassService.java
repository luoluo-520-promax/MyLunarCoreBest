package cn.itcast.demo.mylunarcore.battlepass;

import cn.itcast.demo.mylunarcore.common.PeriodicResetService;
import cn.itcast.demo.mylunarcore.economy.WalletApplicationService;
import cn.itcast.demo.mylunarcore.repo.ItemRepository;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 战令/大月卡进度：XP 升级、免费/付费轨领奖；周重置由 PeriodicReset 挂钩（赛季级不清零 XP）。
 */
@Service
public class BattlePassService {

    private static final Logger log = LoggerFactory.getLogger(BattlePassService.class);

    public record ProgressView(int seasonId, int xp, int level, int maxLevel, boolean premium,
                               Set<Integer> claimedFree, Set<Integer> claimedPremium) {}

    public record ClaimResult(boolean ok, int retcode) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RewardCfg(int level, Integer currencyId, Integer amount, Integer itemId, Integer count) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PassConfig(int seasonId, String seasonKey, int maxLevel, int xpPerLevel,
                             List<RewardCfg> freeRewards, List<RewardCfg> premiumRewards) {
        static PassConfig defaults() {
            return new PassConfig(1, "1.0", 50, 1000, List.of(), List.of());
        }
    }

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final WalletApplicationService walletApplicationService;
    private final ItemRepository itemRepository;
    private final Path dataDir;
    private final AtomicReference<PassConfig> config = new AtomicReference<>(PassConfig.defaults());

    public BattlePassService(JdbcTemplate jdbc,
                             ObjectMapper objectMapper,
                             WalletApplicationService walletApplicationService,
                             ItemRepository itemRepository,
                             PeriodicResetService periodicResetService,
                             @Value("${lunarcore.data-dir:data}") String dataDir) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.walletApplicationService = walletApplicationService;
        this.itemRepository = itemRepository;
        this.dataDir = Path.of(dataDir);
        periodicResetService.registerWeekly(week ->
                log.info("BattlePass weekly tick week={} season={}", week, config.get().seasonId()));
    }

    @PostConstruct
    public void loadConfig() {
        Path file = dataDir.resolve("BattlePassConfigs.json");
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            PassConfig cfg = objectMapper.readValue(Files.readString(file), PassConfig.class);
            if (cfg != null && cfg.seasonId() > 0) {
                config.set(cfg);
            }
        } catch (Exception e) {
            log.warn("load BattlePassConfigs failed: {}", e.toString());
        }
    }

    public ProgressView getProgress(int playerId) {
        ensureRow(playerId);
        PassConfig cfg = config.get();
        Row row = loadRow(playerId, cfg.seasonId());
        if (row == null) {
            return new ProgressView(cfg.seasonId(), 0, 0, cfg.maxLevel(), false, Set.of(), Set.of());
        }
        return new ProgressView(cfg.seasonId(), row.xp, row.level, cfg.maxLevel(), row.premium,
                row.claimedFree, row.claimedPremium);
    }

    public void addXp(int playerId, int xp) {
        if (playerId <= 0 || xp <= 0) {
            return;
        }
        PassConfig cfg = config.get();
        ensureRow(playerId);
        try {
            jdbc.update("""
                    UPDATE battle_pass_progress
                    SET xp = xp + ?,
                        level = LEAST(?, FLOOR((xp + ?) / ?)),
                        updated_at = NOW(3)
                    WHERE player_id = ? AND season_id = ?
                    """, xp, cfg.maxLevel(), xp, Math.max(1, cfg.xpPerLevel()), playerId, cfg.seasonId());
            // 上面 FLOOR((xp+?) 在 MySQL 中 xp 仍是旧值；再校正一次
            Row row = loadRow(playerId, cfg.seasonId());
            if (row != null) {
                int level = Math.min(cfg.maxLevel(), row.xp / Math.max(1, cfg.xpPerLevel()));
                if (level != row.level) {
                    jdbc.update("UPDATE battle_pass_progress SET level = ? WHERE player_id = ? AND season_id = ?",
                            level, playerId, cfg.seasonId());
                }
            }
        } catch (Exception e) {
            log.debug("battle pass xp skipped: {}", e.getMessage());
        }
    }

    @Transactional
    public ClaimResult claimLevel(int playerId, int level, boolean premiumTrack) {
        PassConfig cfg = config.get();
        if (playerId <= 0 || level <= 0 || level > cfg.maxLevel()) {
            return new ClaimResult(false, 2);
        }
        ensureRow(playerId);
        Row row = loadRow(playerId, cfg.seasonId());
        if (row == null || row.level < level) {
            return new ClaimResult(false, 3);
        }
        if (premiumTrack && !row.premium) {
            return new ClaimResult(false, 4);
        }
        Set<Integer> claimed = premiumTrack ? new HashSet<>(row.claimedPremium) : new HashSet<>(row.claimedFree);
        if (claimed.contains(level)) {
            return new ClaimResult(false, 5);
        }
        RewardCfg reward = findReward(premiumTrack ? cfg.premiumRewards() : cfg.freeRewards(), level);
        if (reward == null) {
            return new ClaimResult(false, 6);
        }
        claimed.add(level);
        try {
            String json = objectMapper.writeValueAsString(claimed);
            if (premiumTrack) {
                jdbc.update("""
                        UPDATE battle_pass_progress SET claimed_premium_json = ?, updated_at = NOW(3)
                        WHERE player_id = ? AND season_id = ?
                        """, json, playerId, cfg.seasonId());
            } else {
                jdbc.update("""
                        UPDATE battle_pass_progress SET claimed_free_json = ?, updated_at = NOW(3)
                        WHERE player_id = ? AND season_id = ?
                        """, json, playerId, cfg.seasonId());
            }
        } catch (Exception e) {
            return new ClaimResult(false, 7);
        }
        grant(playerId, reward);
        return new ClaimResult(true, 0);
    }

    public ClaimResult unlockPremium(int playerId) {
        ensureRow(playerId);
        try {
            jdbc.update("""
                    UPDATE battle_pass_progress SET premium = 1, updated_at = NOW(3)
                    WHERE player_id = ? AND season_id = ?
                    """, playerId, config.get().seasonId());
            return new ClaimResult(true, 0);
        } catch (Exception e) {
            return new ClaimResult(false, 5);
        }
    }

    private void grant(int playerId, RewardCfg reward) {
        if (reward.currencyId() != null && reward.amount() != null && reward.amount() > 0) {
            walletApplicationService.add(playerId, reward.currencyId(), reward.amount(), "battle_pass");
        }
        if (reward.itemId() != null && reward.count() != null && reward.count() > 0) {
            try {
                itemRepository.addSimpleItem(playerId, reward.itemId(), 3, reward.count());
            } catch (Exception e) {
                log.debug("battle pass item grant skipped: {}", e.getMessage());
            }
        }
    }

    private static RewardCfg findReward(List<RewardCfg> list, int level) {
        if (list == null) {
            return null;
        }
        for (RewardCfg r : list) {
            if (r.level() == level) {
                return r;
            }
        }
        return null;
    }

    private void ensureRow(int playerId) {
        PassConfig cfg = config.get();
        try {
            jdbc.update("""
                    INSERT IGNORE INTO battle_pass_progress
                    (player_id, season_id, xp, level, premium, claimed_free_json, claimed_premium_json)
                    VALUES (?, ?, 0, 0, 0, '[]', '[]')
                    """, playerId, cfg.seasonId());
        } catch (Exception ignored) {
        }
    }

    private Row loadRow(int playerId, int seasonId) {
        try {
            List<Row> list = jdbc.query("""
                    SELECT xp, level, premium, claimed_free_json, claimed_premium_json
                    FROM battle_pass_progress WHERE player_id = ? AND season_id = ?
                    """, (rs, i) -> new Row(
                    rs.getInt(1), rs.getInt(2), rs.getInt(3) != 0,
                    parseSet(rs.getString(4)), parseSet(rs.getString(5))), playerId, seasonId);
            return list.isEmpty() ? null : list.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    private Set<Integer> parseSet(String json) {
        if (json == null || json.isBlank()) {
            return new HashSet<>();
        }
        try {
            return new HashSet<>(objectMapper.readValue(json, new TypeReference<List<Integer>>() {}));
        } catch (Exception e) {
            return new HashSet<>();
        }
    }

    private record Row(int xp, int level, boolean premium, Set<Integer> claimedFree, Set<Integer> claimedPremium) {}
}
