package cn.itcast.demo.mylunarcore.abyss;

import cn.itcast.demo.mylunarcore.common.AppLogger;
import cn.itcast.demo.mylunarcore.common.LogCategory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 忘却之庭 / 深渊周期挑战服务。
 *
 * <p>核心职责：
 * <ul>
 *   <li>从 <code>data/AbyssSeasonConfigs.json</code> 加载赛季配置（每期持续天数 cycleDays、
 *       单层最大星数 maxStarsPerFloor、楼层列表 floors、累计星级奖励档位 starRewards）。</li>
 *   <li>以固定纪元日对齐的方式计算"当前赛季"的起止时间（默认每 14 天一个赛季），保证所有节点计算出的
 *       赛季 key 一致。</li>
 *   <li>维护每个玩家在本赛季内的通关进度：已通过的最高层 clearedFloor、累计星数 stars、
 *       每层获取的星数 floorStars。</li>
 *   <li>支持"多队轮战"校验：上报通关时必须提供足够的队伍阵容 ID（teamsRequired），防止单队蒙混过关。</li>
 *   <li>进度先写入内存缓存（ConcurrentHashMap）加速读取，再异步/同步落库到
 *       <code>abyss_player_progress</code> 表；数据库不可用时降级为纯内存运行。</li>
 * </ul>
 *
 * <p>与现有 Challenge（挑战）协议可并存：本服务专注"赛季重置 + 星级奖励"这一层业务，
 * 具体战斗仍走挑战系统。
 */
@Service
public class AbyssSeasonService {

