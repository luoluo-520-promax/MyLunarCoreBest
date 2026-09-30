package cn.itcast.demo.mylunarcore.assist;

import cn.itcast.demo.mylunarcore.character.AttributeCalculator;
import cn.itcast.demo.mylunarcore.model.ActivityConfig;
import cn.itcast.demo.mylunarcore.model.AvatarEntity;
import cn.itcast.demo.mylunarcore.model.AvatarTalentEntity;
import cn.itcast.demo.mylunarcore.model.GameItemEntity;
import cn.itcast.demo.mylunarcore.model.PlayerData;
import cn.itcast.demo.mylunarcore.model.QuestProgressEntity;
import cn.itcast.demo.mylunarcore.quest.QuestConfigRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 教练提示规则引擎。
 * <p>
 * 读取热更 {@link CoachTipsConfig} 快照，按 category（quest/activity/growth/avatar/talent/meta/fallback）
 * 对照玩家数据、任务进度、开放活动与天赋进行匹配，生成并按 priority 排序的 {@link CoachHint} 列表。
 */
@Component
public class CoachRuleEngine {
    /**
     * quest_progress.status：进行中。
     */
    public static final int QUEST_STATUS_IN_PROGRESS = 1;
    /**
     * quest_progress.status：目标已达成，等待提交领奖。
     */
    public static final int QUEST_STATUS_READY_SUBMIT = 2;
    /**
     * tipsSource：本类持有的 CoachTipsSource 状态/依赖
     */
    private final CoachTipsSource tipsSource;
    /**
     * questConfigRepository：任务静态配置（标题等）
     */
    private final QuestConfigRepository questConfigRepository;
    /**
     * attributeCalculator：角色面板属性换算
     */
    private final AttributeCalculator attributeCalculator;
    /**
     * avatarUsageStatsService：全服角色出场/胜率统计
     */
    private final AvatarUsageStatsService avatarUsageStatsService;
    /**
     * 构造并注入依赖
     */
    public CoachRuleEngine(CoachTipsSource tipsSource,
                           QuestConfigRepository questConfigRepository,
                           AttributeCalculator attributeCalculator,
                           AvatarUsageStatsService avatarUsageStatsService) {
        // 规则引擎依赖 tipsSource，读取热更后的规则配置快照。
        this.tipsSource = tipsSource;
        // 依赖任务配置仓库，用于把 questId 还原成任务标题。
        this.questConfigRepository = questConfigRepository;
        // 依赖属性计算器，用于把角色等级和培养信息换算成数值面板。
        this.attributeCalculator = attributeCalculator;
        // 依赖使用率统计，用于生成 meta 相关的热门角色建议。
        this.avatarUsageStatsService = avatarUsageStatsService;
    }
    /**
     * 评估并生成教练提示列表
     */
    public List<CoachHint> evaluate(PlayerData playerData,
                                    List<QuestProgressEntity> questProgress,
                                    List<ActivityConfig> activeActivities) {
        // 默认不带天赋数据时，直接沿用空列表，避免下层判空分支。
        return evaluate(playerData, questProgress, activeActivities, List.of());
    }
    /**
     * 按规则配置批量生成教练提示。 <ol> <li>读取热更 tip 配置快照。</li> <li>逐条规则根据 category 做专门匹配。</li> <li>收集所有命中的 hint 后统一按 priority 降序排序。</li> </ol>
     */
    public List<CoachHint> evaluate(PlayerData playerData,
                                    List<QuestProgressEntity> questProgress,
                                    List<ActivityConfig> activeActivities,
                                    List<AvatarTalentEntity> talents) {
        // 从热更源读取当前 tip 配置，保证规则判断使用最新快照。
        CoachTipsConfig tipsConfig = tipsSource.current();
        // matched 保存所有命中的提示，最后统一排序。
        List<CoachHint> matched = new ArrayList<>();
        // talents 可能为空，统一折叠为空列表，避免下游多层判空。
        List<AvatarTalentEntity> safeTalents = talents == null ? List.of() : talents;
        // 遍历每条 tip 定义，由各 category 专门决定命中逻辑。
        for (CoachTipsConfig.TipDef tip : tipsConfig.tips()) {
            // 空 tip、未启用 tip 或缺少 id 的规则直接忽略。
            if (tip == null || !tip.isEnabled() || tip.id() == null || tip.id().isBlank()) {
                continue; // 本条数据无效或不匹配，跳到下一项
            }
            // 把当前 tip 与玩家数据、任务、活动和天赋做具体匹配。
            CoachHint hint = matchTip(tip, playerData, questProgress, activeActivities, safeTalents);
            // 只有命中的规则才进入结果集。
            if (hint != null) {
                matched.add(hint); // 在 evaluate 中调用：matched.add(hint);
            }
        }
        // 以 priority 降序排列，保证最重要的建议排在最前面。
        matched.sort(Comparator.comparingInt((CoachHint h) -> h == null ? Integer.MIN_VALUE : h.priority()).reversed());
        // 返回排序后的规则命中结果。
        return matched;
    }
    /**
     * 匹配单条教练提示规则
     */
    private CoachHint matchTip(CoachTipsConfig.TipDef tip,
                               PlayerData playerData,
                               List<QuestProgressEntity> questProgress,
                               List<ActivityConfig> activeActivities,
                               List<AvatarTalentEntity> talents) {
        // category 决定该 tip 属于哪条业务分支。
        String category = tip.category() == null ? "" : tip.category().trim().toLowerCase();
        // 根据 category 选择对应匹配逻辑。
        return switch (category) {
            case "quest" -> matchQuest(tip, questProgress); // 匹配分支 case "quest" -> matchQuest(tip, questProgress); 的场景预算/来源处理
            case "activity" -> matchActivity(tip, activeActivities); // 匹配分支 case "activity" -> matchActivity(tip, activeActivities); 的场景预算/来源处理
            case "growth" -> matchGrowth(tip, playerData); // 匹配分支 case "growth" -> matchGrowth(tip, playerData); 的场景预算/来源处理
            case "avatar" -> matchAvatar(tip, playerData); // 匹配分支 case "avatar" -> matchAvatar(tip, playerData); 的场景预算/来源处理
            case "talent" -> matchTalent(tip, talents); // 匹配分支 case "talent" -> matchTalent(tip, talents); 的场景预算/来源处理
            case "meta" -> matchMeta(tip, playerData); // 匹配分支 case "meta" -> matchMeta(tip, playerData); 的场景预算/来源处理
            case "fallback" -> buildHint(tip, render(tip.template()), 0); // 匹配分支 case "fallback" -> buildHint(tip, render(tip.template()), 0); 的场景预算/来源处理
            default -> null; // 匹配分支 default -> null; 的场景预算/来源处理
        };
    }
    /**
     * 匹配任务类教练提示
     */
    private CoachHint matchQuest(CoachTipsConfig.TipDef tip, List<QuestProgressEntity> questProgress) {
        // 没有任务进度时，quest 类规则不可能命中。
        if (questProgress == null || questProgress.isEmpty()) {
            return null; // 返回：null
        }
        // quest_claim 代表“可提交领奖”分支，其余 quest 代表一般任务推进。
        boolean wantClaim = "quest_claim".equals(tip.id());
        // best 保存当前最值得提示的任务进度记录。
        QuestProgressEntity best = null;
        // 遍历任务进度，找到最合适的目标任务。
        for (QuestProgressEntity q : questProgress) {
            // 空记录直接跳过，避免污染选择结果。
            if (q == null) {
                continue; // 本条数据无效或不匹配，跳到下一项
            }
            // 读取任务状态用于判断当前是否处于进行中或可提交状态。
            int status = q.getStatus();
            // 可提交分支只接受 ready submit。
            if (wantClaim) {
                if (status != QUEST_STATUS_READY_SUBMIT) { // 若满足 status != QUEST_STATUS_READY_SUBMIT 则走本分支
                    continue; // 本条数据无效或不匹配，跳到下一项
                }
            } else if (status != QUEST_STATUS_IN_PROGRESS && status != QUEST_STATUS_READY_SUBMIT) { // 条件不成立时的替代分支
                continue; // 本条数据无效或不匹配，跳到下一项
            }
            // 选 questId 更小的作为稳定优先项，避免结果抖动。
            if (best == null || q.getQuestId() < best.getQuestId()) {
                best = q; // 赋值 best，供 matchQuest 使用
            }
        }
        // 没有找到合适任务时，返回空。
        if (best == null) {
            return null; // 返回：null
        }
        // claim 规则要求最终状态必须仍然是 ready submit。
        if (wantClaim && best.getStatus() != QUEST_STATUS_READY_SUBMIT) {
            return null; // 返回：null
        }
        // next 规则不希望在已可提交任务上重复给“继续推进”提示。
        if (!wantClaim && "quest_next".equals(tip.id()) && best.getStatus() == QUEST_STATUS_READY_SUBMIT) {
            return null; // 返回：null
        }
        // 解析任务标题，用于输出更可读的提示文本。
        String title = resolveQuestTitle(best.getQuestId());
        // 用模板渲染 questTitle 和 questId 占位符。
        String message = render(tip.template(), Map.of("questTitle", title, "questId", String.valueOf(best.getQuestId())));
        // 构造最终教练提示，refId 绑定到 questId。
        return buildHint(tip, message, best.getQuestId());
    }
    /**
     * 匹配活动类教练提示
     */
    private CoachHint matchActivity(CoachTipsConfig.TipDef tip, List<ActivityConfig> activeActivities) {
        // 没有活动时，activity 类规则无法命中。
        if (activeActivities == null || activeActivities.isEmpty()) {
            return null; // 返回：null
        }
        // endWithinHours 默认 72 小时，表示临期活动提示窗口。
        int withinHours = tip.endingWithinHours() == null ? 72 : Math.max(1, tip.endingWithinHours());
        // 当前时间戳用于比较活动结束时刻。
        long nowSec = Instant.now().getEpochSecond();
        // 只保留截止时间窗口内的活动。
        long deadline = nowSec + withinHours * 3600L;
        // soonest 保存最接近结束的活动。
        ActivityConfig soonest = null;
        // 遍历所有开放活动，选择最早结束且仍在窗口内的一个。
        for (ActivityConfig activity : activeActivities) {
            // 空活动记录直接跳过。
            if (activity == null) {
                continue; // 本条数据无效或不匹配，跳到下一项
            }
            // 活动结束时间用于筛选临期范围。
            long end = activity.getEndTime();
            // 结束时间早于当前或晚于窗口上限的活动都不提示。
            if (end < nowSec || end > deadline) {
                continue; // 本条数据无效或不匹配，跳到下一项
            }
            // 在窗口内选择最早结束的活动，便于玩家优先处理。
            if (soonest == null || end < soonest.getEndTime()) {
                soonest = activity; // 赋值 soonest，供 matchActivity 使用
            }
        }
        // 没有临期活动时不返回提示。
        if (soonest == null) {
            return null; // 返回：null
        }
        // 计算剩余小时数，向上取整到至少 1 小时。
        long hoursLeft = Math.max(1, (soonest.getEndTime() - nowSec + 3599) / 3600);
        // 活动名称为空时使用活动编号做兜底展示。
        String name = soonest.getName() == null || soonest.getName().isBlank()
                ? ("活动#" + soonest.getActivityId()) // 活动名为空时用活动编号兜底
                : soonest.getName(); // 在 matchActivity 中调用：: soonest.getName();
        // 渲染活动名、活动 id 和剩余小时。
        String message = render(tip.template(), Map.of(
                "activityName", name,
                "activityId", String.valueOf(soonest.getActivityId()),
                "hoursLeft", String.valueOf(hoursLeft) // 模板变量：剩余小时数
        )); // 结束多行构造或方法调用
        // 返回活动提醒提示，refId 绑定活动 id。
        return buildHint(tip, message, soonest.getActivityId());
    }
    /**
     * 匹配养成类教练提示
     */
    private CoachHint matchGrowth(CoachTipsConfig.TipDef tip, PlayerData playerData) {
        // 玩家数据或物品列表为空时，成长类规则不命中。
        if (playerData == null || playerData.getItems() == null) {
            return null; // 返回：null
        }
        // 读取规则要求的物品 id 和数量阈值。
        int requiredItemId = tip.requiredItemId() == null ? 0 : tip.requiredItemId();
        int requiredCount = tip.requiredItemCount() == null ? 1 : Math.max(1, tip.requiredItemCount()); // 赋值 requiredCount，供 matchGrowth 使用
        // 物品 id 必须为正数，否则规则无效。
        if (requiredItemId <= 0) {
            return null; // 返回：null
        }
        // owned 汇总玩家持有该物品的总数量。
        long owned = 0L;
        // 遍历背包物品，统计未丢弃且 itemId 匹配的数量。
        for (GameItemEntity item : playerData.getItems()) {
            // 只有有效物品并且 itemId 相同才计入拥有数量。
            if (item != null && !item.isDiscarded() && item.getItemId() == requiredItemId) {
                owned += item.getCount(); // 在 matchGrowth 中调用：owned += item.getCount();
            }
        }
        // 数量不足时不提示，避免误导玩家。
        if (owned < requiredCount) {
            return null; // 返回：null
        }
        // 渲染物品 id、需求数量和当前拥有数量。
        String message = render(tip.template(), Map.of(
                "itemId", String.valueOf(requiredItemId),
                "requiredCount", String.valueOf(requiredCount),
                "ownedCount", String.valueOf(owned) // 模板变量：当前拥有数量
        )); // 结束多行构造或方法调用
        // 返回成长建议，refId 绑定到物品 id。
        return buildHint(tip, message, requiredItemId);
    }
    /**
     * 匹配角色类教练提示
     */
    private CoachHint matchAvatar(CoachTipsConfig.TipDef tip, PlayerData playerData) {
        // 没有角色列表时，avatar 类规则不命中。
        if (playerData == null || playerData.getAvatars() == null || playerData.getAvatars().isEmpty()) {
            return null; // 返回：null
        }
        // 可选阈值：限制只看某个最高等级和最高突破以下的角色。
        Integer maxLevel = tip.maxAvatarLevel();
        Integer maxPromotion = tip.maxPromotion(); // 赋值 maxPromotion，供 matchAvatar 使用
        // best 保存当前最适合提示的角色。
        AvatarEntity best = null;
        // 遍历所有角色，选择一个最值得提醒的低培养对象。
        for (AvatarEntity avatar : playerData.getAvatars()) {
            // 空角色记录直接跳过。
            if (avatar == null) {
                continue; // 本条数据无效或不匹配，跳到下一项
            }
            // 超出等级阈值的角色不参与提示。
            if (maxLevel != null && avatar.getLevel() > maxLevel) {
                continue; // 本条数据无效或不匹配，跳到下一项
            }
            // 超出突破阈值的角色不参与提示。
            if (maxPromotion != null && avatar.getPromotion() > maxPromotion) {
                continue; // 本条数据无效或不匹配，跳到下一项
            }
            // 选等级更低、同级时突破更低的角色作为优先提醒对象。
            if (best == null
                    || avatar.getLevel() < best.getLevel() // 延续上一行布尔条件
                    || (avatar.getLevel() == best.getLevel() && avatar.getPromotion() < best.getPromotion())) { // 延续上一行布尔条件
                best = avatar; // 赋值 best，供 matchAvatar 使用
            }
        }
        // 没找到目标角色时返回空。
        if (best == null) {
            return null; // 返回：null
        }
        // 计算角色属性面板，用于提示中展示当前成长情况。
        AttributeCalculator.AvatarAttributes attrs = attributeCalculator.calculate(best);
        // 用模板注入 avatarId、等级、突破、rank 和四维属性。
        String message = render(tip.template(), Map.of(
                "avatarId", String.valueOf(best.getAvatarId()),
                "avatarLevel", String.valueOf(best.getLevel()),
                "promotion", String.valueOf(best.getPromotion()),
                "rank", String.valueOf(best.getRank()),
                "hp", String.valueOf(attrs.hp()),
                "atk", String.valueOf(attrs.atk()),
                "def", String.valueOf(attrs.def()),
                "spd", String.valueOf(attrs.spd()) // 模板变量：速度属性
        )); // 结束多行构造或方法调用
        // 返回角色成长提示，refId 使用 avatarId。
        return buildHint(tip, message, best.getAvatarId());
    }
    /**
     * 匹配天赋类教练提示
     */
    private CoachHint matchTalent(CoachTipsConfig.TipDef tip, List<AvatarTalentEntity> talents) {
        // 天赋列表为空时，talent 类规则不命中。
        if (talents == null || talents.isEmpty()) {
            return null; // 返回：null
        }
        // 是否要求未激活天赋，由规则配置控制。
        boolean wantInactive = Boolean.TRUE.equals(tip.requireInactiveTalent());
        // 可选的 target talent id，若配置了则只看指定天赋。
        int requiredTalentId = tip.requiredTalentId() == null ? 0 : tip.requiredTalentId();
        // 允许的最大天赋等级阈值。
        Integer maxTalentLevel = tip.maxTalentLevel();
        // best 选择一个最适合提醒的天赋记录。
        AvatarTalentEntity best = null;
        // 遍历所有天赋，找符合条件且最弱的一个。
        for (AvatarTalentEntity talent : talents) {
            // 空天赋记录直接跳过。
            if (talent == null) {
                continue; // 本条数据无效或不匹配，跳到下一项
            }
            // 若指定了天赋 id，则只处理对应记录。
            if (requiredTalentId > 0 && talent.getTalentId() != requiredTalentId) {
                continue; // 本条数据无效或不匹配，跳到下一项
            }
            // 需要未激活时，已激活天赋不参与提醒。
            if (wantInactive && talent.isActivated()) {
                continue; // 本条数据无效或不匹配，跳到下一项
            }
            // 已达到或超过阈值的天赋不再提醒继续升级。
            if (maxTalentLevel != null && talent.getLevel() >= maxTalentLevel) {
                continue; // 本条数据无效或不匹配，跳到下一项
            }
            // 选择等级更低、且未激活优先的天赋。
            if (best == null
                    || talent.getLevel() < best.getLevel() // 延续上一行布尔条件
                    || (talent.getLevel() == best.getLevel() && !talent.isActivated() && best.isActivated())) { // 延续上一行布尔条件
                best = talent; // 赋值 best，供 matchTalent 使用
            }
        }
        // 没有符合条件的天赋则返回空。
        if (best == null) {
            return null; // 返回：null
        }
        // 用模板说明 avatarId、talentId、等级和激活状态。
        String message = render(tip.template(), Map.of(
                "avatarId", String.valueOf(best.getAvatarId()),
                "talentId", String.valueOf(best.getTalentId()),
                "talentLevel", String.valueOf(best.getLevel()),
                "activated", best.isActivated() ? "已激活" : "未激活"
        )); // 结束多行构造或方法调用
        // 返回天赋提示，refId 使用 avatarId。
        return buildHint(tip, message, best.getAvatarId());
    }
    /**
     * 匹配元信息类教练提示
     */
    private CoachHint matchMeta(CoachTipsConfig.TipDef tip, PlayerData playerData) {
        // 没有使用率统计服务时，meta 类规则直接关闭。
        if (avatarUsageStatsService == null) {
            return null; // 返回：null
        }
        // 读取 topN 参数，默认展示前 5 名。
        int topN = tip.metaTopN() == null ? 5 : Math.max(1, tip.metaTopN());
        // 是否要求推荐的角色必须是玩家自己拥有的。
        boolean requireOwned = Boolean.TRUE.equals(tip.metaRequireOwned());
        // 读取当前热门角色榜单。
        List<AvatarUsageStatsService.UsageEntry> top = avatarUsageStatsService.topN(topN);
        // 榜单为空时没有可提示内容。
        if (top.isEmpty()) {
            return null; // 返回：null
        }
        // chosen 保存最终挑中的热门角色。
        AvatarUsageStatsService.UsageEntry chosen = null;
        // 若要求玩家已拥有，则在热门榜里找玩家实际持有的角色。
        if (requireOwned) {
            // 没有角色列表时无法判断是否拥有热门角色。
            if (playerData == null || playerData.getAvatars() == null) {
                return null; // 返回：null
            }
            // 遍历热门榜，从高到低寻找玩家已拥有的角色。
            for (AvatarUsageStatsService.UsageEntry entry : top) {
                // 逐个比对玩家角色和热门榜 avatarId。
                for (AvatarEntity avatar : playerData.getAvatars()) {
                    if (avatar != null && avatar.getAvatarId() == entry.avatarId()) { // 若满足 avatar != null && avatar.getAvatarId() == entry.avatarId() 则走本分支
                        chosen = entry; // 赋值 chosen，供 matchMeta 使用
                        break; // 已达截断/命中条件，结束循环
                    }
                }
                // 一旦找到可用角色就停止继续扫描。
                if (chosen != null) {
                    break; // 已达截断/命中条件，结束循环
                }
            }
        } else { // 条件不成立时的替代分支
            // 不要求拥有时，直接取热门榜第一名。
            chosen = top.get(0);
        }
        // 没有选中任何角色时不返回提示。
        if (chosen == null) {
            return null; // 返回：null
        }
        // 组合热门角色提示，带上使用率、胜率和榜单摘要。
        String message = render(tip.template(), Map.of(
                "avatarId", String.valueOf(chosen.avatarId()),
                "rank", String.valueOf(chosen.rank()),
                "pickCount", String.valueOf(chosen.pickCount()),
                "usageRate", formatPercent(chosen.usageRate()),
                "winRate", formatPercent(chosen.winRate()),
                "usageSummary", avatarUsageStatsService.summaryText(topN) // 模板变量：热门使用率摘要
        )); // 结束多行构造或方法调用
        // 返回 meta 提示，refId 使用 avatarId。
        return buildHint(tip, message, chosen.avatarId());
    }
    /**
     * 格式化百分比文本
     */
    private static String formatPercent(double rate) {
        // 把 0.1234 格式化成 12.3% 风格，便于在提示文本中展示。
        return Math.round(rate * 1000.0) / 10.0 + "%";
    }
    /**
     * 解析任务标题
     */
    private String resolveQuestTitle(int questId) {
        // 通过 questId 查询任务配置，尝试输出正式标题。
        QuestConfigRepository.QuestConfig cfg = questConfigRepository.find(questId);
        // 任务标题存在且非空时，优先使用配置标题。
        if (cfg != null && cfg.title() != null && !cfg.title().isBlank()) {
            return cfg.title(); // 返回：cfg.title()
        }
        // 配置不存在时，用任务编号作为兜底展示。
        return "任务#" + questId;
    }
    /**
     * 构建结果
     */
    private static CoachHint buildHint(CoachTipsConfig.TipDef tip, String message, int refId) {
        // 统一把 tip 配置封装成 CoachHint，供上层协议和审计使用。
        return new CoachHint(
                tip.id(), // 续写 buildHint 的参数列表
                tip.category() == null ? "" : tip.category(),
                tip.priority(), // 续写 buildHint 的参数列表
                tip.title() == null ? "" : tip.title(),
                message == null ? "" : message,
                tip.action() == null ? "" : tip.action(),
                refId // 关联业务实体 ID（任务/活动/角色等）
        ); // 结束多行构造或方法调用
    }
    /**
     * 渲染提示模板
     */
    private static String render(String template) {
        // 空参数模板调用统一走无变量渲染。
        return render(template, Map.of());
    }
    /**
     * 渲染提示模板
     */
    private static String render(String template, Map<String, String> vars) {
        // 模板为空时直接返回空串，避免后续替换报错。
        if (template == null) {
            return ""; // 返回：""
        }
        // result 保存逐步替换后的模板文本。
        String result = template;
        // safe 复制一份变量表，避免外部 map 被并发修改。
        Map<String, String> safe = vars == null ? Map.of() : new HashMap<>(vars);
        // 遍历每个占位符，把 {key} 替换成业务值。
        for (Map.Entry<String, String> e : safe.entrySet()) {
            // 使用 Objects.toString 兜底空值，保证渲染结果稳定。
            result = result.replace("{" + e.getKey() + "}", Objects.toString(e.getValue(), ""));
        }
        // 返回渲染完成的提示文本。
        return result;
    }
}
