package cn.itcast.demo.mylunarcore.worldboss;

import cn.itcast.demo.mylunarcore.common.AppLogger;
import cn.itcast.demo.mylunarcore.common.ClusterJobLock;
import cn.itcast.demo.mylunarcore.common.LogCategory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 世界 BOSS 服务：全服共享血量、按剩余血量比例切换阶段、个人伤害排行与周期奖励。
 * <p>
 * 多节点下扣血优先走 {@code world_boss_state} 的 SQL 原子更新；DB 不可用时回退
 * {@link #memHp}（仅适合单机联调）。每日挑战次数用内存键 {@code playerId:日期} 计数；
 * 周重置经 {@link ClusterJobLock} 单点执行。
 */
@Service
public class WorldBossService {

    // 活动业务日志分类
    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_ACTIVITY, WorldBossService.class);
    // 日限按上海时区切日
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /**
     * 客户端展示用 BOSS 快照。
     *
     * @param bossId      配置 BOSS ID
     * @param name        名称
     * @param maxHp       满血
     * @param currentHp   当前血
     * @param phaseIndex  阶段下标（与 phases 配置对应）
     * @param phaseName   当前阶段名
     * @param seasonEnd   本赛季结束时刻
     * @param dailyLimit  每日可挑战次数上限
     */
    public record BossInfo(int bossId, String name, long maxHp, long currentHp, int phaseIndex,
                           String phaseName, Instant seasonEnd, int dailyLimit) {}

    /** 排行榜一行：玩家、累计伤害、名次（从 1 起）。 */
    public record RankEntry(int playerId, long damage, int rank) {}

    /**
     * 一次挑战结果。
     *
     * @param success        是否扣血成功
     * @param retcode        1=参数非法，2=日限，0=成功
     * @param damageApplied  实际计入的伤害
     * @param remainHp       扣后剩余全服血量
     * @param phaseIndex     扣后阶段
     * @param rewardHint     参与奖/阶段 buff 提示文案
     */
    public record ChallengeResult(boolean success, int retcode, long damageApplied, long remainHp,
                                  int phaseIndex, String rewardHint) {}

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    // 周重置等多节点互斥
    private final ClusterJobLock clusterJobLock;

    // 以下字段由 WorldBossConfigs.json 覆盖，下列为默认值
    private int bossId = 90001;
    private String bossName = "WorldBoss";
    private long maxHp = 100_000_000L;
    private int dailyLimit = 3;
    private int seasonDays = 7;
    // hpRatio 降序的阶段列表（血量越低阶段下标通常越大）
    private final List<Phase> phases = new ArrayList<>();
    // rankMax 门槛对应的排名奖励描述
    private final List<RankReward> rankRewards = new ArrayList<>();
    // 参与奖默认文案
    private String participationReward = "";

    // DB 不可用时的全服血量
    private final AtomicLong memHp = new AtomicLong(maxHp);
    // 内存累计伤害：playerId → damage
    private final ConcurrentHashMap<Integer, Long> memDamage = new ConcurrentHashMap<>();
    // 日挑战计数：\"playerId:yyyy-MM-dd\" → 已用次数
    private final ConcurrentHashMap<String, Integer> memDaily = new ConcurrentHashMap<>();
    // 当前赛季结束时间
    private volatile Instant seasonEnd = Instant.now().plus(Duration.ofDays(7));

    /** 阶段：当剩余血量比例 <= hpRatio 时可进入该阶段。 */
    private record Phase(double hpRatio, String name, String buffDesc) {}

    /** 排名奖励：名次 <= rankMax 时使用 rewardDesc。 */
    private record RankReward(int rankMax, String rewardDesc) {}

    public WorldBossService(JdbcTemplate jdbc, ObjectMapper objectMapper, ClusterJobLock clusterJobLock) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.clusterJobLock = clusterJobLock;
    }

    /** 启动：读配置并确保 DB/内存赛季状态存在。 */
    @PostConstruct
    public void init() {
        loadConfig();
        ensureSeason();
    }

    /** 组装当前 BOSS 信息（含阶段名与赛季结束时间）。 */
    public BossInfo info() {
        long hp = currentHp();
        int phase = phaseIndex(hp);
        // 无阶段配置时给占位「觉醒」
        Phase p = phases.isEmpty() ? new Phase(1, "觉醒", "") : phases.get(Math.min(phase, phases.size() - 1));
        return new BossInfo(bossId, bossName, maxHp, hp, phase, p.name(), seasonEnd, dailyLimit);
    }

    /** 今日已用挑战次数（不占额度）。 */
    public int usedChallengesToday(int playerId) {
        if (playerId <= 0) {
            return 0;
        }
        String dayKey = playerId + ":" + LocalDate.now(ZONE);
        return memDaily.getOrDefault(dayKey, 0);
    }

    /**
     * 玩家挑战：校验日限 → 裁剪单次伤害（最多 maxHp 的 1%，防谎报）→
     * 原子扣血 → 累计个人伤害并落库 → 返回剩余血与阶段提示。
     */
    public ChallengeResult challenge(int playerId, long declaredDamage) {
        if (playerId <= 0 || declaredDamage <= 0) {
            return new ChallengeResult(false, 1, 0, currentHp(), phaseIndex(currentHp()), "");
        }
        // 上海时区自然日键
        String dayKey = playerId + ":" + LocalDate.now(ZONE);
        int used = memDaily.merge(dayKey, 1, Integer::sum); // 先占一次额度
        if (used > dailyLimit) {
            memDaily.merge(dayKey, -1, Integer::sum); // 超限回滚计数
            return new ChallengeResult(false, 2, 0, currentHp(), phaseIndex(currentHp()), "daily_limit");
        }
        // 单次伤害上限为满血 1%；正式环境应改为战斗权威结算伤害
        long dmg = Math.min(declaredDamage, maxHp / 100);
        long remain = applyDamage(dmg);
        memDamage.merge(playerId, dmg, Long::sum); // 内存排行回退数据
        persistDamage(playerId, dmg); // upsert world_boss_damage
        int phase = phaseIndex(remain);
        String hint = participationReward;
        if (phase > 0 && phase < phases.size()) {
            // 叠加当前阶段 buff 描述与参与奖
            hint = phases.get(phase).buffDesc() + " | " + participationReward;
        }
        return new ChallengeResult(true, 0, dmg, remain, phase, hint);
    }

    /**
     * TopN 排行：优先读 DB 按 damage 降序；DB 空/失败则用 memDamage 排序。
     * limit 夹在 [1,100]。
     */
    public List<RankEntry> topRanks(int limit) {
        int n = Math.max(1, Math.min(100, limit));
        List<RankEntry> fromDb = loadRanksFromDb(n);
        if (!fromDb.isEmpty()) {
            return fromDb;
        }
        List<Map.Entry<Integer, Long>> sorted = new ArrayList<>(memDamage.entrySet());
        sorted.sort(Comparator.<Map.Entry<Integer, Long>>comparingLong(Map.Entry::getValue).reversed());
        List<RankEntry> out = new ArrayList<>();
        for (int i = 0; i < Math.min(n, sorted.size()); i++) {
            Map.Entry<Integer, Long> e = sorted.get(i);
            out.add(new RankEntry(e.getKey(), e.getValue(), i + 1));
        }
        return out;
    }

    /** 按名次匹配第一条 rankMax >= rank 的奖励；否则返回参与奖。 */
    public String rewardForRank(int rank) {
        for (RankReward r : rankRewards) {
            if (rank <= r.rankMax()) {
                return r.rewardDesc();
            }
        }
        return participationReward;
    }

    /**
     * 每周一 04:05：抢锁后重置血量、清空伤害与日限、延长赛季，并同步 DB。
     */
    @Scheduled(cron = "0 5 4 * * MON")
    public void weeklyReset() {
        clusterJobLock.tryRun("world-boss-reset", Duration.ofMinutes(10), () -> {
            memHp.set(maxHp);
            memDamage.clear();
            memDaily.clear();
            seasonEnd = Instant.now().plus(Duration.ofDays(seasonDays));
            try {
                jdbc.update("UPDATE world_boss_state SET current_hp=?, season_end=?, updated_at=? WHERE boss_id=?",
                        maxHp, seasonEnd.toString(), Instant.now().toString(), bossId);
                jdbc.update("DELETE FROM world_boss_damage WHERE boss_id=?", bossId);
            } catch (Exception e) {
                log.debug("world boss reset db skip: {}", e.getMessage());
            }
            log.info("world_boss season reset bossId={} maxHp={}", bossId, maxHp);
        });
    }

    /**
     * 确保 {@code world_boss_state} 有当前 boss 行；无则插入满血新赛季；
     * 有则把 current_hp/season_end 灌入内存。DB 失败则纯内存开赛季。
     */
    private void ensureSeason() {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT current_hp, season_end FROM world_boss_state WHERE boss_id=?", bossId);
            if (rows.isEmpty()) {
                seasonEnd = Instant.now().plus(Duration.ofDays(seasonDays));
                jdbc.update("""
                        INSERT INTO world_boss_state (boss_id, current_hp, max_hp, season_end, updated_at)
                        VALUES (?, ?, ?, ?, ?)
                        """, bossId, maxHp, maxHp, seasonEnd.toString(), Instant.now().toString());
                memHp.set(maxHp);
            } else {
                Map<String, Object> row = rows.get(0);
                memHp.set(((Number) row.get("current_hp")).longValue());
                Object se = row.get("season_end");
                if (se != null) {
                    seasonEnd = Instant.parse(se.toString());
                }
            }
        } catch (Exception e) {
            memHp.set(maxHp);
            seasonEnd = Instant.now().plus(Duration.ofDays(seasonDays));
            log.debug("world_boss ensureSeason memory fallback: {}", e.getMessage());
        }
    }

    /**
     * 扣血：先 SQL {@code GREATEST(0, current_hp - dmg)}，再读回并同步 memHp；
     * 失败则 {@link AtomicLong#updateAndGet} 本地扣减。
     */
    private long applyDamage(long dmg) {
        try {
            jdbc.update("""
                    UPDATE world_boss_state SET current_hp = GREATEST(0, current_hp - ?), updated_at=?
                    WHERE boss_id=?
                    """, dmg, Instant.now().toString(), bossId);
            Long hp = jdbc.queryForObject("SELECT current_hp FROM world_boss_state WHERE boss_id=?",
                    Long.class, bossId);
            if (hp != null) {
                memHp.set(hp);
                return hp;
            }
        } catch (Exception ignored) {
        }
        return memHp.updateAndGet(v -> Math.max(0, v - dmg));
    }

    /** 读当前血：DB 优先，失败用 memHp。 */
    private long currentHp() {
        try {
            Long hp = jdbc.queryForObject("SELECT current_hp FROM world_boss_state WHERE boss_id=?",
                    Long.class, bossId);
            if (hp != null) {
                memHp.set(hp);
                return hp;
            }
        } catch (Exception ignored) {
        }
        return memHp.get();
    }

    /** 个人伤害 upsert：先 UPDATE 累加，0 行再 INSERT。 */
    private void persistDamage(int playerId, long dmg) {
        try {
            int u = jdbc.update("""
                    UPDATE world_boss_damage SET damage = damage + ?, updated_at=?
                    WHERE boss_id=? AND player_id=?
                    """, dmg, Instant.now().toString(), bossId, playerId);
            if (u == 0) {
                jdbc.update("""
                        INSERT INTO world_boss_damage (boss_id, player_id, damage, updated_at)
                        VALUES (?, ?, ?, ?)
                        """, bossId, playerId, dmg, Instant.now().toString());
            }
        } catch (Exception ignored) {
        }
    }

    /** 从 DB 拉 TopN；异常返回空列表以触发内存排行。 */
    private List<RankEntry> loadRanksFromDb(int limit) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT player_id, damage FROM world_boss_damage
                    WHERE boss_id=? ORDER BY damage DESC LIMIT ?
                    """, bossId, limit);
            List<RankEntry> out = new ArrayList<>();
            int rank = 1;
            for (Map<String, Object> row : rows) {
                out.add(new RankEntry(
                        ((Number) row.get("player_id")).intValue(),
                        ((Number) row.get("damage")).longValue(),
                        rank++));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 按剩余血量比例计算阶段下标。phases 按 hpRatio 降序配置；
     * 从后往前找第一个 {@code ratio <= hpRatio} 的阶段。
     */
    private int phaseIndex(long hp) {
        if (phases.isEmpty() || maxHp <= 0) {
            return 0;
        }
        double ratio = (double) hp / maxHp;
        int idx = 0;
        for (int i = 0; i < phases.size(); i++) {
            if (ratio <= phases.get(i).hpRatio()) {
                idx = i;
            }
        }
        // 再按降序阈值精确定位最高适用阶段
        for (int i = phases.size() - 1; i >= 0; i--) {
            if (ratio <= phases.get(i).hpRatio()) {
                return i;
            }
        }
        return idx;
    }

    /**
     * 读取 {@code data/WorldBossConfigs.json}：bossId/name/maxHp/日限/赛季天数/
     * phases/rankRewards/participationReward；阶段按 hpRatio 降序排序。
     */
    private void loadConfig() {
        try {
            Path p = Path.of("data/WorldBossConfigs.json");
            if (!Files.exists(p)) {
                return; // 保持代码默认值
            }
            JsonNode root = objectMapper.readTree(Files.readString(p));
            bossId = root.path("bossId").asInt(90001);
            bossName = root.path("name").asText("WorldBoss");
            maxHp = root.path("maxHp").asLong(100_000_000L);
            dailyLimit = root.path("dailyChallengeLimit").asInt(3);
            seasonDays = root.path("seasonDays").asInt(7);
            participationReward = root.path("participationReward").asText("");
            phases.clear();
            for (JsonNode n : root.path("phases")) {
                phases.add(new Phase(n.path("hpRatio").asDouble(), n.path("name").asText(),
                        n.path("buffDesc").asText("")));
            }
            phases.sort(Comparator.comparingDouble(Phase::hpRatio).reversed());
            rankRewards.clear();
            for (JsonNode n : root.path("rankRewards")) {
                rankRewards.add(new RankReward(n.path("rankMax").asInt(), n.path("rewardDesc").asText()));
            }
            // 若内存血仍为 0，尝试对齐到满血；超出 maxHp 也夹回满血
            memHp.compareAndSet(0, maxHp);
            if (memHp.get() <= 0 || memHp.get() > maxHp) {
                memHp.set(maxHp);
            }
        } catch (Exception e) {
            log.warn("WorldBossConfigs load failed: {}", e.toString());
        }
    }
}
