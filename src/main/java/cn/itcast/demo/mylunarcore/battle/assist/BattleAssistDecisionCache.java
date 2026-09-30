package cn.itcast.demo.mylunarcore.battle.assist;

import cn.itcast.demo.mylunarcore.battle.BattleContext;
import cn.itcast.demo.mylunarcore.battle.EntityState;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.LongAdder;

/**
 * Auto AI 决策缓存：将战术选项与战况哈希后复用最近 N 条结果。
 * 同一遭遇反复刷本时命中率可期。
 */
@Component
public class BattleAssistDecisionCache {

    private final Cache<Long, BattleAssistPolicy.Suggestion> cache;
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();

    public BattleAssistDecisionCache() {
        this.cache = Caffeine.newBuilder()
                .maximumSize(2048)
                .expireAfterWrite(Duration.ofSeconds(8))
                .build();
    }

    public Optional<BattleAssistPolicy.Suggestion> get(BattleContext context, int actorEntityId) {
        if (context == null) {
            return Optional.empty();
        }
        long key = hash(context, actorEntityId);
        BattleAssistPolicy.Suggestion hit = cache.getIfPresent(key);
        if (hit == null) {
            misses.increment();
            return Optional.empty();
        }
        hits.increment();
        return Optional.of(hit);
    }

    public void put(BattleContext context, int actorEntityId, BattleAssistPolicy.Suggestion suggestion) {
        if (context == null || suggestion == null || suggestion.skillId() <= 0) {
            return;
        }
        cache.put(hash(context, actorEntityId), suggestion);
    }

    public void invalidateBattle(long battleId) {
        // 键含 battleId 高位；失效时清全部（量级小，可接受）
        cache.invalidateAll();
    }

    static long hash(BattleContext context, int actorEntityId) {
        long h = context.getBattleId() * 31L
                + context.getTurn() * 17L
                + context.getCurrentWave() * 13L
                + actorEntityId * 11L
                + context.getAutoStrategy() * 7L
                + context.getTargetFocus() * 5L
                + context.getTeamSkillPoints()
                + context.getUltEnergyPercent();
        List<Integer> monsters = context.listAliveMonsterIdsInCurrentWave();
        for (Integer id : monsters) {
            EntityState e = context.getEntity(id);
            if (e == null) {
                continue;
            }
            // 血量桶化：每 10% 一档，提高命中率
            int bucket = e.getHp() <= 0 ? 0 : Math.min(10, (e.getHp() * 10) / Math.max(1, e.getHp() + 1));
            // 用当前 hp 与 broken 粗粒度签名
            int hpSig = Math.min(10, e.getHp() / Math.max(1, estimateMaxHp(e)));
            h = h * 31 + id * 19L + hpSig * 3L + (e.isBroken() ? 1 : 0) + bucket;
        }
        EntityState actor = context.getEntity(actorEntityId);
        if (actor != null) {
            h = h * 31 + actor.getBuffStacks().hashCode();
        }
        return h;
    }

    private static int estimateMaxHp(EntityState e) {
        // EntityState 无 maxHp；用当前 hp 上界近似桶宽
        return Math.max(e.getHp(), 1);
    }

    public long hitCount() {
        return hits.sum();
    }

    public long missCount() {
        return misses.sum();
    }

    public long size() {
        return cache.estimatedSize();
    }
}
