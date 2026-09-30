package cn.itcast.demo.mylunarcore.player;

import cn.itcast.demo.mylunarcore.common.PeriodicResetService;
import cn.itcast.demo.mylunarcore.economy.WalletApplicationService;
import cn.itcast.demo.mylunarcore.guild.GuildTechService;
import cn.itcast.demo.mylunarcore.model.PlayerEntity;
import cn.itcast.demo.mylunarcore.repo.PlayerDataRepository;
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
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 全局体力（树脂）系统：自然恢复、打本消耗、货币购买；日购次数随 PeriodicReset 清零。
 */
@Service
public class StaminaService {

    private static final Logger log = LoggerFactory.getLogger(StaminaService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    public record StaminaSnapshot(int current, int max, int dailyBuyCount, int dailyBuyLimit, long nextRegenAtMs,
                                  int reserve, int reserveCap) {
        public StaminaSnapshot(int current, int max, int dailyBuyCount, int dailyBuyLimit, long nextRegenAtMs) {
            this(current, max, dailyBuyCount, dailyBuyLimit, nextRegenAtMs, 0, 0);
        }
    }

    public record ConsumeResult(boolean ok, int retcode, int remaining) {
        public static ConsumeResult fail(int retcode) { return new ConsumeResult(false, retcode, 0); }
        public static ConsumeResult ok(int remaining) { return new ConsumeResult(true, 0, remaining); }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StaminaConfig(int maxStamina, int regenPerMinute, long regenIntervalMs, int dungeonCost,
                                int materialFarmCost, int dailyBuyLimit, int buyCostCurrencyId,
                                List<Integer> buyCostAmounts, int buyGrantAmount) {
        static StaminaConfig defaults() {
            return new StaminaConfig(240, 1, 60_000L, 40, 20, 8, 101,
                    List.of(50, 100, 150, 200, 250, 300, 350, 400), 60);
        }
    }

    private final JdbcTemplate jdbc;
    private final PlayerDataRepository playerDataRepository;
    private final WalletApplicationService walletApplicationService;
    private final ObjectMapper objectMapper;
    private final Path dataDir;
    private final ObjectProvider<GuildTechService> guildTechProvider;
    private final ObjectProvider<StaminaOverflowService> overflowProvider;
    private final AtomicReference<StaminaConfig> config = new AtomicReference<>(StaminaConfig.defaults());

    public StaminaService(JdbcTemplate jdbc,
                          PlayerDataRepository playerDataRepository,
                          WalletApplicationService walletApplicationService,
                          ObjectMapper objectMapper,
                          PeriodicResetService periodicResetService,
                          ObjectProvider<GuildTechService> guildTechProvider,
                          @Value("${lunarcore.data-dir:data}") String dataDir) {
        this(jdbc, playerDataRepository, walletApplicationService, objectMapper, periodicResetService,
                guildTechProvider, dataDir, null);
    }

    public StaminaService(JdbcTemplate jdbc,
                          PlayerDataRepository playerDataRepository,
                          WalletApplicationService walletApplicationService,
                          ObjectMapper objectMapper,
                          PeriodicResetService periodicResetService,
                          ObjectProvider<GuildTechService> guildTechProvider,
                          @Value("${lunarcore.data-dir:data}") String dataDir,
                          ObjectProvider<StaminaOverflowService> overflowProvider) {
        this.jdbc = jdbc;
        this.playerDataRepository = playerDataRepository;
        this.walletApplicationService = walletApplicationService;
        this.objectMapper = objectMapper;
        this.guildTechProvider = guildTechProvider;
        this.overflowProvider = overflowProvider;
        this.dataDir = Path.of(dataDir);
        periodicResetService.registerDaily(day -> resetDailyBuys(day.toString()));
    }

    @PostConstruct
    public void loadConfig() {
        Path file = dataDir.resolve("StaminaConfigs.json");
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            StaminaConfig cfg = objectMapper.readValue(Files.readString(file), StaminaConfig.class);
            if (cfg != null && cfg.maxStamina() > 0) {
                config.set(cfg);
            }
        } catch (Exception e) {
            log.warn("load StaminaConfigs failed: {}", e.toString());
        }
    }

    public StaminaConfig config() {
        return config.get();
    }

    public StaminaSnapshot snapshot(int playerId) {
        recover(playerId);
        int cur = readStamina(playerId);
        StaminaConfig cfg = config.get();
        Meta meta = loadMeta(playerId);
        return new StaminaSnapshot(cur, cfg.maxStamina(), meta.dailyBuyCount, cfg.dailyBuyLimit(),
                meta.lastRegenAtMs + cfg.regenIntervalMs(), reserveOf(playerId), reserveCap());
    }

    /** Tick 驱动自然恢复（含公会科技恢复加成）。 */
    public void onTick(int playerId, long nowMillis, long deltaMillis) {
        if (deltaMillis <= 0) {
            return;
        }
        recoverAt(playerId, nowMillis);
    }

    public void recover(int playerId) {
        recoverAt(playerId, System.currentTimeMillis());
    }

    private void recoverAt(int playerId, long nowMillis) {
        int cur = readStamina(playerId);
        if (cur < 0) {
            return;
        }
        StaminaConfig cfg = config.get();
        int max = cfg.maxStamina();
        Meta meta = loadMeta(playerId);
        long last = meta.lastRegenAtMs > 0 ? meta.lastRegenAtMs : nowMillis;
        long interval = Math.max(1_000L, cfg.regenIntervalMs());
        double regenBonus = 1.0;
        GuildTechService tech = guildTechProvider.getIfAvailable();
        if (tech != null) {
            regenBonus += tech.buffForPlayer(playerId).staminaRegenBonusPct();
        }
        long elapsed = Math.max(0L, nowMillis - last);
        int ticks = (int) (elapsed / interval);
        if (ticks <= 0) {
            return;
        }
        int gain = (int) Math.floor(ticks * Math.max(1, cfg.regenPerMinute()) * regenBonus);
        if (gain <= 0) {
            return;
        }
        if (cur >= max) {
            StaminaOverflowService overflow = overflow();
            if (overflow != null) {
                overflow.absorbOverflowRegen(playerId, gain);
            }
            touchMeta(playerId, last + ticks * interval, null);
            return;
        }
        int room = max - cur;
        int applied = Math.min(room, gain);
        writeStamina(playerId, cur + applied);
        int wasted = gain - applied;
        if (wasted > 0) {
            StaminaOverflowService overflow = overflow();
            if (overflow != null) {
                overflow.absorbOverflowRegen(playerId, wasted);
            }
        }
        touchMeta(playerId, last + ticks * interval, null);
    }

    /** 消耗体力；retcode: 2=不足 3=非法参数。 */
    @Transactional
    public ConsumeResult tryConsume(int playerId, int amount) {
        if (playerId <= 0 || amount <= 0) {
            return ConsumeResult.fail(3);
        }
        recover(playerId);
        int cur = readStamina(playerId);
        if (cur < 0) {
            return ConsumeResult.fail(3);
        }
        if (cur < amount) {
            StaminaOverflowService overflow = overflow();
            if (overflow != null) {
                int need = amount - cur;
                int took = overflow.withdraw(playerId, need);
                if (took > 0) {
                    writeStamina(playerId, cur + took);
                    cur = readStamina(playerId);
                }
            }
        }
        if (cur < amount) {
            return ConsumeResult.fail(2);
        }
        int next = cur - amount;
        writeStamina(playerId, next);
        return ConsumeResult.ok(next);
    }

    public ConsumeResult tryConsumeDungeon(int playerId) {
        return tryConsume(playerId, config.get().dungeonCost());
    }

    public ConsumeResult tryConsumeMaterialFarm(int playerId) {
        return tryConsume(playerId, config.get().materialFarmCost());
    }

    /** 购买体力；retcode: 2=日限 3=货币不足 4=已满。 */
    @Transactional
    public ConsumeResult buyStamina(int playerId) {
        if (playerId <= 0) {
            return ConsumeResult.fail(3);
        }
        recover(playerId);
        StaminaConfig cfg = config.get();
        int cur = readStamina(playerId);
        if (cur < 0) {
            return ConsumeResult.fail(3);
        }
        if (cur >= cfg.maxStamina()) {
            return ConsumeResult.fail(4);
        }
        String today = LocalDate.now(ZONE).toString();
        Meta meta = loadMeta(playerId);
        if (!today.equals(meta.buyDay)) {
            meta = new Meta(meta.lastRegenAtMs, 0, today);
        }
        if (meta.dailyBuyCount >= cfg.dailyBuyLimit()) {
            return ConsumeResult.fail(2);
        }
        List<Integer> costs = cfg.buyCostAmounts() == null ? List.of() : cfg.buyCostAmounts();
        int cost = costs.isEmpty() ? 50 : costs.get(Math.min(meta.dailyBuyCount, costs.size() - 1));
        WalletApplicationService.WalletChangeResult paid =
                walletApplicationService.deduct(playerId, cfg.buyCostCurrencyId(), cost, "stamina_buy");
        if (!paid.success()) {
            return ConsumeResult.fail(3);
        }
        int grant = Math.max(1, cfg.buyGrantAmount());
        int next = Math.min(cfg.maxStamina(), cur + grant);
        writeStamina(playerId, next);
        touchMeta(playerId, meta.lastRegenAtMs, new Meta(meta.lastRegenAtMs, meta.dailyBuyCount + 1, today));
        return ConsumeResult.ok(next);
    }

    /** 月卡/活动等额外体力发放（可超过自然上限的缓冲由调用方控制；此处封顶 max*1.5）。 */
    @Transactional
    public ConsumeResult addBonus(int playerId, int amount, String reason) {
        if (playerId <= 0 || amount <= 0) {
            return ConsumeResult.fail(3);
        }
        recover(playerId);
        int cur = readStamina(playerId);
        if (cur < 0) {
            return ConsumeResult.fail(3);
        }
        int cap = (int) Math.min(Integer.MAX_VALUE, Math.floor(config.get().maxStamina() * 1.5));
        writeStamina(playerId, Math.min(cap, cur + amount));
        return ConsumeResult.ok(readStamina(playerId));
    }

    /** 从后备池提取到当前体力；retcode: 2=储备不足 3=非法 4=已满。 */
    @Transactional
    public ConsumeResult claimReserve(int playerId, int amount) {
        if (playerId <= 0) {
            return ConsumeResult.fail(3);
        }
        recover(playerId);
        StaminaOverflowService overflow = overflow();
        if (overflow == null) {
            return ConsumeResult.fail(3);
        }
        int cur = readStamina(playerId);
        int max = config.get().maxStamina();
        if (cur >= max) {
            return ConsumeResult.fail(4);
        }
        int want = amount <= 0 ? (max - cur) : amount;
        want = Math.min(want, max - cur);
        int took = overflow.withdraw(playerId, want);
        if (took <= 0) {
            return ConsumeResult.fail(2);
        }
        writeStamina(playerId, cur + took);
        return ConsumeResult.ok(readStamina(playerId));
    }

    private StaminaOverflowService overflow() {
        return overflowProvider == null ? null : overflowProvider.getIfAvailable();
    }

    private int reserveOf(int playerId) {
        StaminaOverflowService overflow = overflow();
        return overflow == null ? 0 : overflow.readReserve(playerId);
    }

    private int reserveCap() {
        return StaminaOverflowService.DEFAULT_RESERVE_CAP;
    }

    private int readStamina(int playerId) {
        PlayerEntity p = playerDataRepository.loadPlayerByUid(playerId);
        return p == null ? -1 : Math.max(0, p.getStamina());
    }

    private void writeStamina(int playerId, int stamina) {
        try {
            jdbc.update("UPDATE player SET stamina = ?, updated_at = NOW() WHERE uid = ?",
                    Math.max(0, stamina), playerId);
        } catch (Exception e) {
            log.warn("write stamina failed uid={}: {}", playerId, e.toString());
        }
    }

    private void resetDailyBuys(String day) {
        try {
            int n = jdbc.update("UPDATE player_stamina_meta SET daily_buy_count = 0, buy_day = ?", day);
            log.info("Stamina daily buy reset day={} rows={}", day, n);
        } catch (Exception e) {
            log.debug("stamina daily reset skipped: {}", e.getMessage());
        }
    }

    private Meta loadMeta(int playerId) {
        try {
            List<Meta> list = jdbc.query("""
                    SELECT last_regen_at_ms, daily_buy_count, buy_day FROM player_stamina_meta WHERE player_id = ?
                    """, (rs, i) -> new Meta(rs.getLong(1), rs.getInt(2), rs.getString(3)), playerId);
            if (!list.isEmpty()) {
                return list.get(0);
            }
        } catch (Exception ignored) {
            // 表未就绪时内存默认
        }
        return new Meta(System.currentTimeMillis(), 0, LocalDate.now(ZONE).toString());
    }

    private void touchMeta(int playerId, long lastRegenAtMs, Meta buyOverride) {
        Meta cur = buyOverride != null ? buyOverride : loadMeta(playerId);
        try {
            jdbc.update("""
                    INSERT INTO player_stamina_meta (player_id, last_regen_at_ms, daily_buy_count, buy_day)
                    VALUES (?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE last_regen_at_ms=VALUES(last_regen_at_ms),
                      daily_buy_count=VALUES(daily_buy_count), buy_day=VALUES(buy_day)
                    """, playerId, lastRegenAtMs, cur.dailyBuyCount, cur.buyDay == null ? "" : cur.buyDay);
        } catch (Exception e) {
            log.debug("stamina meta upsert skipped: {}", e.getMessage());
        }
    }

    private static final class Meta {
        final long lastRegenAtMs;
        final int dailyBuyCount;
        final String buyDay;

        Meta(long lastRegenAtMs, int dailyBuyCount, String buyDay) {
            this.lastRegenAtMs = lastRegenAtMs;
            this.dailyBuyCount = dailyBuyCount;
            this.buyDay = buyDay;
        }
    }
}