    /** 业务日志记录器：日志分类为 BUSIENESS_ACTIVITY（活动类业务）。 */
    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_ACTIVITY, AbyssSeasonService.class);
    /** 赛季起止时间统一使用上海时区计算，避免部署机器时区不同导致赛季边界不一致。 */
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    /**
     * 单层深渊配置。
     *
     * @param floor            层数（从 1 开始，越高越难）
     * @param recommendedPower 推荐综合战力，用于客户端展示与难度引导
     * @param teamsRequired    通关该层至少需要几支独立队伍（多队轮战）
     * @param stageIds         该层关联的关卡 ID 列表（对应挑战系统的关卡）
     */
    public record FloorConfig(int floor, int recommendedPower, int teamsRequired, List<Integer> stageIds) {}

    /**
     * 当前赛季的信息。
     *
     * @param seasonKey   赛季唯一标识，形如 "abyss-2026-01-05"
     * @param openAt      赛季开始时间（上海时区当天 0 点）
     * @param closeAt     赛季结束时间（openAt + cycleDays）
     * @param floors      该赛季的楼层配置快照
     * @param starRewards 累计星级奖励档位列表（按所需星数升序）
     */
    public record SeasonInfo(String seasonKey, Instant openAt, Instant closeAt,
                             List<FloorConfig> floors, List<Map<String, Object>> starRewards) {}

    /**
     * 玩家在某个赛季中的进度。
     *
     * @param seasonKey    所属赛季标识
     * @param clearedFloor 当前已通关的最高层（0 表示尚未通关任何层）
     * @param stars        累计获得的星数（所有已通层星数之和）
     * @param floorStars   每层获得星数映射：层号 → 星数（1..maxStarsPerFloor）
     */
    public record PlayerProgress(String seasonKey, int clearedFloor, int stars, Map<Integer, Integer> floorStars) {}

    /**
     * 上报通关的结果。
     *
     * @param success     本次上报是否成功处理
     * @param retcode     业务错误码：0=成功，1=楼层配置不存在，2=队伍数不足，3=楼层未解锁（需先通上一层）
     * @param starsGained 本次相比历史记录净增加的星数（重复通关无提升时为 0）
     * @param totalStars  更新后的累计总星数
     * @param rewardHint  本次是否触达新的星级奖励档位；若有则返回奖励描述文本，用 " | " 分隔多个
     */
    public record ClearResult(boolean success, int retcode, int starsGained, int totalStars, String rewardHint) {}

    /** 数据库访问模板，负责读写 abyss_player_progress 表。 */
    private final JdbcTemplate jdbc;
    /** JSON 解析器，用于读取配置与序列化每层星数映射。 */
    private final ObjectMapper objectMapper;
    /** 一个赛季的持续天数，默认 14 天，可由配置文件覆盖。 */
    private int cycleDays = 14;
    /** 每层最多可获得的星数，默认 3 星。 */
    private int maxStarsPerFloor = 3;
    /** 楼层配置列表（启动时从 JSON 加载）。 */
    private final List<FloorConfig> floors = new ArrayList<>();
    /** 累计星级奖励档位列表（启动时从 JSON 加载）。 */
    private final List<Map<String, Object>> starRewards = new ArrayList<>();
    /** 内存缓存：cacheKey(playerId:seasonKey) → 玩家该赛季进度，DB 不可用或高频读取时兜底。 */
    private final ConcurrentHashMap<String, PlayerProgress> mem = new ConcurrentHashMap<>();

    /** 依赖注入：需要 JdbcTemplate 做持久化、ObjectMapper 做配置解析。 */
    public AbyssSeasonService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * 服务启动后自动加载深渊配置。
     *
     * <p>读取 data/AbyssSeasonConfigs.json：若文件不存在则保持默认值
     * （cycleDays=14、maxStarsPerFloor=3、空楼层/空奖励），避免启动失败。
     * 解析失败仅告警不抛异常，保证服务可用性。
     */
    @PostConstruct
    public void load() {
        try {
            Path p = Path.of("data/AbyssSeasonConfigs.json");
            // 配置文件缺失时静默返回，沿用代码内默认配置
            if (!Files.exists(p)) {
                return;
            }
            JsonNode root = objectMapper.readTree(Files.readString(p));
            // 赛季持续天数与单层最大星数，均提供默认值兜底
            cycleDays = root.path("cycleDays").asInt(14);
            maxStarsPerFloor = root.path("maxStarsPerFloor").asInt(3);
            // 先清空旧配置再重新装载，支持热重载语义（重复调用 load 不残留脏数据）
            floors.clear();
            for (JsonNode f : root.path("floors")) {
                List<Integer> stages = new ArrayList<>();
                // 把 stageIds 数组逐个转为 int 列表
                f.path("stageIds").forEach(n -> stages.add(n.asInt()));
                floors.add(new FloorConfig(
                        f.path("floor").asInt(),
                        f.path("recommendedPower").asInt(),
                        f.path("teamsRequired").asInt(1),
                        List.copyOf(stages)));
            }
            starRewards.clear();
            for (JsonNode r : root.path("starRewards")) {
                Map<String, Object> m = new LinkedHashMap<>();
                // 奖励档位：达到 stars 颗星即可领取 rewardDesc 描述的奖励
                m.put("stars", r.path("stars").asInt());
                m.put("rewardDesc", r.path("rewardDesc").asText());
                starRewards.add(m);
            }
        } catch (Exception e) {
            // 配置损坏时仅告警，不影响服务启动，使用内置默认配置继续运行
            log.warn("AbyssSeasonConfigs load failed: {}", e.toString());
        }
    }

    /**
     * 计算"当前赛季"的起止时间。
     *
     * <p>对齐算法：先取本周一作为基准日，再把基准日的纪元日数按 cycleDays 向下取整，
     * 得到赛季起始纪元日（保证任意时刻计算出的赛季边界稳定、跨节点一致）；
     * 赛季结束时间为起始时间 + cycleDays 天。
     *
     * @return 当前赛季的完整信息快照
     */
    public SeasonInfo currentSeason() {
        // 取本周一作为对齐锚点（previousOrSame 保证周一对齐到自身）
        LocalDate start = LocalDate.now(ZONE).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        // 对齐 cycleDays：以固定纪元周对齐，floorDiv 保证负纪元日（1970 前）也能正确向下取整
        long epochDay = start.toEpochDay();
        long cycleIndex = Math.floorDiv(epochDay, Math.max(1, cycleDays));
        LocalDate open = LocalDate.ofEpochDay(cycleIndex * cycleDays);
        // 结束时间 = 起始时间 + 持续天数（左闭右开区间）
        LocalDate close = open.plusDays(cycleDays);
        String key = "abyss-" + open;
        return new SeasonInfo(
                key,
                open.atStartOfDay(ZONE).toInstant(),
                close.atStartOfDay(ZONE).toInstant(),
                List.copyOf(floors),
                List.copyOf(starRewards));
    }

    /**
     * 查询某玩家在当前赛季的进度。
     *
     * <p>先查内存缓存（键为 playerId:seasonKey），未命中则从数据库加载并回填缓存。
     *
     * @param playerId 玩家 ID
     * @return 该玩家当前赛季进度；从未有记录时返回全零初始进度
     */
    public PlayerProgress progress(int playerId) {
        String key = currentSeason().seasonKey();
        String cacheKey = playerId + ":" + key;
        PlayerProgress cached = mem.get(cacheKey);
        if (cached != null) {
            return cached;
        }
        PlayerProgress loaded = loadDb(playerId, key);
        mem.put(cacheKey, loaded);
        return loaded;
    }

    /**
     * 上报通关结果。
     *
     * <p>校验流程（按失败码顺序）：
     * <ol>
     *   <li>楼层配置不存在（invalid_floor）</li>
     *   <li>提供的有效队伍数 < 该层 teamsRequired（need_teams:N）</li>
     *   <li>跨层挑战：目标层 > 已通过层 + 1（floor_locked，禁止跳层）</li>
     *   <li>重复刷星但本次星数未超过历史记录（no_improve，不重复计星）</li>
     * </ol>
     *
     * @param playerId      玩家 ID
     * @param floor         上报通关的层数
     * @param stars         本次获得的星数（会被截断到 1..maxStarsPerFloor）
     * @param teamLineupIds 出战队伍阵容 ID 列表，用于校验多队轮战要求
     * @return 通关处理结果（成功标志、错误码、净增星数、累计星数、新触达的奖励提示）
     */
    public ClearResult reportClear(int playerId, int floor, int stars, List<Integer> teamLineupIds) {
        FloorConfig cfg = floors.stream().filter(f -> f.floor() == floor).findFirst().orElse(null);
        if (cfg == null) {
            return new ClearResult(false, 1, 0, 0, "invalid_floor");
        }
        // 统计有效队伍数：过滤 null 与 <=0 的 ID
        int teams = teamLineupIds == null ? 0 : (int) teamLineupIds.stream().filter(id -> id != null && id > 0).count();
        if (teams < cfg.teamsRequired()) {
            return new ClearResult(false, 2, 0, progress(playerId).stars(),
                    "need_teams:" + cfg.teamsRequired());
        }
        PlayerProgress prev = progress(playerId);
        // 禁止跳层：最多挑战"当前最高层 + 1"
        if (floor > prev.clearedFloor() + 1) {
            return new ClearResult(false, 3, 0, prev.stars(), "floor_locked");
        }
        // 星数截断到合法区间 [0, maxStarsPerFloor]
        int gain = Math.max(0, Math.min(maxStarsPerFloor, stars));
        Map<Integer, Integer> floorStars = new LinkedHashMap<>(prev.floorStars());
        int old = floorStars.getOrDefault(floor, 0);
        // 本次星数未超过历史记录时视为无提升，不重复结算
        if (gain <= old) {
            return new ClearResult(true, 0, 0, prev.stars(), "no_improve");
        }
        floorStars.put(floor, gain);
        int total = floorStars.values().stream().mapToInt(Integer::intValue).sum();
        int cleared = Math.max(prev.clearedFloor(), floor);
        PlayerProgress next = new PlayerProgress(prev.seasonKey(), cleared, total, Map.copyOf(floorStars));
        // 更新内存缓存并落库
        mem.put(playerId + ":" + prev.seasonKey(), next);
        persist(playerId, next);
        // 计算本次跨越了哪些星级奖励档位（before < need && after >= need）
        String rewardHint = starRewardHint(old == 0 ? total : total, prev.stars(), total);
        return new ClearResult(true, 0, gain - old, total, rewardHint);
    }

    /**
     * 计算跨越的星级奖励档位提示。
     *
     * @param ignored 预留参数（当前实现中未使用，保留签名一致性）
     * @param before  结算前的累计星数
     * @param after   结算后的累计星数
     * @return 所有"结算前未达、结算后已达"档位的奖励描述，多个用 " | " 连接；无则返回空串
     */
    private String starRewardHint(int ignored, int before, int after) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> r : starRewards) {
            int need = ((Number) r.get("stars")).intValue();
            // 只报告本次新跨越的档位，避免重复提示已领奖励
            if (before < need && after >= need) {
                if (!sb.isEmpty()) {
                    sb.append(" | ");
                }
                sb.append(r.get("rewardDesc"));
            }
        }
        return sb.toString();
    }

    /**
     * 从数据库加载玩家某赛季的进度。
     *
     * <p>查询 abyss_player_progress 表，找不到记录或查询异常时返回全零初始进度，
     * 保证调用方无需判空。
     *
     * @param playerId  玩家 ID
     * @param seasonKey 赛季标识
     * @return 数据库中的进度；不存在或异常时返回初始进度
     */
    private PlayerProgress loadDb(int playerId, String seasonKey) {
        try {
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT cleared_floor, stars, floor_stars_json FROM abyss_player_progress
                    WHERE player_id=? AND season_key=?
                    """, playerId, seasonKey);
            if (rows.isEmpty()) {
                return new PlayerProgress(seasonKey, 0, 0, Map.of());
            }
            Map<String, Object> row = rows.get(0);
            // floor_stars_json 以 JSON 字符串存储，需反序列化为 Map<Integer,Integer>
            Map<Integer, Integer> fs = parseFloorStars(String.valueOf(row.get("floor_stars_json")));
            return new PlayerProgress(seasonKey,
                    ((Number) row.get("cleared_floor")).intValue(),
                    ((Number) row.get("stars")).intValue(),
                    fs);
        } catch (Exception e) {
            // 数据库不可用时不阻塞业务，返回初始进度走内存兜底
            return new PlayerProgress(seasonKey, 0, 0, Map.of());
        }
    }

    /**
     * 持久化玩家进度到数据库。
     *
     * <p>采用"先 UPDATE 后 INSERT"策略：UPDATE 影响行数为 0 表示记录不存在，
     * 再执行 INSERT 创建；避免并发下的主键冲突异常。持久化失败不影响内存缓存中的最新进度。
     *
     * @param playerId 玩家 ID
     * @param p        待保存的赛季进度
     */
    private void persist(int playerId, PlayerProgress p) {
        try {
            String json = objectMapper.writeValueAsString(p.floorStars());
            int u = jdbc.update("""
                    UPDATE abyss_player_progress SET cleared_floor=?, stars=?, floor_stars_json=?, updated_at=?
                    WHERE player_id=? AND season_key=?
                    """, p.clearedFloor(), p.stars(), json, Instant.now().toString(), playerId, p.seasonKey());
            if (u == 0) {
                jdbc.update("""
                        INSERT INTO abyss_player_progress
                        (player_id, season_key, cleared_floor, stars, floor_stars_json, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """, playerId, p.seasonKey(), p.clearedFloor(), p.stars(), json, Instant.now().toString());
            }
        } catch (Exception ignored) {
            // 持久化失败忽略：内存缓存仍保有最新进度，待下次周期任务或上报时重试
        }
    }

    /**
     * 解析每层星数 JSON 字符串。
     *
     * <p>输入的 JSON 形如 {"1":3,"2":2}，键为层数字符串，值为星数。
     * 对 null、空串、字面量 "null" 及解析异常均返回空 Map。
     *
     * @param json 待解析的 JSON 字符串
     * @return 层号 → 星数 的有序 Map；解析失败返回空 Map
     */
    @SuppressWarnings("unchecked")
    private Map<Integer, Integer> parseFloorStars(String json) {
        if (json == null || json.isBlank() || "null".equals(json)) {
            return Map.of();
        }
        try {
            Map<String, Object> raw = objectMapper.readValue(json, Map.class);
            Map<Integer, Integer> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : raw.entrySet()) {
                // 层数字符串转 int，星数统一转为 int
                out.put(Integer.parseInt(e.getKey()), ((Number) e.getValue()).intValue());
            }
            return out;
        } catch (Exception e) {
            // 脏数据兜底：宁可清空也不让异常冒泡到业务调用方
            return Map.of();
        }
    }
}
