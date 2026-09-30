package cn.itcast.demo.mylunarcore.challenge;

import cn.itcast.demo.mylunarcore.battle.EncounterConfig;
import cn.itcast.demo.mylunarcore.battle.EncounterConfigRepository;
import cn.itcast.demo.mylunarcore.economy.RewardDistributor;
import cn.itcast.demo.mylunarcore.player.StaminaService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 扫荡服务：基于历史最低回合通关记录，跳过完整 BattleManager，直接扣体力并发放倍率奖励。
 */
@Service
public class SweepService {

    private static final Logger log = LoggerFactory.getLogger(SweepService.class);
    private static final int MAX_MULTIPLIER = 3;
    private static final int DROP_COUNT_CAP_PER_ITEM = 999;

    public record BestClear(int stageId, int bestTurnCount) {}

    public record SweepReward(int itemId, int count) {}

    public record SweepResult(boolean ok, int retcode, int stageId, int multiplier, int staminaCost,
                              int staminaRemaining, int bestTurnCount, List<SweepReward> rewards,
                              int playerExp) {
        static SweepResult fail(int retcode) {
            return new SweepResult(false, retcode, 0, 0, 0, 0, 0, List.of(), 0);
        }
    }

    public record SweepInfo(boolean unlocked, int bestTurnCount, int maxMultiplier, int unitStaminaCost) {}

    private final JdbcTemplate jdbc;
    private final StaminaService staminaService;
    private final EncounterConfigRepository encounterConfigRepository;
    private final RewardDistributor rewardDistributor;
    private final Map<String, Integer> memoryBest = new ConcurrentHashMap<>();

    public SweepService(JdbcTemplate jdbc,
                        StaminaService staminaService,
                        EncounterConfigRepository encounterConfigRepository,
                        RewardDistributor rewardDistributor) {
        this.jdbc = jdbc;
        this.staminaService = staminaService;
        this.encounterConfigRepository = encounterConfigRepository;
        this.rewardDistributor = rewardDistributor;
    }

    /** 战斗胜利后记录最低回合数，作为扫荡解锁条件。 */
    public void recordClear(int playerId, int stageId, int turnCount) {
        if (playerId <= 0 || stageId <= 0 || turnCount <= 0) {
            return;
        }
        String key = playerId + ":" + stageId;
        memoryBest.merge(key, turnCount, Math::min);
        try {
            jdbc.update("""
                    INSERT INTO player_stage_clear_best (player_id, stage_id, best_turn_count, updated_at)
                    VALUES (?, ?, ?, NOW())
                    ON DUPLICATE KEY UPDATE
                      best_turn_count = LEAST(best_turn_count, VALUES(best_turn_count)),
                      updated_at = NOW()
                    """, playerId, stageId, turnCount);
        } catch (Exception e) {
            log.debug("recordClear persist skipped: {}", e.getMessage());
        }
    }

    public SweepInfo info(int playerId, int stageId) {
        int best = bestTurn(playerId, stageId);
        int unit = Math.max(1, staminaService.config().materialFarmCost());
        return new SweepInfo(best > 0, Math.max(0, best), MAX_MULTIPLIER, unit);
    }

    @Transactional
    public SweepResult sweep(int playerId, int stageId, int multiplier) {
        if (playerId <= 0 || stageId <= 0) {
            return SweepResult.fail(6);
        }
        int mul = multiplier <= 0 ? 1 : multiplier;
        if (mul > MAX_MULTIPLIER) {
            return SweepResult.fail(4);
        }
        int best = bestTurn(playerId, stageId);
        if (best <= 0) {
            return SweepResult.fail(3);
        }
        int unit = Math.max(1, staminaService.config().materialFarmCost());
        int cost = unit * mul;
        StaminaService.ConsumeResult consumed = staminaService.tryConsume(playerId, cost);
        if (!consumed.ok()) {
            return SweepResult.fail(consumed.retcode() == 2 ? 2 : 6);
        }

        EncounterConfig encounter = encounterConfigRepository.current();
        List<EncounterConfig.DropEntry> baseDrops = resolveDropsForStage(encounter, stageId);
        List<EncounterConfig.DropEntry> scaled = scaleDrops(baseDrops, mul);
        int baseExp = Math.max(0, encounter.defaultPlayerExp());
        int playerExp = Math.min(baseExp * mul, baseExp * MAX_MULTIPLIER * 2);

        List<RewardDistributor.GrantedItem> granted =
                rewardDistributor.grantBattleRewards(playerId, scaled, playerExp, "sweep:" + stageId + "x" + mul);
        List<SweepReward> rewards = new ArrayList<>();
        for (RewardDistributor.GrantedItem g : granted) {
            rewards.add(new SweepReward(g.itemId(), g.count()));
        }
        return new SweepResult(true, 0, stageId, mul, cost, consumed.remaining(), best,
                List.copyOf(rewards), playerExp);
    }

