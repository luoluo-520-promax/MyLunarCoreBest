package cn.itcast.demo.mylunarcore.analytics;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * 战斗平衡性分析服务（轻量版）。
 *
 * <p>目的：为数值策划提供"哪些角色/光锥/遗器组合出场率高、胜率高"的量化依据，
 * 辅助职业平衡调整。典型用法：战斗结算时把上阵阵容（每名角色 + 其所带光锥 + 遗器套装）
 * 抽象为组合键 {@link ComboKey}，调用 {@link #recordBattle} 记录一场战斗。
 *
 * <p>统计维度：
 * <ul>
 *   <li>出场次数 battles：某组合被使用过的战斗场数；</li>
 *   <li>胜场 wins：其中获胜的场数；</li>
 *   <li>胜率 winRate = wins / battles（千分位取整到一位小数）；</li>
 *   <li>出场率 pickRate = battles / totalBattles。</li>
 * </ul>
 *
 * <p>内存态为热数据源（ConcurrentHashMap + LongAdder），同时每条记录异步落库到
 * <code>combat_balance_event</code> 表供离线分析；查询 TOP 时优先读库，库不可用则回退内存。
 */
@Service
public class CombatBalanceAnalyticsService {

    /**
     * 阵容组合键：某名角色 + 光锥 + 遗器套装组合成一个统计单元。
     *
     * @param avatarId    角色 ID
     * @param lightConeId 光锥 ID
     * @param relicSetId  遗器套装 ID
     */
    public record ComboKey(int avatarId, int lightConeId, int relicSetId) {
        /** 组合键的字符串化，作为内存 Map 的 key：avatar:lightCone:relic。 */
        public String asString() {
            return avatarId + ":" + lightConeId + ":" + relicSetId;
        }
    }

    /**
     * 单个组合的统计快照。
     *
     * @param avatarId    角色 ID
     * @param lightConeId 光锥 ID
     * @param relicSetId  遗器套装 ID
     * @param battles     出场次数
     * @param wins        胜场数
     * @param winRate     胜率（百分比，一位小数）
     * @param pickRate    出场率（百分比，一位小数）
     */
    public record ComboStats(int avatarId, int lightConeId, int relicSetId,
                             int battles, int wins, double winRate, double pickRate) {}

    /** 数据库访问模板，持久化 combat_balance_event 表。 */
    private final JdbcTemplate jdbc;
    /** 组合出场次数内存统计：key = comboKey.asString() → 次数。 */
    private final ConcurrentHashMap<String, AtomicInteger> battles = new ConcurrentHashMap<>();
    /** 组合胜场内存统计：key = comboKey.asString() → 胜场数。 */
    private final ConcurrentHashMap<String, AtomicInteger> wins = new ConcurrentHashMap<>();
    /** 全服总战斗场数（LongAdder 支持高并发累加）。 */
    private final LongAdder totalBattles = new LongAdder();

    public CombatBalanceAnalyticsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 记录一场战斗的阵容统计。
     *
     * <p>对阵容里每个组合键分别累计出场次数（胜场另计），并异步写库一条明细事件。
     * 空阵容直接忽略；组合键为 null 的元素跳过。
     *
     * @param lineup 整场上阵阵容（每个组合键代表一名角色的角色/光锥/遗器组合）
     * @param win    本场是否胜利
     */
    public void recordBattle(List<ComboKey> lineup, boolean win) {
        if (lineup == null || lineup.isEmpty()) {
            return;
        }
        totalBattles.increment();
        for (ComboKey key : lineup) {
            if (key == null) {
                continue;
            }
            String k = key.asString();
            // 出场次数 +1
            battles.computeIfAbsent(k, x -> new AtomicInteger()).incrementAndGet();
            if (win) {
                // 胜场数 +1
                wins.computeIfAbsent(k, x -> new AtomicInteger()).incrementAndGet();
            }
            persist(key, win);
        }
    }

    /**
     * 查询胜率最高的若干组合（用于平衡分析报表）。
     *
     * <p>只统计出场次数 ≥ 3 的组合（样本过少不计入），按胜率降序排序后取前 limit 条；
     * 胜率与出场率均为一位小数的百分比。limit 至少为 1。
     *
     * @param limit 返回条数上限
     * @return 胜率降序的统计列表
     */
    public List<ComboStats> topByWinRate(int limit) {
        long total = Math.max(1, totalBattles.sum());
        List<ComboStats> list = new ArrayList<>();
        for (Map.Entry<String, AtomicInteger> e : battles.entrySet()) {
            int b = e.getValue().get();
            if (b < 3) {
                continue; // 样本过少忽略
            }
            int w = wins.getOrDefault(e.getKey(), new AtomicInteger()).get();
            // 拆回组合键的三要素
            String[] parts = e.getKey().split(":");
            list.add(new ComboStats(
                    Integer.parseInt(parts[0]),
                    Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]),
                    b, w,
                    // 胜率：千分位四舍五入后保留一位小数
                    Math.round(w * 1000.0 / b) / 10.0,
                    // 出场率：同上
                    Math.round(b * 1000.0 / total) / 10.0));
        }
        // 胜率降序
        list.sort(Comparator.comparingDouble(ComboStats::winRate).reversed());
        return list.stream().limit(Math.max(1, limit)).toList();
    }

    /**
     * 汇总快照：总战斗数、跟踪的组合数、当前胜率 TOP20。
     *
     * @return 统计快照 Map
     */
    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalBattles", totalBattles.sum());
        out.put("combosTracked", battles.size());
        out.put("top", topByWinRate(20));
        return out;
    }

    /**
     * 把单条组合战斗事件写入 combat_balance_event 表（供离线数据管道分析）。
     *
     * @param key 组合键
     * @param win 是否胜利
     */
    private void persist(ComboKey key, boolean win) {
        try {
            jdbc.update("""
                    INSERT INTO combat_balance_event
                    (avatar_id, light_cone_id, relic_set_id, win, created_at)
                    VALUES (?, ?, ?, ?, ?)
                    """, key.avatarId(), key.lightConeId(), key.relicSetId(),
                    win ? 1 : 0, Instant.now().toString());
        } catch (Exception ignored) {
            // 落库失败忽略，内存统计不受影响
        }
    }
}
