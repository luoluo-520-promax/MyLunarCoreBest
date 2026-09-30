package cn.itcast.demo.mylunarcore.assist;

import cn.itcast.demo.mylunarcore.common.AppLogger;
import cn.itcast.demo.mylunarcore.common.BattleEndedEvent;
import cn.itcast.demo.mylunarcore.common.BattleStartedEvent;
import cn.itcast.demo.mylunarcore.common.ConfigFileService;
import cn.itcast.demo.mylunarcore.common.LogCategory;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.model.LineupEntity;
import cn.itcast.demo.mylunarcore.model.PlayerData;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.repo.PlayerDataRepository;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 全服角色出场与胜率统计服务。
 * <p>
 * 监听战斗开始/结束事件累计各角色出场与胜场，定期落盘持久化；
 * 对外提供 TopN 榜单与摘要文本，供 AI 助手上下文（meta）和推荐逻辑引用。
 */
@Service
public class AvatarUsageStatsService {
    /**
     * 业务日志使用 BUSSINESS_ASSIST 分类，便于单独查看 AI 辅助/统计链路的运行情况。
     */
    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_ASSIST, AvatarUsageStatsService.class);
    /**
     * 读取配置中 aiAssist 节点，决定是否启用出场统计、落盘路径等行为。
     */
    private final LunarCoreProperties properties;
    /**
     * 统一把相对路径解析到仓库/服务器配置目录，避免直接拼硬编码磁盘路径。
     */
    private final ConfigFileService configFileService;
    /**
     * 优先从当前在线会话里取阵容，能避免离线读库造成的延迟和额外 IO。
     */
    private final GameSessionManager sessionManager;
    /**
     * 在线会话没有命中时，回退到持久化玩家数据仓库读取阵容。
     */
    private final PlayerDataRepository playerDataRepository;
    /**
     * 使用 Jackson 序列化/反序列化 JSON 统计文件，文件结构和内存结构保持一致。
     */
    private final ObjectMapper objectMapper = new ObjectMapper();
    /**
     * 按 avatarId 记录“被选中次数”，key 为角色 ID，value 为原子计数器，支持并发累加。
     */
    private final ConcurrentHashMap<Integer, AtomicLong> pickCounts = new ConcurrentHashMap<>();
    /**
     * 按 avatarId 记录“胜利次数”，只在战斗结束且 endStatus=1 时累加。
     */
    private final ConcurrentHashMap<Integer, AtomicLong> winCounts = new ConcurrentHashMap<>();
    /**
     * 暂存 battleId -> 该场战斗参与的角色 ID 列表，结束事件到来时据此计算胜场。
     */
    private final ConcurrentHashMap<Long, List<Integer>> battleAvatars = new ConcurrentHashMap<>();
    /**
     * 统计战斗总数，每次开始战斗都会 +1。
     */
    private final AtomicLong totalBattles = new AtomicLong();
    /**
     * 统计所有角色出场总次数，等于所有 battle 中有效 avatarId 的累积计数。
     */
    private final AtomicLong totalPicks = new AtomicLong();
    /**
     * 脏标记计数器，达到阈值后自动落盘，避免每次事件都写文件。
     */
    private final AtomicLong dirtyCounter = new AtomicLong();
    /**
     * 构造并注入依赖
     */
    public AvatarUsageStatsService(LunarCoreProperties properties,
                                   ConfigFileService configFileService,
                                   GameSessionManager sessionManager,
                                   PlayerDataRepository playerDataRepository) {
        // 将四个依赖注入到字段，后续战斗事件监听和磁盘刷新都要使用。
        this.properties = properties;
        this.configFileService = configFileService; // 赋值 this.configFileService，供 AvatarUsageStatsService 使用
        this.sessionManager = sessionManager; // 赋值 this.sessionManager，供 AvatarUsageStatsService 使用
        this.playerDataRepository = playerDataRepository; // 赋值 this.playerDataRepository，供 AvatarUsageStatsService 使用
    }
    /**
     * 服务启动后初始化
     */
    @PostConstruct
    public void init() {
        // 服务启动后立即从磁盘恢复历史统计，保证重启后榜单不会清零。
        loadFromDisk();
    }
    /**
     * 监听战斗开始事件并累计出场
     */
    @EventListener
    public void onBattleStarted(BattleStartedEvent event) {
        // 配置未开启或事件对象为空时直接退出，避免无意义统计。
        if (!properties.getAiAssist().isAvatarUsageStatsEnabled() || event == null) {
            return; // 条件不满足，本方法提前结束不写状态
        }
        // 通过 playerId + lineupId 解析本场战斗实际使用的角色 ID 列表。
        List<Integer> avatarIds = resolveAvatarIds(event.playerId(), event.lineupId());
        // 没有解析到阵容时，不记录这场战斗，避免空数据污染总数。
        if (avatarIds.isEmpty()) {
            return; // 条件不满足，本方法提前结束不写状态
        }
        // 按 battleId 暂存该场战斗的角色列表，供结束事件计算胜场使用。
        battleAvatars.put(event.battleId(), avatarIds);
        // 战斗总数 +1，对应一次有效的开战事件。
        totalBattles.incrementAndGet();
        // 遍历阵容中的每个 avatarId，分别累加个人出场次数与全局出场总数。
        for (Integer avatarId : avatarIds) {
            // 过滤空值和非法 ID，避免把 0 或负数写进统计文件。
            if (avatarId == null || avatarId <= 0) {
                continue; // 本条数据无效或不匹配，跳到下一项
            }
            // 如果该角色首次出现，则创建 AtomicLong 计数器，再进行原子自增。
            pickCounts.computeIfAbsent(avatarId, ignored -> new AtomicLong()).incrementAndGet();
            // 全局总出场数也同步 +1，用于计算 usageRate 分母。
            totalPicks.incrementAndGet();
        }
        // 记录脏状态并在达到阈值时尝试落盘。
        markDirtyAndMaybeFlush();
        // 打 debug 日志，方便排查某场 battleId 对应了哪些角色出场。
        log.debug("avatar usage pick battleId={} avatars={}", event.battleId(), avatarIds);
    }
    /**
     * 监听战斗结束事件并累计胜场
     */
    @EventListener
    public void onBattleEnded(BattleEndedEvent event) {
        // 配置未开启或事件对象为空时直接退出。
        if (!properties.getAiAssist().isAvatarUsageStatsEnabled() || event == null) {
            return; // 条件不满足，本方法提前结束不写状态
        }
        // 结束事件到来后，先把开战时暂存的角色列表取出并移除，避免内存泄漏。
        List<Integer> avatarIds = battleAvatars.remove(event.battleId());
        // 如果未找到对应 battleId 或列表为空，说明这场战斗没有可用的出场记录。
        if (avatarIds == null || avatarIds.isEmpty()) {
            return; // 条件不满足，本方法提前结束不写状态
        }
        // endStatus != 1 表示不是胜利结束，因此不记胜场，只保留已记录的出场次数。
        if (event.endStatus() != 1) {
            return; // 条件不满足，本方法提前结束不写状态
        }
        // 胜利时将本场战斗里所有有效角色的 winCount +1。
        for (Integer avatarId : avatarIds) {
            // 继续过滤空值和非法 ID，避免污染胜率计算。
            if (avatarId == null || avatarId <= 0) {
                continue; // 本条数据无效或不匹配，跳到下一项
            }
            // 如果角色此前没有胜场记录，则先创建，再累加。
            winCounts.computeIfAbsent(avatarId, ignored -> new AtomicLong()).incrementAndGet();
        }
        // 胜场更新后也要标记脏数据，按阈值触发落盘。
        markDirtyAndMaybeFlush();
    }
    /**
     * 记录事件
     */
    public void recordPicks(List<Integer> avatarIds) {
        // 外部若直接传空列表，则不进行任何统计操作。
        if (avatarIds == null || avatarIds.isEmpty()) {
            return; // 条件不满足，本方法提前结束不写状态
        }
        // 这里按一次“战斗/选择记录”计数，和战斗开始事件的总数口径保持一致。
        totalBattles.incrementAndGet();
        // 对传入列表中的每个 avatarId 执行同样的有效性校验和计数逻辑。
        for (Integer avatarId : avatarIds) {
            // 过滤 null、0、负数等非法角色 ID。
            if (avatarId == null || avatarId <= 0) {
                continue; // 本条数据无效或不匹配，跳到下一项
            }
            // 个人出场次数原子 +1。
            pickCounts.computeIfAbsent(avatarId, ignored -> new AtomicLong()).incrementAndGet();
            // 全局出场次数也同步 +1。
            totalPicks.incrementAndGet();
        }
        // 记录脏数据，可能在累计到 20 次变更时写盘。
        markDirtyAndMaybeFlush();
    }
    /**
     * 读取战斗总场次
     */
    public long getTotalBattles() {
        // 直接返回原子变量中的累计战斗总数。
        return totalBattles.get();
    }
    /**
     * 读取角色总出场次数
     */
    public long getTotalPicks() {
        // 直接返回全局累计出场总数。
        return totalPicks.get();
    }
    /**
     * 读取指定角色出场次数
     */
    public long getPickCount(int avatarId) {
        // 根据 avatarId 查询单个角色的出场计数；没有记录时返回 0。
        AtomicLong c = pickCounts.get(avatarId);
        return c == null ? 0L : c.get(); // 返回：c == null ? 0L : c.get()
    }
    /**
     * 返回出场次数 TopN 列表
     */
    public List<UsageEntry> topN(int limit) {
        // limit 至少为 1，避免传入 0 或负数导致返回空/非法结果。
        int n = Math.max(1, limit);
        // usageRate 的分母使用 totalPicks，且至少为 1，避免除零。
        long picksTotal = Math.max(1L, totalPicks.get());
        // 先收集所有角色的统计行，随后统一排序和截断。
        List<UsageEntry> all = new ArrayList<>();
        for (Map.Entry<Integer, AtomicLong> e : pickCounts.entrySet()) { // 遍历 (Map.Entry<Integer, AtomicLong> e : pickCounts.entrySet()) { 处理每一项
            // 当前角色的出场次数。
            long picks = e.getValue().get();
            // 出场次数为 0 的记录没有排序价值，直接跳过。
            if (picks <= 0) {
                continue; // 本条数据无效或不匹配，跳到下一项
            }
            // 从胜场计数表里取出该角色的胜利次数，缺省值为 0。
            long wins = winCounts.getOrDefault(e.getKey(), new AtomicLong()).get();
            // usageRate = 单角色出场次数 / 全服总出场次数。
            double usageRate = (double) picks / picksTotal;
            // winRate = 单角色胜场 / 单角色出场次数，若 picks 为 0 则返回 0。
            double winRate = picks == 0 ? 0.0 : (double) wins / picks;
            // 先创建不带 rank 的统计项，rank 之后在裁剪后统一补上。
            all.add(new UsageEntry(e.getKey(), picks, wins, usageRate, winRate));
        }
        // 排序规则：先按出场次数降序，再按 avatarId 升序，保证榜单稳定。
        all.sort(Comparator.comparingLong(UsageEntry::pickCount).reversed()
                .thenComparingInt(UsageEntry::avatarId)); // 续写 topN 的参数列表
        // 如果超过 limit，就只保留前 n 条。
        if (all.size() > n) {
            all = all.subList(0, n); // 赋值 all，供 topN 使用
        }
        // 重新生成带 rank 的结果，rank 从 1 开始。
        List<UsageEntry> ranked = new ArrayList<>(all.size());
        for (int i = 0; i < all.size(); i++) { // 遍历 (int i = 0; i < all.size(); i++) { 处理每一项
            UsageEntry u = all.get(i); // 赋值 u，供 topN 使用
            // 在 topN 中调用：ranked.add(new UsageEntry(u.avatarId(), u.pickCount(), u.winCount
            ranked.add(new UsageEntry(u.avatarId(), u.pickCount(), u.winCount(), u.usageRate(), u.winRate(), i + 1));
        }
        return List.copyOf(ranked); // 返回：List.copyOf(ranked)
    }
    /**
     * 返回 TopN 统计的 Map 列表
     */
    public List<Map<String, Object>> topNAsMaps(int limit) {
        // 供上下文构建器/接口层直接消费的 Map 结构，字段名固定为前端可识别的键。
        List<Map<String, Object>> rows = new ArrayList<>();
        for (UsageEntry e : topN(limit)) { // 遍历 (UsageEntry e : topN(limit)) { 处理每一项
            // 使用 LinkedHashMap 保留字段插入顺序，便于 JSON 输出一致。
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("avatarId", e.avatarId()); // 在 topNAsMaps 中调用：row.put("avatarId", e.avatarId());
            row.put("rank", e.rank()); // 在 topNAsMaps 中调用：row.put("rank", e.rank());
            row.put("pickCount", e.pickCount()); // 在 topNAsMaps 中调用：row.put("pickCount", e.pickCount());
            row.put("winCount", e.winCount()); // 在 topNAsMaps 中调用：row.put("winCount", e.winCount());
            // usageRate 和 winRate 均保留到 4 位小数，减少提示词/JSON 的噪声。
            row.put("usageRate", round4(e.usageRate()));
            row.put("winRate", round4(e.winRate())); // 在 topNAsMaps 中调用：row.put("winRate", round4(e.winRate()));
            rows.add(row); // 在 topNAsMaps 中调用：rows.add(row);
        }
        return List.copyOf(rows); // 返回：List.copyOf(rows)
    }
    /**
     * 生成出场统计摘要文本
     */
    public String summaryText(int limit) {
        // 生成一段可直接塞进提示词的中文总结文本。
        List<UsageEntry> top = topN(limit);
        // 没有任何统计数据时，返回明确的空榜提示。
        if (top.isEmpty()) {
            return "全服角色使用率榜暂无数据"; // 返回："全服角色使用率榜暂无数据"
        }
        // 固定前缀描述榜单含义，后续逐条拼接 rank、avatarId、使用率和胜率。
        StringBuilder sb = new StringBuilder("全服热门角色（按出场率）：");
        for (int i = 0; i < top.size(); i++) { // 遍历 (int i = 0; i < top.size(); i++) { 处理每一项
            UsageEntry e = top.get(i); // 赋值 e，供 summaryText 使用
            // 多条记录之间用中文逗号分隔，便于自然语言阅读。
            if (i > 0) {
                sb.append('，'); // 在 summaryText 中调用：sb.append('，');
            }
            sb.append('#').append(e.rank()) // 拼接名次与角色使用率摘要片段
                    .append(" avatarId=").append(e.avatarId()) // 续写 summaryText 的参数列表
                    .append(" 使用率=").append(percent(e.usageRate())) // 续写 summaryText 的参数列表
                    .append(" 胜率=").append(percent(e.winRate())); // 续写 summaryText 的参数列表
        }
        return sb.toString(); // 返回：sb.toString()
    }
    /**
     * 将统计数据落盘
     */
    public synchronized void flushToDisk() {
        // 功能开关关闭时不写盘，避免在关闭统计后仍然生成文件。
        if (!properties.getAiAssist().isAvatarUsageStatsEnabled()) {
            return; // 条件不满足，本方法提前结束不写状态
        }
        try { // 包裹可能失败的外部/IO/推理调用
            // 从配置读取统计文件相对路径，允许外部自定义存储位置。
            String relative = properties.getAiAssist().getAvatarUsageStatsResource();
            // 若配置为空，则回退到默认文件名 avatar_usage_stats.json。
            Path path = configFileService.resolve(relative == null || relative.isBlank()
                    ? "avatar_usage_stats.json" : relative); // 配置路径为空时回退默认文件名
            // 确保父目录存在，避免首次写盘时因目录不存在而失败。
            Files.createDirectories(path.getParent());
            // 生成当前内存快照，避免写盘过程中并发修改影响一致性。
            StatsFile file = toFileSnapshot();
            // 以 pretty printer 输出，方便人工排查统计文件内容。
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(path.toFile(), file);
            // 落盘成功后清空脏计数，重新开始计数下一轮变更。
            dirtyCounter.set(0);
            log.debug("avatar usage stats flushed path={} avatars={}", path, file.avatars().size()); // 在 flushToDisk 中调用：log.debug("avatar usage stats flushed path={} avatars={}", path, 
        } catch (Exception e) { // 捕获异常后降级：记日志并返回错误或空结果
            // 写盘失败不抛出，改为 warn 记录，避免影响主业务流程。
            log.warn("avatar usage stats flush failed: {}", e.toString());
        }
    }
    /**
     * 加载配置或数据
     */
    private void loadFromDisk() {
        try { // 包裹可能失败的外部/IO/推理调用
            // 读取配置里定义的统计文件名或默认文件名。
            String relative = properties.getAiAssist().getAvatarUsageStatsResource();
            Path path = configFileService.resolve(relative == null || relative.isBlank() // 赋值 path，供 loadFromDisk 使用
                    ? "avatar_usage_stats.json" : relative); // 读盘路径同样允许回退默认文件名
            // 文件不存在时直接返回，表示当前是首次启动或还未生成过统计文件。
            if (!Files.exists(path)) {
                return; // 条件不满足，本方法提前结束不写状态
            }
            // 按 StatsFile 结构读取 JSON，忽略未来新增字段。
            StatsFile file = objectMapper.readValue(Files.readString(path, StandardCharsets.UTF_8), StatsFile.class);
            if (file == null) { // 若满足 file == null 则走本分支
                return; // 条件不满足，本方法提前结束不写状态
            }
            // 从文件中恢复 totalBattles，负数会被截断为 0。
            totalBattles.set(Math.max(0L, file.totalBattles()));
            // 重新计算总出场数，确保文件内各 avatar 行与聚合字段一致。
            long picks = 0L;
            if (file.avatars() != null) { // 若满足 file.avatars() != null 则走本分支
                for (StatsAvatarRow row : file.avatars()) { // 遍历 (StatsAvatarRow row : file.avatars()) { 处理每一项
                    // row 为空或 avatarId 非法时跳过，避免恢复脏数据。
                    if (row == null || row.avatarId() <= 0) {
                        continue; // 本条数据无效或不匹配，跳到下一项
                    }
                    // pickCount 和 winCount 都按非负值恢复到内存计数器。
                    pickCounts.put(row.avatarId(), new AtomicLong(Math.max(0L, row.pickCount())));
                    winCounts.put(row.avatarId(), new AtomicLong(Math.max(0L, row.winCount()))); // 在 loadFromDisk 中调用：winCounts.put(row.avatarId(), new AtomicLong(Math.max(0L, row.win
                    picks += Math.max(0L, row.pickCount()); // 在 loadFromDisk 中调用：picks += Math.max(0L, row.pickCount());
                }
            }
            // 总出场数按所有角色 pickCount 求和恢复。
            totalPicks.set(picks);
            log.info("avatar usage stats loaded battles={} picks={} avatars={}",
                    totalBattles.get(), totalPicks.get(), pickCounts.size()); // 在 loadFromDisk 中调用：totalBattles.get(), totalPicks.get(), pickCounts.size());
        } catch (Exception e) { // 捕获异常后降级：记日志并返回错误或空结果
            // 读取失败时不中断启动，只记录跳过信息。
            log.warn("avatar usage stats load skipped: {}", e.toString());
        }
    }
    /**
     * 转换为可序列化快照
     */
    private StatsFile toFileSnapshot() {
        // 将当前内存中的统计表转换成可持久化文件结构。
        List<StatsAvatarRow> rows = new ArrayList<>();
        for (Map.Entry<Integer, AtomicLong> e : pickCounts.entrySet()) { // 遍历 (Map.Entry<Integer, AtomicLong> e : pickCounts.entrySet()) { 处理每一项
            // 当前角色的出场次数。
            long picks = e.getValue().get();
            // 对应胜场次数若不存在则按 0 处理。
            long wins = winCounts.getOrDefault(e.getKey(), new AtomicLong()).get();
            rows.add(new StatsAvatarRow(e.getKey(), picks, wins)); // 在 toFileSnapshot 中调用：rows.add(new StatsAvatarRow(e.getKey(), picks, wins));
        }
        // 文件内角色行按 pickCount 降序存储，便于人工查看时优先看到热门角色。
        rows.sort(Comparator.comparingLong(StatsAvatarRow::pickCount).reversed());
        // version=1 作为文件结构版本号，未来升级可据此做兼容处理。
        return new StatsFile(1, totalBattles.get(), rows);
    }
    /**
     * 解析战斗阵容中的角色 ID
     */
    private List<Integer> resolveAvatarIds(int playerId, int lineupId) {
        // 先定位玩家指定 lineupId 的阵容实体，再解析出 avatarsJson 里的角色 ID 列表。
        LineupEntity lineup = findLineup(playerId, lineupId);
        if (lineup == null) { // 若满足 lineup == null 则走本分支
            return List.of(); // 无命中结果，向上层返回空以便继续降级
        }
        return LineupAvatarIdParser.parse(lineup.getAvatarsJson()); // 返回：LineupAvatarIdParser.parse(lineup.getAvatarsJson())
    }
    /**
     * 查找玩家当前阵容
     */
    private LineupEntity findLineup(int playerId, int lineupId) {
        // 优先从当前在线 session 中读取 PlayerData，减少数据库读取次数。
        GameSession session = sessionManager.getOrNull(playerId);
        if (session != null && session.getPlayerData() != null) { // 若满足 session != null && session.getPlayerData() != null 则走本分支
            PlayerData data = session.getPlayerData(); // 赋值 data，供 findLineup 使用
            if (data.getLineups() != null) { // 若满足 data.getLineups() != null 则走本分支
                for (LineupEntity lineup : data.getLineups()) { // 遍历 (LineupEntity lineup : data.getLineups()) { 处理每一项
                    // 只返回与入参 lineupId 完全匹配的阵容。
                    if (lineup != null && lineup.getId() == lineupId) {
                        return lineup; // 返回：lineup
                    }
                }
            }
        }
        try { // 包裹可能失败的外部/IO/推理调用
            // session 没命中时，回退到仓库层读取持久化阵容。
            List<LineupEntity> lineups = playerDataRepository.loadLineups(playerId);
            if (lineups == null) { // 若满足 lineups == null 则走本分支
                return null; // 返回：null
            }
            for (LineupEntity lineup : lineups) { // 遍历 (LineupEntity lineup : lineups) { 处理每一项
                if (lineup != null && lineup.getId() == lineupId) { // 若满足 lineup != null && lineup.getId() == lineupId 则走本分支
                    return lineup; // 返回：lineup
                }
            }
        } catch (Exception e) { // 捕获异常后降级：记日志并返回错误或空结果
            // 数据库读取失败只记录 debug，避免战斗统计流程被异常打断。
            log.debug("loadLineups for usage stats failed playerId={}: {}", playerId, e.toString());
        }
        return null; // 返回：null
    }
    /**
     * 标记脏数据并按阈值落盘
     */
    private void markDirtyAndMaybeFlush() {
        // 每次增量更新后让脏计数 +1，达到阈值就触发写盘。
        long dirty = dirtyCounter.incrementAndGet();
        // 阈值 20 表示累计 20 次变更才落盘一次，平衡数据安全与 IO 开销。
        if (dirty >= 20) {
            flushToDisk(); // 在 markDirtyAndMaybeFlush 中调用：flushToDisk();
        }
    }
    /**
     * 保留四位小数
     */
    private static double round4(double v) {
        // 按 10000 倍四舍五入后再缩回，得到 4 位小数精度。
        return Math.round(v * 10000.0) / 10000.0;
    }
    /**
     * 计算百分比
     */
    private static String percent(double rate) {
        // 将 0~1 的比例转换为“xx.x%”字符串，保留 1 位小数。
        return Math.round(rate * 1000.0) / 10.0 + "%";
    }
    /**
     * 角色出场统计条目
     */
    public record UsageEntry(int avatarId, long pickCount, long winCount, double usageRate, double winRate, int rank) {
        /**
         * 无 rank 的中间结果构造，最终榜单会在 topN 中补写 rank
         */
        public UsageEntry(int avatarId, long pickCount, long winCount, double usageRate, double winRate) {
            this(avatarId, pickCount, winCount, usageRate, winRate, 0);
        }
    }
    /**
     * 统计落盘文件结构
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StatsFile(int version, long totalBattles, List<StatsAvatarRow> avatars) {
        /**
         * 反序列化后规范化 avatars 列表
         */
        public StatsFile {
            avatars = avatars == null ? List.of() : List.copyOf(avatars);
        }
    }
    /**
     * 统计文件中的角色行
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StatsAvatarRow(int avatarId, long pickCount, long winCount) {
    }
}