    public record BatchSweepStep(int stageId, int times) {}

    public record BatchSweepResult(boolean ok, int retcode, int staminaCost, int staminaRemaining,
                                   int completedTimes, List<SweepReward> rewards, int playerExp) {
        static BatchSweepResult fail(int retcode) {
            return new BatchSweepResult(false, retcode, 0, 0, 0, List.of(), 0);
        }
    }

    /**
     * 一键连续扫荡：按关卡顺序多次执行，统一汇总奖励；体力不足时中止已完成部分仍结算。
     */
    @Transactional
    public BatchSweepResult batchSweep(int playerId, List<BatchSweepStep> steps, int confirmedCost) {
        if (playerId <= 0) {
            return BatchSweepResult.fail(6);
        }
        if (steps == null || steps.isEmpty()) {
            return BatchSweepResult.fail(4);
        }
        int expected = 0;
        int unit = Math.max(1, staminaService.config().materialFarmCost());
        for (BatchSweepStep step : steps) {
            if (step == null || step.stageId() <= 0 || step.times() <= 0) {
                return BatchSweepResult.fail(4);
            }
            if (bestTurn(playerId, step.stageId()) <= 0) {
                return BatchSweepResult.fail(3);
            }
            expected += unit * step.times();
        }
        if (confirmedCost > 0 && confirmedCost != expected) {
            return BatchSweepResult.fail(2);
        }
        StaminaService.StaminaSnapshot snap = staminaService.snapshot(playerId);
        if (snap.current() < expected) {
            return BatchSweepResult.fail(2);
        }
        Map<Integer, Integer> merged = new java.util.LinkedHashMap<>();
        int costSum = 0;
        int remaining = snap.current();
        int completed = 0;
        int expSum = 0;
        for (BatchSweepStep step : steps) {
            for (int i = 0; i < step.times(); i++) {
                SweepResult one = sweep(playerId, step.stageId(), 1);
                if (!one.ok()) {
                    return new BatchSweepResult(completed > 0, completed > 0 ? 5 : one.retcode(),
                            costSum, remaining, completed, toRewardList(merged), expSum);
                }
                costSum += one.staminaCost();
                remaining = one.staminaRemaining();
                completed++;
                expSum += one.playerExp();
                for (SweepReward r : one.rewards()) {
                    merged.merge(r.itemId(), r.count(), Integer::sum);
                }
            }
        }
        return new BatchSweepResult(true, 0, costSum, remaining, completed, toRewardList(merged), expSum);
    }

    private static List<SweepReward> toRewardList(Map<Integer, Integer> merged) {
        List<SweepReward> out = new ArrayList<>();
        merged.forEach((id, count) -> out.add(new SweepReward(id, count)));
        return out;
    }

    private int bestTurn(int playerId, int stageId) {
        String key = playerId + ":" + stageId;
        Integer mem = memoryBest.get(key);
        if (mem != null && mem > 0) {
            return mem;
        }
        try {
            List<Integer> rows = jdbc.query(
                    "SELECT best_turn_count FROM player_stage_clear_best WHERE player_id=? AND stage_id=?",
                    (rs, i) -> rs.getInt(1), playerId, stageId);
            if (rows != null && !rows.isEmpty() && rows.get(0) > 0) {
                memoryBest.put(key, rows.get(0));
                return rows.get(0);
            }
        } catch (Exception ignored) {
            // 表未就绪时仅用内存
        }
        return mem == null ? 0 : mem;
    }

    private static List<EncounterConfig.DropEntry> resolveDropsForStage(EncounterConfig encounter, int stageId) {
        if (encounter == null) {
            return List.of();
        }
        for (EncounterConfig.EncounterEntry e : encounter.encounters()) {
            if (e != null && e.battleStageId() == stageId && e.drops() != null && !e.drops().isEmpty()) {
                return e.drops();
            }
        }
        return encounter.defaultDrops();
    }

    private static List<EncounterConfig.DropEntry> scaleDrops(List<EncounterConfig.DropEntry> base, int mul) {
        if (base == null || base.isEmpty()) {
            return List.of();
        }
        List<EncounterConfig.DropEntry> out = new ArrayList<>(base.size());
        for (EncounterConfig.DropEntry d : base) {
            if (d == null) {
                continue;
            }
            Integer count = d.count() == null ? null : Math.min(DROP_COUNT_CAP_PER_ITEM, d.count() * mul);
            Integer amount = d.amount() == null ? null : Math.min(DROP_COUNT_CAP_PER_ITEM * 10, d.amount() * mul);
            out.add(new EncounterConfig.DropEntry(d.itemId(), count, d.currencyId(), amount));
        }
        return out;
    }
}
