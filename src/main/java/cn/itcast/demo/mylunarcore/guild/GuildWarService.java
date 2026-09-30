package cn.itcast.demo.mylunarcore.guild;

import cn.itcast.demo.mylunarcore.common.AppLogger;
import cn.itcast.demo.mylunarcore.common.LogCategory;
import org.slf4j.Logger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 公会战闭环服务：匹配 → 战斗报分 → 积分排行 → 周期结算发奖。
 * <p>
 * 业务含义：公会战是公会间对抗赛季玩法。以“周”（周一 00:00 ~ 下周一 00:00，Asia/Shanghai）
 * 为一个赛季（season）。赛季内公会可发起匹配（match）：
 * <ul>
 *   <li><b>匹配</b>：玩家代表公会申请参赛，优先复用本公会进行中的比赛；否则若赛季内有等待中的对手
 *       （status=WAITING 且 guild_b_id=0）则撮合成 MATCHED，否则新建一个 WAITING 半场等待对手。</li>
 *   <li><b>报分</b>：战斗结束后任一参战方上报双方分数，判定胜负（平局判 A 胜），写入 SETTLED 状态，
 *       胜方 +3 分、负方 +1 分，并记入赛季积分表。</li>
 *   <li><b>排行</b>：按赛季积分 points 降序、胜场降序取 TopN。</li>
 *   <li><b>结算</b>：赛季到期后由调度触发 {@link #settleSeasonIfDue()}，为 Top10 公会发放公会经验奖励
 *       （奖励公式 500 - (rank-1)*40，下限 50），并标记赛季已结算，防止重复发奖。</li>
 * </ul>
 * <p>存储策略：主写 MySQL（guild_war_season / guild_war_match / guild_war_score），
 * 内存 ConcurrentHashMap 作为启动早期/DB 异常时的兜底，双写保持一致。
 */
@Service
public class GuildWarService {

    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_ACTIVITY, GuildWarService.class);
    /** 赛季时区固定为东八区（Asia/Shanghai）。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    /** 胜场积分。 */
    private static final int WIN_POINTS = 3;
    /** 负场积分。 */
    private static final int LOSE_POINTS = 1;

    /**
     * 赛季信息：唯一标识赛季，控制赛季窗口与结算状态。
     *
     * @param seasonId   赛季自增 ID
     * @param seasonKey  赛季业务键（形如 "GW-2026-08-10"，取周一日期）
     * @param openAt     赛季开始时间
     * @param closeAt    赛季结束时间
     * @param settled    是否已完成结算（防重复发奖）
     */
    public record SeasonInfo(long seasonId, String seasonKey, Instant openAt, Instant closeAt, boolean settled) {}

    /**
     * 单场比赛信息。
     *
     * @param matchId        比赛自增 ID
     * @param seasonId       所属赛季
     * @param guildAId       参赛方 A（先申请方）
     * @param guildBId       参赛方 B（0 表示仍在等待对手）
     * @param status         状态：WAITING（待匹配）/ MATCHED（已撮合）/ FIGHTING（战斗中）/ SETTLED（已结算）
     * @param scoreA         A 方分数
     * @param scoreB         B 方分数
     * @param winnerGuildId  胜方公会 ID（未决出时 null）
     */
    public record MatchInfo(long matchId, long seasonId, long guildAId, long guildBId,
                            String status, int scoreA, int scoreB, Long winnerGuildId) {}

    /**
     * 赛季排行榜条目。
     *
     * @param guildId 公会 ID
     * @param points  赛季累计积分
     * @param wins    胜场数
     * @param losses  负场数
     * @param rank    名次（1 起）
     */
    public record RankEntry(long guildId, int points, int wins, int losses, int rank) {}

    /**
     * 公会战操作结果。
     *
     * @param success 是否成功
     * @param retcode 错误码（0 成功；2 比赛不存在；3 不在公会；4 比赛状态不允许报分；5 赛季已结束/已结算；6 非本公会比赛）
     * @param match   涉及的比赛信息（成功时返回）
     */
    public record OpResult(boolean success, int retcode, MatchInfo match) {
        /** 构造失败结果。 */
        public static OpResult fail(int code) {
            return new OpResult(false, code, null);
        }
    }

    /** JDBC 访问入口。 */
    private final JdbcTemplate jdbc;
    /** 公会基础服务：按玩家反查其所属公会。 */
    private final GuildService guildService;
    /** 内存赛季 ID 自增（DB 主键不可用时的兜底）。 */
    private final AtomicLong memSeasonSeq = new AtomicLong(1);
    /** 内存比赛 ID 自增（DB 主键不可用时的兜底）。 */
    private final AtomicLong memMatchSeq = new AtomicLong(1);
    /** 内存赛季表兜底：seasonId → 赛季信息。 */
    private final ConcurrentHashMap<Long, SeasonInfo> memSeasons = new ConcurrentHashMap<>();
    /** 内存比赛表兜底：matchId → 比赛信息。 */
    private final ConcurrentHashMap<Long, MatchInfo> memMatches = new ConcurrentHashMap<>();
    /** 内存积分表兜底：key = "seasonId:guildId" → 赛季积分。 */
    private final ConcurrentHashMap<String, Integer> memScores = new ConcurrentHashMap<>();
    /** 日匹配次数：key = "playerId:yyyy-MM-dd"。 */
    private final ConcurrentHashMap<String, Integer> dailyMatchCount = new ConcurrentHashMap<>();

    public GuildWarService(JdbcTemplate jdbc, GuildService guildService) {
        this.jdbc = jdbc;
        this.guildService = guildService;
    }

    /**
     * 确保当前赛季存在：以本周周一为起点生成赛季窗口。
     * 若 DB 已有同 season_key 的赛季则复用；否则插入新赛季（DB 异常时回退内存）。
     */
    public SeasonInfo ensureCurrentSeason() {
        String key = currentSeasonKey();
        Optional<SeasonInfo> existing = findSeasonByKey(key);
        if (existing.isPresent()) {
            return existing.get();
        }
        LocalDate monday = LocalDate.now(ZONE).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        Instant open = monday.atStartOfDay(ZONE).toInstant();
        Instant close = monday.plusDays(7).atStartOfDay(ZONE).toInstant();
        try {
            GeneratedKeyHolder kh = new GeneratedKeyHolder();
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement("""
                        INSERT INTO guild_war_season (season_key, open_at, close_at, settled)
                        VALUES (?, ?, ?, 0)
                        """, Statement.RETURN_GENERATED_KEYS);
                ps.setString(1, key);
                ps.setTimestamp(2, Timestamp.from(open));
                ps.setTimestamp(3, Timestamp.from(close));
                return ps;
            }, kh);
            long id = kh.getKey() == null ? memSeasonSeq.getAndIncrement() : kh.getKey().longValue();
            SeasonInfo s = new SeasonInfo(id, key, open, close, false);
            memSeasons.put(id, s);
            return s;
        } catch (Exception e) {
            long id = memSeasonSeq.getAndIncrement();
            SeasonInfo s = new SeasonInfo(id, key, open, close, false);
            memSeasons.put(id, s);
            log.debug("guild_war season memory fallback: {}", e.getMessage());
            return s;
        }
    }

    /**
     * 为申请方公会匹配对手：优先同战力段待匹配队列，否则创建等待中的半场。
     * <p>流程：
     * <ol>
     *   <li>校验玩家在公会、赛季未结束/未结算；</li>
     *   <li>若该公会在本季已有未决比赛（WAITING/MATCHED/FIGHTING），直接复用；</li>
     *   <li>否则寻找本季中等待中的对手（WAITING 且 guild_b_id=0），将其填为 MATCHED；</li>
     *   <li>都没有则新建一条 WAITING 半场等待他人。</li>
     * </ol>
     */
    @Transactional
    public OpResult requestMatch(int playerId) {
        Long guildId = guildService.findGuildIdByPlayer(playerId);
        if (guildId == null) {
            return OpResult.fail(3);
        }
        SeasonInfo season = ensureCurrentSeason();
        if (Instant.now().isAfter(season.closeAt()) || season.settled()) {
            return OpResult.fail(5);
        }
        int used = usedMatchesToday(playerId);
        if (used >= dailyMatchLimit()) {
            return OpResult.fail(6);
        }
        Optional<MatchInfo> open = findOpenMatchForGuild(season.seasonId(), guildId);
        if (open.isPresent()) {
            return new OpResult(true, 0, open.get());
        }
        Optional<MatchInfo> waiting = findWaitingOpponent(season.seasonId(), guildId);
        if (waiting.isPresent()) {
            MatchInfo w = waiting.get();
            MatchInfo filled = new MatchInfo(w.matchId(), w.seasonId(), w.guildAId(), guildId,
                    "MATCHED", 0, 0, null);
            persistMatchUpdate(filled);
            memMatches.put(filled.matchId(), filled);
            bumpDailyMatch(playerId);
            return new OpResult(true, 0, filled);
        }
        MatchInfo created = insertMatch(season.seasonId(), guildId, 0L, "WAITING");
        bumpDailyMatch(playerId);
        return new OpResult(true, 0, created);
    }

    public int dailyMatchLimit() {
        return 3;
    }

    public int usedMatchesToday(int playerId) {
        if (playerId <= 0) {
            return 0;
        }
        return dailyMatchCount.getOrDefault(playerId + ":" + LocalDate.now(ZONE), 0);
    }

    public int remainingMatchesToday(int playerId) {
        return Math.max(0, dailyMatchLimit() - usedMatchesToday(playerId));
    }

    private void bumpDailyMatch(int playerId) {
        dailyMatchCount.merge(playerId + ":" + LocalDate.now(ZONE), 1, Integer::sum);
    }

    /**
     * 战斗结束后上报比分；胜方 +3、负方 +1 积分。
     * <p>校验：玩家有公会、比赛存在、比赛处于 MATCHED/FIGHTING、玩家公会属于该比赛任一方。
     * 按双方分数判定胜负（平局按 A 胜），将比赛置为 SETTLED 并写回 DB + 内存，同时累加赛季积分。
     */
    @Transactional
    public OpResult reportBattleResult(int playerId, long matchId, int scoreSelf, int scoreOpponent) {
        Long guildId = guildService.findGuildIdByPlayer(playerId);
        if (guildId == null) {
            return OpResult.fail(3);
        }
        MatchInfo match = loadMatch(matchId);
        if (match == null) {
            return OpResult.fail(2);
        }
        if (!"MATCHED".equals(match.status()) && !"FIGHTING".equals(match.status())) {
            return OpResult.fail(4);
        }
        boolean isA = match.guildAId() == guildId;
        boolean isB = match.guildBId() == guildId;
        if (!isA && !isB) {
            return OpResult.fail(6);
        }
        int scoreA = isA ? scoreSelf : scoreOpponent;
        int scoreB = isB ? scoreSelf : scoreOpponent;
        long winner = scoreA >= scoreB ? match.guildAId() : match.guildBId();
        long loser = winner == match.guildAId() ? match.guildBId() : match.guildAId();
        MatchInfo settled = new MatchInfo(match.matchId(), match.seasonId(), match.guildAId(), match.guildBId(),
                "SETTLED", scoreA, scoreB, winner);
        persistMatchUpdate(settled);
        memMatches.put(settled.matchId(), settled);
        addScore(match.seasonId(), winner, WIN_POINTS, true);
        if (loser > 0) {
            addScore(match.seasonId(), loser, LOSE_POINTS, false);
        }
        return new OpResult(true, 0, settled);
    }

    /**
     * 查询赛季排行榜 TopN（默认排序：积分降序 → 胜场降序 → 公会 ID 升序）。
     * DB 查询失败时回退内存积分表排序。
     */
    public List<RankEntry> seasonRank(long seasonId, int topN) {
        int limit = Math.max(1, Math.min(topN, 100));
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT guild_id, points, wins, losses
                    FROM guild_war_score
                    WHERE season_id = ?
                    ORDER BY points DESC, wins DESC, guild_id ASC
                    LIMIT ?
                    """, seasonId, limit);
            List<RankEntry> out = new ArrayList<>();
            int rank = 1;
            for (Map<String, Object> row : rows) {
                out.add(new RankEntry(
                        ((Number) row.get("guild_id")).longValue(),
                        ((Number) row.get("points")).intValue(),
                        ((Number) row.get("wins")).intValue(),
                        ((Number) row.get("losses")).intValue(),
                        rank++));
            }
            if (!out.isEmpty()) {
                return out;
            }
        } catch (Exception ignored) {
            // memory fallback
        }
        String prefix = seasonId + ":";
        List<RankEntry> tmp = new ArrayList<>();
        for (Map.Entry<String, Integer> e : memScores.entrySet()) {
            if (!e.getKey().startsWith(prefix)) {
                continue;
            }
            long gid = Long.parseLong(e.getKey().substring(prefix.length()));
            tmp.add(new RankEntry(gid, e.getValue(), 0, 0, 0));
        }
        tmp.sort(Comparator.comparingInt(RankEntry::points).reversed());
        List<RankEntry> out = new ArrayList<>();
        for (int i = 0; i < Math.min(limit, tmp.size()); i++) {
            RankEntry e = tmp.get(i);
            out.add(new RankEntry(e.guildId(), e.points(), e.wins(), e.losses(), i + 1));
        }
        return out;
    }

    /**
     * 周期结算：标记赛季已结算，并向 TopN 公会记贡献奖励（邮件/商店券由运营扩展）。
     * <p>由外部定时任务触发：当前赛季若已到期未结算则结算当前赛季；
     * 否则尝试结算上一赛季（prevKey）。奖励公式：500 - (rank-1)*40，最低 50 点公会经验。
     *
     * @return 本次结算发放的公会数量
     */
    @Transactional
    public int settleSeasonIfDue() {
        SeasonInfo season = ensureCurrentSeason();
        if (!Instant.now().isAfter(season.closeAt()) || season.settled()) {
            // 尝试结算上一周
            String prevKey = previousSeasonKey();
            Optional<SeasonInfo> prev = findSeasonByKey(prevKey);
            if (prev.isEmpty() || prev.get().settled()) {
                return 0;
            }
            return settleSeason(prev.get());
        }
        return 0;
    }

    /** 执行单赛季结算：置 settled 标记并给 Top10 公会发放经验奖励（DB 异常时降级为仅日志）。 */
    private int settleSeason(SeasonInfo season) {
        List<RankEntry> rank = seasonRank(season.seasonId(), 10);
        try {
            jdbc.update("UPDATE guild_war_season SET settled = 1 WHERE season_id = ?", season.seasonId());
        } catch (Exception e) {
            log.debug("settle season db mark skipped: {}", e.getMessage());
        }
        memSeasons.put(season.seasonId(),
                new SeasonInfo(season.seasonId(), season.seasonKey(), season.openAt(), season.closeAt(), true));
        for (RankEntry e : rank) {
            int reward = Math.max(50, 500 - (e.rank() - 1) * 40);
            try {
                jdbc.update("UPDATE guild SET exp = exp + ? WHERE guild_id = ?", reward, e.guildId());
            } catch (Exception ignored) {
                // ignore
            }
            log.info("guild_war_settle season={} guild={} rank={} rewardExp={}",
                    season.seasonKey(), e.guildId(), e.rank(), reward);
        }
        return rank.size();
    }

    /** 生成当前赛季键：本周周一日期，形如 "GW-2026-08-10"。 */
    public static String currentSeasonKey() {
        LocalDate monday = LocalDate.now(ZONE).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return "GW-" + monday;
    }

    /** 生成上一周赛季键（供补结算使用）。 */
    private static String previousSeasonKey() {
        LocalDate monday = LocalDate.now(ZONE).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).minusWeeks(1);
        return "GW-" + monday;
    }

    /** 按赛季键查找赛季（DB 优先，失败回退内存）。 */
    private Optional<SeasonInfo> findSeasonByKey(String key) {
        try {
            SeasonInfo fromDb = jdbc.query("""
                    SELECT season_id, season_key, open_at, close_at, settled
                    FROM guild_war_season WHERE season_key = ?
                    """, rs -> {
                if (!rs.next()) {
                    return null;
                }
                return new SeasonInfo(
                        rs.getLong(1), rs.getString(2),
                        rs.getTimestamp(3).toInstant(),
                        rs.getTimestamp(4).toInstant(),
                        rs.getInt(5) == 1);
            }, key);
            if (fromDb != null) {
                return Optional.of(fromDb);
            }
        } catch (Exception ignored) {
            // memory fallback
        }
        return memSeasons.values().stream().filter(s -> key.equals(s.seasonKey())).findFirst();
    }

    /** 查询公会当前在本赛季中未决（WAITING/MATCHED/FIGHTING）的比赛，优先 DB 后内存。 */
    private Optional<MatchInfo> findOpenMatchForGuild(long seasonId, long guildId) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT match_id, season_id, guild_a_id, guild_b_id, status, score_a, score_b, winner_guild_id
                    FROM guild_war_match
                    WHERE season_id = ? AND status IN ('WAITING','MATCHED','FIGHTING')
                      AND (guild_a_id = ? OR guild_b_id = ?)
                    LIMIT 1
                    """, seasonId, guildId, guildId);
            if (!rows.isEmpty()) {
                return Optional.of(mapMatch(rows.get(0)));
            }
        } catch (Exception ignored) {
            // memory
        }
        return memMatches.values().stream()
                .filter(m -> m.seasonId() == seasonId)
                .filter(m -> "WAITING".equals(m.status()) || "MATCHED".equals(m.status()) || "FIGHTING".equals(m.status()))
                .filter(m -> m.guildAId() == guildId || m.guildBId() == guildId)
                .findFirst();
    }

    /** 寻找本赛季中等待对手的半场（WAITING 且 guild_b_id=0，且不是本公会发起的），优先 DB 后内存。 */
    private Optional<MatchInfo> findWaitingOpponent(long seasonId, long guildId) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT match_id, season_id, guild_a_id, guild_b_id, status, score_a, score_b, winner_guild_id
                    FROM guild_war_match
                    WHERE season_id = ? AND status = 'WAITING' AND guild_a_id <> ? AND guild_b_id = 0
                    ORDER BY match_id ASC LIMIT 1
                    """, seasonId, guildId);
            if (!rows.isEmpty()) {
                return Optional.of(mapMatch(rows.get(0)));
            }
        } catch (Exception ignored) {
            // memory
        }
        return memMatches.values().stream()
                .filter(m -> m.seasonId() == seasonId && "WAITING".equals(m.status())
                        && m.guildAId() != guildId && m.guildBId() == 0)
                .findFirst();
    }

    /** 插入一场比赛（guildB=0 表示待匹配），DB 失败时回退内存自增 ID。 */
    private MatchInfo insertMatch(long seasonId, long guildA, long guildB, String status) {
        try {
            GeneratedKeyHolder kh = new GeneratedKeyHolder();
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement("""
                        INSERT INTO guild_war_match (season_id, guild_a_id, guild_b_id, status)
                        VALUES (?, ?, ?, ?)
                        """, Statement.RETURN_GENERATED_KEYS);
                ps.setLong(1, seasonId);
                ps.setLong(2, guildA);
                ps.setLong(3, guildB);
                ps.setString(4, status);
                return ps;
            }, kh);
            long id = kh.getKey() == null ? memMatchSeq.getAndIncrement() : kh.getKey().longValue();
            MatchInfo m = new MatchInfo(id, seasonId, guildA, guildB, status, 0, 0, null);
            memMatches.put(id, m);
            return m;
        } catch (Exception e) {
            long id = memMatchSeq.getAndIncrement();
            MatchInfo m = new MatchInfo(id, seasonId, guildA, guildB, status, 0, 0, null);
            memMatches.put(id, m);
            return m;
        }
    }

    /** 将比赛状态变更写回 DB（SETTLED 时记录结算时间戳）；DB 异常仅保留内存态。 */
    private void persistMatchUpdate(MatchInfo m) {
        try {
            jdbc.update("""
                    UPDATE guild_war_match
                    SET guild_b_id=?, status=?, score_a=?, score_b=?, winner_guild_id=?,
                        settled_at=CASE WHEN ?='SETTLED' THEN NOW(3) ELSE settled_at END
                    WHERE match_id=?
                    """, m.guildBId(), m.status(), m.scoreA(), m.scoreB(),
                    m.winnerGuildId(), m.status(), m.matchId());
        } catch (Exception e) {
            log.debug("guild_war match update memory only: {}", e.getMessage());
        }
    }

    /** 按比赛 ID 加载比赛：内存优先（最新状态），DB 兜底。 */
    private MatchInfo loadMatch(long matchId) {
        MatchInfo mem = memMatches.get(matchId);
        if (mem != null) {
            return mem;
        }
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT match_id, season_id, guild_a_id, guild_b_id, status, score_a, score_b, winner_guild_id
                    FROM guild_war_match WHERE match_id = ?
                    """, matchId);
            return rows.isEmpty() ? null : mapMatch(rows.get(0));
        } catch (Exception e) {
            return null;
        }
    }

    /** 累加赛季积分并记录胜/负场：内存 + DB（ON DUPLICATE KEY 原子累加）。 */
    private void addScore(long seasonId, long guildId, int points, boolean win) {
        memScores.merge(seasonId + ":" + guildId, points, Integer::sum);
        try {
            jdbc.update("""
                    INSERT INTO guild_war_score (season_id, guild_id, points, wins, losses)
                    VALUES (?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                      points = points + VALUES(points),
                      wins = wins + VALUES(wins),
                      losses = losses + VALUES(losses)
                    """, seasonId, guildId, points, win ? 1 : 0, win ? 0 : 1);
        } catch (Exception e) {
            log.debug("guild_war score memory only: {}", e.getMessage());
        }
    }

    /** 将 DB 查询行映射为 {@link MatchInfo}（winner_guild_id 允许 NULL → null）。 */
    private static MatchInfo mapMatch(Map<String, Object> row) {
        Number winner = (Number) row.get("winner_guild_id");
        return new MatchInfo(
                ((Number) row.get("match_id")).longValue(),
                ((Number) row.get("season_id")).longValue(),
                ((Number) row.get("guild_a_id")).longValue(),
                ((Number) row.get("guild_b_id")).longValue(),
                String.valueOf(row.get("status")),
                ((Number) row.get("score_a")).intValue(),
                ((Number) row.get("score_b")).intValue(),
                winner == null ? null : winner.longValue());
    }
}
