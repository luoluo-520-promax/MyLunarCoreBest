package cn.itcast.demo.mylunarcore.character;

import cn.itcast.demo.mylunarcore.item.ItemApplicationService;
import cn.itcast.demo.mylunarcore.model.AvatarEntity;
import cn.itcast.demo.mylunarcore.model.GameItemEntity;
import cn.itcast.demo.mylunarcore.player.StaminaService;
import cn.itcast.demo.mylunarcore.repo.AvatarRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 养成计划：按目标等级汇总材料缺口，并按掉落效率给出推荐关卡顺序。
 */
@Service
public class DevelopmentPlanService {

    public static final int MAX_LEVEL = 80;

    public record MaterialNeed(int itemId, int required, int owned, int deficit) {}

    public record StageAdvice(int stageId, String stageName, int dropItemId, int estimatedDrops,
                              int staminaCost, int efficiencyBp, int recommendedTimes, boolean sweepUnlocked) {}

    public record PlanResult(boolean ok, int retcode, int avatarId, int currentLevel, int targetLevel,
                             List<MaterialNeed> materials, List<StageAdvice> stages,
                             int totalStaminaCost, int staminaCurrent, int staminaDeficit,
                             boolean canOneClickSweep) {
        static PlanResult fail(int retcode) {
            return new PlanResult(false, retcode, 0, 0, 0, List.of(), List.of(), 0, 0, 0, false);
        }
    }

    /**
     * 养成日程表：今日可用体力（当前+储备+日购剩余+自然回体至日末）与缺口对比。
     */
    public record ScheduleResult(boolean ok, int retcode, int avatarId, int targetLevel,
                                 int todayProgressBp, long naturalReadyAtMs,
                                 int staminaAvailableToday, int staminaNeeded,
                                 int reserveStamina, int dailyBuyRemaining, String summary) {
        static ScheduleResult fail(int retcode) {
            return new ScheduleResult(false, retcode, 0, 0, 0, 0L, 0, 0, 0, 0, "");
        }
    }

    /** 材料本：itemId → (stageId, name, dropPerRun, unitStamina) */
    private static final Map<Integer, StageAdvice> DROP_TABLE = Map.of(
            201, new StageAdvice(101, "拟造花萼·角色经验", 201, 5, 20, 2500, 0, true),
            202, new StageAdvice(102, "拟造花萼·信用点", 202, 4, 20, 2000, 0, true),
            203, new StageAdvice(103, "凝滞虚影·突破", 203, 3, 30, 1000, 0, true),
            301, new StageAdvice(301, "侵蚀隧洞·行迹", 301, 2, 40, 500, 0, true),
            401, new StageAdvice(401, "历战余响·周本", 401, 1, 30, 333, 0, false)
    );

    private final AvatarRepository avatarRepository;
    private final ItemApplicationService itemApplicationService;
    private final ObjectProvider<StaminaService> staminaProvider;

    public DevelopmentPlanService(AvatarRepository avatarRepository,
                                  ItemApplicationService itemApplicationService,
                                  ObjectProvider<StaminaService> staminaProvider) {
        this.avatarRepository = avatarRepository;
        this.itemApplicationService = itemApplicationService;
        this.staminaProvider = staminaProvider;
    }

