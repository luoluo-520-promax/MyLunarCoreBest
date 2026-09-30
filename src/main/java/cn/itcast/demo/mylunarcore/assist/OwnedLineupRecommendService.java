package cn.itcast.demo.mylunarcore.assist;

import cn.itcast.demo.mylunarcore.character.AttributeCalculator;
import cn.itcast.demo.mylunarcore.common.ActivityQueryService;
import cn.itcast.demo.mylunarcore.model.ActivityConfig;
import cn.itcast.demo.mylunarcore.model.AvatarEntity;
import cn.itcast.demo.mylunarcore.model.PlayerData;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 基于已拥有角色的阵容推荐服务。
 * <p>
 * 结合角色面板、敌人克制标签与全服热度，对玩家现有角色打分并贪心组队，
 * 返回推荐 avatarId 列表、理由说明，以及可选的活动跳转类 {@link CoachHint}。
 */
@Service
public class OwnedLineupRecommendService {
    /**
     * 阵容推荐结果
     */
    public record RecommendResult(
            int retcode, // 续写 RecommendResult 的参数列表
            List<Integer> avatarIds,
            String reason,
            List<CoachHint> relatedHints
    ) { // 记录/配置对象的紧凑构造体定义开始
    }
    /**
     * sessionManager：在线 GameSession 与 PlayerData
     */
    private final GameSessionManager sessionManager;
    /**
     * usageStatsService：全服角色热度统计
     */
    private final AvatarUsageStatsService usageStatsService;
    /**
     * attributeCalculator：角色面板属性换算
     */
    private final AttributeCalculator attributeCalculator;
    /**
     * activityQueryService：当前开放活动列表
     */
    private final ActivityQueryService activityQueryService;
    /**
     * featureContentRepository：AssistFeatureContent 热更内容
     */
    private final AssistFeatureContentRepository featureContentRepository;
    /**
     * 构造并注入依赖
     */
    public OwnedLineupRecommendService(GameSessionManager sessionManager,
                                       AvatarUsageStatsService usageStatsService,
                                       AttributeCalculator attributeCalculator,
                                       ActivityQueryService activityQueryService,
                                       AssistFeatureContentRepository featureContentRepository) {
        // 需要会话管理器读取玩家当前在线角色快照。
        this.sessionManager = sessionManager;
        // 使用率统计用于把全服热门 meta 纳入阵容评分。
        this.usageStatsService = usageStatsService;
        // 属性计算器把角色实体转成攻防血等评分基础。
        this.attributeCalculator = attributeCalculator;
        // 活动查询服务用于补充当前活动相关的推荐提示。
        this.activityQueryService = activityQueryService;
        // 特性内容仓库提供敌人标签 -> 克制标签的静态映射。
        this.featureContentRepository = featureContentRepository;
    }
    /**
     * 根据已拥有角色推荐阵容
     */
    public RecommendResult recommend(long uid, int maxSlots, String scene) {
        // 默认不传敌人标签时，按空列表处理，保留原有业务路径。
        return recommend(uid, maxSlots, scene, List.of());
    }
    /**
     * 基于已拥有角色、敌人标签和全服热度生成阵容建议。 <ol> <li>读取玩家当前在线会话中的角色快照。</li> <li>用属性分、克制分和全服使用率分组成候选评分。</li> <li>贪心挑选互补性更好的阵容，并返回活动跳转提示。</li> </ol>
     */
    public RecommendResult recommend(long uid, int maxSlots, String scene, List<String> enemyTags) {
        // 非法 uid 直接返回 retcode=1，表示请求主体缺失。
        if (uid <= 0) {
            return new RecommendResult(1, List.of(), "", List.of()); // 无命中结果，向上层返回空以便继续降级
        }
        // slots 至少 1，最多 8，防止客户端传入异常大值拖慢推荐。
        int slots = maxSlots <= 0 ? 4 : Math.min(8, maxSlots);
        // 先从 session manager 获取在线会话，优先使用最新内存快照。
        GameSession session = sessionManager.getOrNull(uid);
        // 没有会话时，无法构造基于已拥有角色的推荐结果。
        PlayerData data = session == null ? null : session.getPlayerData();
        // 角色列表为空时直接返回空推荐，并保留可解释的 reason。
        if (data == null || data.getAvatars() == null || data.getAvatars().isEmpty()) {
            return new RecommendResult(0, List.of(), "当前没有可推荐的已拥有角色。", List.of()); // 无命中结果，向上层返回空以便继续降级
        }

        // 敌人标签先归一成小写集合，便于与克制标签做交集判断。
        Set<String> enemy = normalizeTags(enemyTags);
        // 读取静态特性内容中的克制标签映射，key 为 avatarId 字符串。
        Map<String, List<String>> counterTags = featureContentRepository.current().avatarCounterTags();

        // owned 记录玩家当前拥有的角色 id，供 meta 和提示生成使用。
        Set<Integer> owned = new HashSet<>();
        // candidates 保存每个角色的基础分、克制分和 meta 分。
        List<ScoredAvatar> candidates = new ArrayList<>();
        // 遍历所有角色实体，构造可推荐候选列表。
        for (AvatarEntity avatar : data.getAvatars()) {
            // 空角色或非法角色 id 不进入候选。
            if (avatar == null || avatar.getAvatarId() <= 0) {
                continue; // 本条数据无效或不匹配，跳到下一项
            }
            // 记录已拥有角色 id，供后续热门榜单叠加。
            owned.add(avatar.getAvatarId());
            // 先计算角色基础属性，用于形成 power 分。
            AttributeCalculator.AvatarAttributes attrs = attributeCalculator.calculate(avatar);
            // power 用攻、血、守做一个粗略强度估计，作为基础战力分。
            int power = attrs.atk() + attrs.hp() / 10 + attrs.def();
            // counter 分根据敌人标签和角色克制标签计算，越匹配越高。
            double counter = counterBonus(avatar.getAvatarId(), enemy, counterTags);
            // 把候选角色打包成带分值的中间结构。
            candidates.add(new ScoredAvatar(avatar.getAvatarId(), avatar.getLevel(), power, counter));
        }
        // 如果角色都被过滤掉了，直接返回空结果。
        if (candidates.isEmpty()) {
            return new RecommendResult(0, List.of(), "当前没有可推荐的已拥有角色。", List.of()); // 无命中结果，向上层返回空以便继续降级
        }

        // 把全服使用率和胜率叠加到已拥有角色的 metaScore 上。
        for (AvatarUsageStatsService.UsageEntry u : usageStatsService.topN(50)) {
            // 只给玩家已拥有的角色叠加 meta 分，避免推荐未拥有角色。
            if (!owned.contains(u.avatarId())) {
                continue; // 本条数据无效或不匹配，跳到下一项
            }
            // 找到同 avatarId 的候选项并追加 usage/win 分。
            for (int i = 0; i < candidates.size(); i++) {
                // 取出当前候选，避免在原对象上反复累加导致逻辑难追踪。
                ScoredAvatar c = candidates.get(i);
                // 只对同一角色做 score 提升。
                if (c.avatarId == u.avatarId()) {
                    // usageRate * 100 和 winRate * 20 作为 meta 加权，强化版本热门角色的优先级。
                    candidates.set(i, new ScoredAvatar(c.avatarId, c.level, c.power,
                            c.metaScore + u.usageRate() * 100.0 + u.winRate() * 20.0)); // 在 recommend 中调用：c.metaScore + u.usageRate() * 100.0 + u.winRate() * 20.0));
                }
            }
        }

        // 贪心挑选互补性更好的阵容，最大数量由 slots 控制。
        List<Integer> picked = greedyPick(candidates, slots);
        // reason 说明推荐依据，包含场景、敌人标签和已拥有角色约束。
        String reason = buildReason(picked, scene, enemy);
        // 活动提示帮助玩家跳到当前活动，补充阵容养成资源路径。
        List<CoachHint> hints = buildActivityHints(owned);
        // 返回成功结果，retcode=0。
        return new RecommendResult(0, picked, reason, hints);
    }
    /**
     * 计算克制加成
     */
    private static double counterBonus(int avatarId, Set<String> enemy, Map<String, List<String>> counterTags) {
        // 没有敌人标签或克制表时，克制分直接为 0。
        if (enemy.isEmpty() || counterTags == null) {
            return 0; // 返回：0
        }
        // 根据 avatarId 找到对应的克制标签列表。
        List<String> tags = counterTags.get(String.valueOf(avatarId));
        // 没有克制标签则不加分。
        if (tags == null || tags.isEmpty()) {
            return 0; // 返回：0
        }
        // bonus 累积每个命中的敌人标签加成。
        double bonus = 0;
        // 遍历克制标签列表，只要命中敌人标签就加权。
        for (String t : tags) {
            // 标签非空且能在 enemy 集合中命中时，追加克制加成。
            if (t != null && enemy.contains(t.toLowerCase(Locale.ROOT))) {
                bonus += 35.0; // 累加评分或计数：bonus += 35.0;
            }
        }
        // 返回克制分，供总评分使用。
        return bonus;
    }
    /**
     * 规范化标签集合
     */
    private static Set<String> normalizeTags(List<String> enemyTags) {
        // out 保存归一化后的标签集合。
        Set<String> out = new HashSet<>();
        // 敌人标签为空时直接返回空集合。
        if (enemyTags == null) {
            return out; // 返回：out
        }
        // 遍历所有输入标签，去空白并转小写。
        for (String t : enemyTags) {
            // 空标签直接忽略。
            if (t == null || t.isBlank()) {
                continue; // 本条数据无效或不匹配，跳到下一项
            }
            // 统一转小写，避免大小写差异影响克制匹配。
            out.add(t.trim().toLowerCase(Locale.ROOT));
        }
        // 返回标准化集合。
        return out;
    }
    /**
     * 贪心挑选阵容角色
     */
    private static List<Integer> greedyPick(List<ScoredAvatar> candidates, int slots) {
        // pool 是可被挑选的候选池，先按 metaScore 排序。
        List<ScoredAvatar> pool = new ArrayList<>(candidates);
        // 优先选 meta 分高的，再比较 power 和 level，确保主力角色先入选。
        pool.sort(Comparator.comparingDouble((ScoredAvatar a) -> a.metaScore).reversed()
                .thenComparingInt(a -> -a.power) // 续写 greedyPick 的参数列表
                .thenComparingInt(a -> -a.level)); // 续写 greedyPick 的参数列表
        // selected 保存当前阵容。
        List<ScoredAvatar> selected = new ArrayList<>();
        // 如果候选池非空，先放入一个最高分角色作为起点。
        if (!pool.isEmpty()) {
            selected.add(pool.remove(0)); // 在 greedyPick 中调用：selected.add(pool.remove(0));
        }
        // 继续选择直到达到 slots 或候选池耗尽。
        while (selected.size() < slots && !pool.isEmpty()) {
            // best 保存当前轮次最合适的候选。
            ScoredAvatar best = null;
            // bestScore 记录当前轮次的最高综合分。
            double bestScore = Double.NEGATIVE_INFINITY;
            // 遍历剩余候选，计算与已选阵容的互补性。
            for (ScoredAvatar cand : pool) {
                // compat 累加与当前阵容每一位的 pairScore。
                double compat = 0;
                // 逐个与已选角色计算 pairScore，衡量阵容协同。
                for (ScoredAvatar s : selected) {
                    compat += pairScore(s, cand); // 在 greedyPick 中调用：compat += pairScore(s, cand);
                }
                // total = 自身 meta 分 + 平均协同分，作为最终挑选依据。
                double total = cand.metaScore + compat / selected.size();
                // 当前候选更优时，更新最优解。
                if (total > bestScore) {
                    bestScore = total; // 赋值 bestScore，供 greedyPick 使用
                    best = cand; // 赋值 best，供 greedyPick 使用
                }
            }
            // 没有找到更优候选时退出循环。
            if (best == null) {
                break; // 已达截断/命中条件，结束循环
            }
            // 将最优候选加入已选阵容。
            selected.add(best);
            // 同时从候选池删除，避免重复选择同一角色。
            pool.remove(best);
        }
        // 只返回角色 id 列表，不暴露内部评分。
        List<Integer> ids = new ArrayList<>();
        // 依照最终挑选顺序输出 avatarId。
        for (ScoredAvatar s : selected) {
            ids.add(s.avatarId); // 在 greedyPick 中调用：ids.add(s.avatarId);
        }
        // 返回不可变列表，避免外部修改推荐结果。
        return List.copyOf(ids);
    }
    /**
     * 计算两名已拥有角色的协同分，供贪心组队使用
     */
    static double pairScore(ScoredAvatar a, ScoredAvatar b) {
        // 同一个角色不能同时进入阵容，直接给负无穷避免被选中。
        if (a.avatarId == b.avatarId) {
            return Double.NEGATIVE_INFINITY; // 返回：Double.NEGATIVE_INFINITY
        }
        // levelDiff 衡量培养层次差异，差距过大时协同分下降。
        int levelDiff = Math.abs(a.level - b.level);
        // powerDiff 衡量战力差异，过于悬殊的搭配也会减分。
        int powerDiff = Math.abs(a.power - b.power);
        // 协同分从 50 起步，等级差和战力差会逐步扣分。
        return 50.0 - levelDiff * 2.0 - Math.min(30.0, powerDiff / 80.0);
    }
    /**
     * 构建结果
     */
    private String buildReason(List<Integer> picked, String scene, Set<String> enemy) {
        // scene 为空时统一归一为 general，便于文案输出。
        String s = scene == null || scene.isBlank() ? "general" : scene.trim().toLowerCase(Locale.ROOT);
        // 没有任何推荐结果时，返回空推荐说明。
        if (picked.isEmpty()) {
            return "暂无推荐。"; // 返回："暂无推荐。"
        }
        // base 先说明推荐基于已拥有角色和全服出场率。
        String base = "基于你已拥有角色与全服出场率，推荐阵容 " + picked + "（场景=" + s + "）";
        // 若有敌人标签，则说明已按克制关系加权。
        if (!enemy.isEmpty()) {
            base += "。已按敌人标签 " + enemy + " 加权克制"; // 累加评分或计数：base += "。已按敌人标签 " + enemy + " 加权克制";
        }
        // 末尾强调不会推荐未拥有角色。
        return base + "。未拥有角色不会出现在推荐中。";
    }
    /**
     * 构建结果
     */
    private List<CoachHint> buildActivityHints(Set<Integer> owned) {
        // hints 保存从当前活动生成的教练提示。
        List<CoachHint> hints = new ArrayList<>();
        // 遍历当前开放活动，补充资源获取提示。
        for (ActivityConfig a : activityQueryService.listCurrentlyActiveConfigs()) {
            // 空活动直接跳过。
            if (a == null) {
                continue; // 本条数据无效或不匹配，跳到下一项
            }
            // 组装活动获取途径提示，source/category/action 都固定表达来源。
            hints.add(new CoachHint(
                    "activity_source",
                    "activity",
                    20, // 续写 buildActivityHints 的参数列表
                    "活动获取途径",
                    "可通过活动“" + nullToEmpty(a.getName()) + "”获取对应资源，详情见活动界面官方说明。",
                    "OPEN_ACTIVITY",
                    a.getActivityId() // 续写构造参数：取出关联业务字段
            )); // 结束多行构造或方法调用
            // 只保留前 2 条活动提示，避免提示过多。
            if (hints.size() >= 2) {
                break; // 已达截断/命中条件，结束循环
            }
        }
        // 如果玩家没有任何已拥有角色，则活动提示也不返回。
        if (owned.isEmpty()) {
            return List.of(); // 无命中结果，向上层返回空以便继续降级
        }
        // 返回冻结后的活动提示列表。
        return List.copyOf(hints);
    }

    record ScoredAvatar(int avatarId, int level, int power, double metaScore) { // 内部数据结构：承载统计或评分中间结果
    }
    /**
     * 将 null 转为空字符串
     */
    private static String nullToEmpty(String s) {
        // 工具方法把 null 转为空串，防止活动名称拼接出现 NPE。
        return s == null ? "" : s;
    }
}
