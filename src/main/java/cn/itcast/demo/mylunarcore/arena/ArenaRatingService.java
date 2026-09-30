package cn.itcast.demo.mylunarcore.arena;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 竞技场积分服务（ELO 模型）。
 *
 * <p>职责：
 * <ul>
 *   <li>为每位玩家维护竞技场评分 rating、胜场 wins、败场 losses；</li>
 *   <li>按赛季（每 4 周一季）切换评分归属，赛季 key 由 {@link #currentSeasonKey()} 计算；</li>
 *   <li>提供单场对局结算 {@link #settleMatch}，按标准 ELO 公式调整双方评分；</li>
 *   <li>提供排行榜查询 {@link #top}，优先读数据库，不可用时回退内存数据；</li>
 *   <li>赛季轮换时通过 {@link #rolloverSeasonIfNeeded()} 将旧赛季玩家重置到默认分。</li>
 * </ul>
 *
 * <p>存储策略：
 * <ul>
 *   <li>内存缓存 memory 用于热点读取与数据库不可用时兜底；</li>
 *   <li>数据库表 arena_rating 保存玩家赛季评分快照；</li>
 *   <li>persist() 采用 upsert 语义，保证同一玩家同一赛季只有一条最新记录。</li>
 * </ul>
 */
@Service
public class ArenaRatingService {

    /** 竞技场匹配模式 ID：供其他系统识别"此对局属于竞技场"。 */
    public static final int MATCH_MODE_ARENA = 10;
    /** 默认初始分，所有新玩家或赛季重置后都从该值开始。 */
    private static final int DEFAULT_RATING = 1000;
    /** ELO 更新系数，值越大分数波动越快。 */
    private static final int K_FACTOR = 32;
    /** 赛季计算使用上海时区，确保周一切赛判断稳定。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /**
     * 玩家竞技场评分快照。
     *
     * @param playerId  玩家 ID
     * @param rating    当前评分
     * @param wins      本赛季胜场
     * @param losses    本赛季败场
     * @param seasonKey 所属赛季 key
     */
    public record Rating(int playerId, int rating, int wins, int losses, String seasonKey) {}

    /**
     * 单场对局结算结果。
     *
     * @param winnerId          胜者 ID
     * @param loserId           败者 ID
     * @param winnerRatingAfter 胜者结算后的评分
     * @param loserRatingAfter  败者结算后的评分
     */
    public record MatchResult(int winnerId, int loserId, int winnerRatingAfter, int loserRatingAfter) {}

    /**
     * 排行榜条目。
     *
     * @param playerId 玩家 ID
     * @param rating   当前评分
     * @param rank     名次（从 1 开始）
     */
    public record RankEntry(int playerId, int rating, int rank) {}

    /** 数据库访问模板。 */
    private final JdbcTemplate jdbc;
    /** 内存评分缓存：playerId → Rating。 */
    private final ConcurrentHashMap<Integer, Rating> memory = new ConcurrentHashMap<>();

    public ArenaRatingService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 获取玩家当前赛季评分；若内存/数据库中都无记录则新建默认分。
     *
     * <p>优先返回内存缓存，且要求缓存记录仍属于当前赛季；否则再查询数据库。
     * 若数据库查到的记录仍属于当前赛季，则回填内存；否则按默认分新建并持久化。
     *
     * @param playerId 玩家 ID
     * @return 当前赛季评分快照
     */
    public Rating getOrCreate(int playerId) {
        Rating mem = memory.get(playerId);
        if (mem != null && currentSeasonKey().equals(mem.seasonKey())) {
            return mem;
        }
        try {
            Rating fromDb = jdbc.query("""
                    SELECT player_id, rating, wins, losses, season_key FROM arena_rating WHERE player_id = ?
                    """, rs -> {
                if (!rs.next()) {
                    return null;
                }
                return new Rating(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getString(5));
            }, playerId);
            if (fromDb != null && currentSeasonKey().equals(fromDb.seasonKey())) {
                memory.put(playerId, fromDb);
                return fromDb;
            }
        } catch (Exception ignored) {
            // DB 不可用时回退默认值
        }
        Rating created = new Rating(playerId, DEFAULT_RATING, 0, 0, currentSeasonKey());
        persist(created);
        return created;
    }

    /**
     * 结算一场 PVP：按 ELO 公式同时更新胜者和败者评分。
     *
     * <p>公式：
     * <ul>
     *   <li>expectedW = 1 / (1 + 10^((Rb-Ra)/400))</li>
     *   <li>胜者新分 = Ra + K * (1 - expectedW)</li>
     *   <li>败者新分 = Rb + K * (0 - expectedL)</li>
     * </ul>
     * 结算后评分下限不低于 100，避免无限掉分。
     *
     * @param winnerId 胜者 ID
     * @param loserId  败者 ID
     * @return 对局结算结果
     */
    public MatchResult settleMatch(int winnerId, int loserId) {
        Rating w = getOrCreate(winnerId);
        Rating l = getOrCreate(loserId);
        double expectedW = 1.0 / (1.0 + Math.pow(10, (l.rating() - w.rating()) / 400.0));
        double expectedL = 1.0 - expectedW;
        int newW = (int) Math.round(w.rating() + K_FACTOR * (1.0 - expectedW));
        int newL = (int) Math.round(l.rating() + K_FACTOR * (0.0 - expectedL));
        Rating wNext = new Rating(winnerId, Math.max(100, newW), w.wins() + 1, w.losses(), w.seasonKey());
        Rating lNext = new Rating(loserId, Math.max(100, newL), l.wins(), l.losses() + 1, l.seasonKey());
        persist(wNext);
        persist(lNext);
        return new MatchResult(winnerId, loserId, wNext.rating(), lNext.rating());
    }

    /**
     * 查询当前赛季排行榜前 N 名。
     *
     * <p>优先从数据库按 rating DESC, player_id ASC 读取；若库不可用或返回为空，
     * 再回退到内存缓存中的当前赛季数据。
     *
     * @param topN 需要返回的名次数量，上限会被裁剪到 [1, 100]
     * @return 排行榜条目列表
     */
    public List<RankEntry> top(int topN) {
        int limit = Math.max(1, Math.min(topN, 100));
        String season = currentSeasonKey();
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT player_id, rating FROM arena_rating
                    WHERE season_key = ?
                    ORDER BY rating DESC, player_id ASC
                    LIMIT ?
                    """, season, limit);
            List<RankEntry> out = new ArrayList<>();
            int rank = 1;
            for (Map<String, Object> row : rows) {
                out.add(new RankEntry(((Number) row.get("player_id")).intValue(),
                        ((Number) row.get("rating")).intValue(), rank++));
            }
            if (!out.isEmpty()) {
                return out;
            }
        } catch (Exception ignored) {
            // memory
        }
        List<Rating> all = memory.values().stream()
                .filter(r -> season.equals(r.seasonKey()))
                .sorted((a, b) -> Integer.compare(b.rating(), a.rating()))
                .limit(limit)
                .toList();
        List<RankEntry> out = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            out.add(new RankEntry(all.get(i).playerId(), all.get(i).rating(), i + 1));
        }
        return out;
    }

    /**
     * 检查是否需要切换赛季，并把旧赛季玩家重置到默认分。
     *
     * <p>实现思路：
     * <ol>
     *   <li>遍历内存缓存，把不属于当前赛季的玩家先重置并持久化；</li>
     *   <li>再执行一次批量 SQL 更新，把数据库中旧赛季行的 rating/wins/losses/season_key 改成当前赛季。</li>
     * </ol>
     *
     * @return 被重置的内存记录数
     */
    public int rolloverSeasonIfNeeded() {
        String season = currentSeasonKey();
        int reset = 0;
        for (Map.Entry<Integer, Rating> e : memory.entrySet()) {
            if (!season.equals(e.getValue().seasonKey())) {
                Rating next = new Rating(e.getKey(), DEFAULT_RATING, 0, 0, season);
                persist(next);
                reset++;
            }
        }
        try {
            // 不删历史行：新赛季靠 season_key 区分；可选归档
            jdbc.update("""
                    UPDATE arena_rating SET rating=?, wins=0, losses=0, season_key=?
                    WHERE season_key <> ?
                    """, DEFAULT_RATING, season, season);
        } catch (Exception ignored) {
            // ignore
        }
        return reset;
    }

    /**
     * 计算当前赛季 key。
     *
     * <p>规则：以本地时间的"周一"为基准，再按 4 周一个赛季计算，
     * 返回字符串形式的赛季编号（例如 A123）。
     *
     * @return 当前赛季 key
     */
    public static String currentSeasonKey() {
        LocalDate monday = LocalDate.now(ZONE).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        // 每 4 周一季
        long weekIndex = monday.toEpochDay() / 7;
        return "A" + (weekIndex / 4);
    }

    /**
     * 将最新评分快照写入内存与数据库。
     *
     * <p>数据库使用 MySQL 风格的 INSERT ... ON DUPLICATE KEY UPDATE 做 upsert，
     * 确保同一个玩家同一赛季只有一条最新记录。
     *
     * @param r 评分快照
     */
    private void persist(Rating r) {
        memory.put(r.playerId(), r);
        try {
            jdbc.update("""
                    INSERT INTO arena_rating (player_id, rating, wins, losses, season_key)
                    VALUES (?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE rating=VALUES(rating), wins=VALUES(wins),
                      losses=VALUES(losses), season_key=VALUES(season_key)
                    """, r.playerId(), r.rating(), r.wins(), r.losses(), r.seasonKey());
        } catch (Exception ignored) {
            // memory only
        }
    }
}