    public PlanResult calculate(int playerId, int avatarId, int targetLevel) {
        if (playerId <= 0) {
            return PlanResult.fail(1);
        }
        if (avatarId <= 0 || targetLevel <= 0 || targetLevel > MAX_LEVEL) {
            return PlanResult.fail(3);
        }
        AvatarEntity avatar = avatarRepository.findAvatar(playerId, avatarId);
        if (avatar == null) {
            return PlanResult.fail(2);
        }
        int current = Math.max(1, avatar.getLevel());
        int target = Math.max(current, targetLevel);
        Map<Integer, Integer> required = requiredMaterials(current, target, avatar.getPromotion());
        Map<Integer, Integer> owned = ownedCounts(playerId);
        List<MaterialNeed> needs = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : required.entrySet()) {
            int have = owned.getOrDefault(e.getKey(), 0);
            int req = Math.max(0, e.getValue());
            needs.add(new MaterialNeed(e.getKey(), req, have, Math.max(0, req - have)));
        }
        List<StageAdvice> stages = recommendStages(needs);
        int unit = staminaUnit();
        int totalStamina = 0;
        for (StageAdvice s : stages) {
            totalStamina += s.staminaCost() * Math.max(0, s.recommendedTimes());
        }
        int staminaCurrent = currentStamina(playerId);
        int deficit = Math.max(0, totalStamina - staminaCurrent);
        boolean canSweep = deficit == 0 && !stages.isEmpty()
                && stages.stream().allMatch(StageAdvice::sweepUnlocked);
        return new PlanResult(true, 0, avatarId, current, target, List.copyOf(needs), List.copyOf(stages),
                totalStamina, staminaCurrent, deficit, canSweep);
    }

    /**
     * 结合体力上限、储备（默认 240）、日购次数与自然恢复，输出今日可完成进度与自然回体达标时间。
     */
    public ScheduleResult calculateOptimalSchedule(int playerId, int avatarId, int targetLevel) {
        PlanResult plan = calculate(playerId, avatarId, targetLevel);
        if (!plan.ok()) {
            return ScheduleResult.fail(plan.retcode());
        }
        StaminaService stamina = staminaProvider == null ? null : staminaProvider.getIfAvailable();
        StaminaService.StaminaSnapshot snap = stamina == null
                ? new StaminaService.StaminaSnapshot(0, 240, 0, 8, 0L, 0, 240)
                : stamina.snapshot(playerId);
        StaminaService.StaminaConfig cfg = stamina == null
                ? new StaminaService.StaminaConfig(240, 1, 60_000L, 40, 20, 8, 101, List.of(50), 60)
                : stamina.config();
        int reserve = Math.max(0, snap.reserve());
        int buyRemain = Math.max(0, snap.dailyBuyLimit() - snap.dailyBuyCount());
        int buyGrant = Math.max(0, cfg.buyGrantAmount()) * buyRemain;
        // 估算至今日 4:00（Asia/Shanghai 重置）前可自然回复的体力
        long now = System.currentTimeMillis();
        long dayEndMs = endOfGameDayMs(now);
        long remainMs = Math.max(0L, dayEndMs - now);
        int regenPerMin = Math.max(1, cfg.regenPerMinute());
        int naturalGain = (int) Math.min(snap.max() - snap.current(),
                (remainMs / Math.max(1_000L, cfg.regenIntervalMs())) * regenPerMin);
        int available = snap.current() + reserve + buyGrant + Math.max(0, naturalGain);
        int needed = Math.max(0, plan.totalStaminaCost());
        int progressBp = needed <= 0 ? 10_000
                : (int) Math.min(10_000L, Math.floor(available * 10_000.0 / needed));
        long readyAt = 0L;
        if (needed > snap.current() + reserve + buyGrant) {
            int deficit = needed - snap.current() - reserve - buyGrant;
            long intervals = (long) Math.ceil(deficit / (double) regenPerMin);
            readyAt = now + intervals * Math.max(1_000L, cfg.regenIntervalMs());
        }
        String summary = progressBp >= 10_000
                ? "今日体力（含储备/日购/回体）可完成养成目标"
                : "今日预计完成 " + (progressBp / 100) + "%；自然回体达标约 "
                + (readyAt > 0 ? formatEta(readyAt - now) : "即将");
        return new ScheduleResult(true, 0, avatarId, plan.targetLevel(), progressBp, readyAt,
                available, needed, reserve, buyRemain, summary);
    }

    private static long endOfGameDayMs(long nowMs) {
        java.time.ZoneId zone = java.time.ZoneId.of("Asia/Shanghai");
        java.time.ZonedDateTime zdt = java.time.Instant.ofEpochMilli(nowMs).atZone(zone);
        java.time.LocalDate day = zdt.toLocalTime().isBefore(java.time.LocalTime.of(4, 0))
                ? zdt.toLocalDate() : zdt.toLocalDate().plusDays(1);
        return day.atTime(4, 0).atZone(zone).toInstant().toEpochMilli();
    }

    private static String formatEta(long ms) {
        long mins = Math.max(1L, ms / 60_000L);
        if (mins < 60) {
            return mins + " 分钟";
        }
        return (mins / 60) + " 小时" + (mins % 60) + " 分";
    }

    /** 每升 10 级消耗一组材料；突破档另计行迹材料。 */
    static Map<Integer, Integer> requiredMaterials(int currentLevel, int targetLevel, int promotion) {
        Map<Integer, Integer> out = new LinkedHashMap<>();
        int fromBand = currentLevel / 10;
        int toBand = (Math.max(currentLevel, targetLevel) + 9) / 10;
        int bands = Math.max(0, toBand - fromBand);
        if (bands <= 0 && targetLevel > currentLevel) {
            bands = 1;
        }
        add(out, 201, bands * 8);
        add(out, 202, bands * 5);
        add(out, 203, bands * 3);
        int promoNeed = Math.max(0, ((targetLevel - 20) / 10) - promotion);
        add(out, 301, promoNeed * 4);
        add(out, 401, targetLevel >= 70 && currentLevel < 70 ? 1 : 0);
        out.entrySet().removeIf(e -> e.getValue() == null || e.getValue() <= 0);
        return out;
    }

    private List<StageAdvice> recommendStages(List<MaterialNeed> needs) {
        List<StageAdvice> out = new ArrayList<>();
        for (MaterialNeed need : needs) {
            if (need.deficit() <= 0) {
                continue;
            }
            StageAdvice base = DROP_TABLE.get(need.itemId());
            if (base == null) {
                continue;
            }
            int times = (int) Math.ceil(need.deficit() / (double) Math.max(1, base.estimatedDrops()));
            out.add(new StageAdvice(base.stageId(), base.stageName(), base.dropItemId(),
                    base.estimatedDrops(), base.staminaCost(), base.efficiencyBp(), times, base.sweepUnlocked()));
        }
        out.sort(Comparator.comparingInt(StageAdvice::efficiencyBp).reversed());
        return out;
    }

    private Map<Integer, Integer> ownedCounts(int playerId) {
        Map<Integer, Integer> owned = new LinkedHashMap<>();
        try {
            for (GameItemEntity item : itemApplicationService.listBagItems(playerId, 3, 1, 200)) {
                if (item == null || item.isDiscarded()) {
                    continue;
                }
                owned.merge(item.getItemId(), (int) Math.max(0, item.getCount()), Integer::sum);
            }
        } catch (Exception ignored) {
            // 仓储未就绪时按 0 拥有计算缺口
        }
        return owned;
    }

    private int staminaUnit() {
        StaminaService stamina = staminaProvider == null ? null : staminaProvider.getIfAvailable();
        if (stamina == null) {
            return 20;
        }
        return Math.max(1, stamina.config().materialFarmCost());
    }

    private int currentStamina(int playerId) {
        StaminaService stamina = staminaProvider == null ? null : staminaProvider.getIfAvailable();
        if (stamina == null) {
            return 0;
        }
        return Math.max(0, stamina.snapshot(playerId).current());
    }

    private static void add(Map<Integer, Integer> map, int itemId, int count) {
        if (count > 0) {
            map.merge(itemId, count, Integer::sum);
        }
    }
}
