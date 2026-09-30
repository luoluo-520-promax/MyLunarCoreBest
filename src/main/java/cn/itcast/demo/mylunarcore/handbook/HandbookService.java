package cn.itcast.demo.mylunarcore.handbook;

import cn.itcast.demo.mylunarcore.achievement.AchievementService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 图鉴收集服务。
 *
 * <p>职责：
 * <ul>
 *   <li>记录玩家对角色（AVATAR）、光锥（LIGHT_CONE）、敌人（ENEMY）、遗器套装（RELIC_SET）的收集进度；</li>
 *   <li>每次解锁图鉴项时写入 <code>player_handbook</code> 表，并在成功后联动成就系统；</li>
 *   <li>提供 summary()/overview() 供客户端展示整体收集率、已收集清单与最近解锁项；</li>
 *   <li>支持内存缓存 mem（key = playerId:type:id）防止重复解锁与重复写库。</li>
 * </ul>
 */
@Service
public class HandbookService {

    /** 图鉴类型枚举。 */
    public enum EntryType { AVATAR, LIGHT_CONE, ENEMY, RELIC_SET }

    /** 单条图鉴收集记录。 */
    public record Entry(EntryType type, int id, String name, boolean unlocked, Instant unlockedAt) {}

    /** 某类型图鉴的汇总。 */
    public record Summary(int unlocked, int total, double percent, List<Entry> recent) {}

    /** 数据库访问模板。 */
    private final JdbcTemplate jdbc;
    /** 可选的成就系统：解锁图鉴后顺手推进成就进度。 */
    private final AchievementService achievementService;
    /** 内存解锁缓存：playerId:type:entryId → unlock 时间。 */
    private final ConcurrentHashMap<String, Instant> mem = new ConcurrentHashMap<>();

    /**
     * 预置图鉴目录（当前为代码内静态表，后续可迁移到 JSON）。
     * 键为图鉴 ID，值为展示名称。
     */
    private static final Map<Integer, String> AVATARS = Map.of(
            1001, "开拓者", 1002, "三月七", 1003, "丹恒", 1004, "姬子");
    private static final Map<Integer, String> LIGHT_CONES = Map.of(
            20001, "记忆中的模样", 20002, "一场术后对话", 20003, "余生");
    private static final Map<Integer, String> ENEMIES = Map.of(
            30001, "虚卒·歼灭科", 30002, "外宇宙之火", 90001, "裂界灾兽·永夜");

    public HandbookService(JdbcTemplate jdbc, ObjectProvider<AchievementService> achievementProvider) {
        this.jdbc = jdbc;
        this.achievementService = achievementProvider.getIfAvailable();
    }

    /**
     * 解锁某条图鉴项。
     *
     * <p>先检查参数合法性，再利用 mem.putIfAbsent 防止重复解锁；首次解锁成功后写库，
     * 并推动两项成就进度：按类型的 handbook_xxx 与总计数 handbook_any。
     *
     * @param playerId 玩家 ID
     * @param type     图鉴类型
     * @param entryId  图鉴项 ID
     * @return 是否真正解锁成功（重复解锁返回 false）
     */
    public boolean unlock(int playerId, EntryType type, int entryId) {
        if (playerId <= 0 || entryId <= 0 || type == null) {
            return false;
        }
        String key = playerId + ":" + type.name() + ":" + entryId;
        Instant prev = mem.putIfAbsent(key, Instant.now());
        if (prev != null) {
            return false;
        }
        try {
            jdbc.update("""
                    INSERT INTO player_handbook (player_id, entry_type, entry_id, unlocked_at)
                    VALUES (?, ?, ?, ?)
                    """, playerId, type.name(), entryId, Instant.now().toString());
        } catch (Exception ignored) {
        }
        if (achievementService != null) {
            // 按类型推进一个专项成就，再推进一个总图鉴成就
            achievementService.addProgress(playerId, "handbook_" + type.name().toLowerCase(), 1);
            achievementService.addProgress(playerId, "handbook_any", 1);
        }
        return true;
    }

    /**
     * 查询某类型图鉴的收集汇总。
     *
     * <p>summary() 会把数据库与内存缓存综合起来，得到当前已解锁条目集合，并返回：
     * 已解锁数量、总数量、百分比以及最近解锁项列表。
     *
     * @param playerId 玩家 ID
     * @param type     图鉴类型
     * @return 指定类型的图鉴汇总
     */
    public Summary summary(int playerId, EntryType type) {
        Set<Integer> unlocked = loadUnlocked(playerId, type);
        Map<Integer, String> catalog = catalogOf(type);
        List<Entry> recent = new ArrayList<>();
        for (Map.Entry<Integer, String> e : catalog.entrySet()) {
            boolean ok = unlocked.contains(e.getKey());
            Instant at = ok ? mem.getOrDefault(playerId + ":" + type.name() + ":" + e.getKey(), Instant.EPOCH) : null;
            if (ok) {
                recent.add(new Entry(type, e.getKey(), e.getValue(), true, at));
            }
        }
        int total = catalog.size();
        int n = unlocked.size();
        double pct = total == 0 ? 0 : (n * 100.0 / total);
        return new Summary(n, total, Math.round(pct * 10) / 10.0, recent);
    }

    /**
     * 返回玩家的图鉴概览。
     *
     * <p>按类型分别输出统计值，但刻意跳过 RELIC_SET（遗器套装）类型，说明该类型当前只在 catalogOf 中保留，
     * 实际概览可能由其他模块或后续版本接管。
     *
     * @param playerId 玩家 ID
     * @return 类型名 → {unlocked,total,percent} 的映射
     */
    public Map<String, Object> overview(int playerId) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (EntryType t : EntryType.values()) {
            if (t == EntryType.RELIC_SET) {
                continue;
            }
            Summary s = summary(playerId, t);
            out.put(t.name(), Map.of("unlocked", s.unlocked(), "total", s.total(), "percent", s.percent()));
        }
        return out;
    }

    /**
     * 读取某类型当前已解锁的图鉴项 ID 集合。
     *
     * <p>先从内存缓存中收集，再合并数据库结果，保证查询面板时尽量不漏数据。
     *
     * @param playerId 玩家 ID
     * @param type     图鉴类型
     * @return 已解锁 ID 集合
     */
    private Set<Integer> loadUnlocked(int playerId, EntryType type) {
        java.util.HashSet<Integer> set = new java.util.HashSet<>();
        String prefix = playerId + ":" + type.name() + ":";
        for (String k : mem.keySet()) {
            if (k.startsWith(prefix)) {
                set.add(Integer.parseInt(k.substring(prefix.length())));
            }
        }
        try {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT entry_id FROM player_handbook WHERE player_id=? AND entry_type=?",
                    playerId, type.name());
            for (Map<String, Object> row : rows) {
                set.add(((Number) row.get("entry_id")).intValue());
            }
        } catch (Exception ignored) {
        }
        return set;
    }

    /**
     * 根据图鉴类型返回对应的静态目录。
     *
     * @param type 图鉴类型
     * @return 该类型的 ID → 名称目录
     */
    private Map<Integer, String> catalogOf(EntryType type) {
        return switch (type) {
            case AVATAR -> AVATARS;
            case LIGHT_CONE -> LIGHT_CONES;
            case ENEMY -> ENEMIES;
            case RELIC_SET -> Map.of(501, "野穗伴行的快枪手", 502, "密林卧雪的猎人", 601, "繁星璀璨的天才");
        };
    }
}
