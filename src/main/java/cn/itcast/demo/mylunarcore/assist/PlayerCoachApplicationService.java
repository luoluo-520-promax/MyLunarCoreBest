package cn.itcast.demo.mylunarcore.assist;

import cn.itcast.demo.mylunarcore.character.TalentApplicationService;
import cn.itcast.demo.mylunarcore.common.ActivityQueryService;
import cn.itcast.demo.mylunarcore.common.AppLogger;
import cn.itcast.demo.mylunarcore.common.LogCategory;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.model.ActivityConfig;
import cn.itcast.demo.mylunarcore.model.AvatarEntity;
import cn.itcast.demo.mylunarcore.model.AvatarTalentEntity;
import cn.itcast.demo.mylunarcore.model.PlayerData;
import cn.itcast.demo.mylunarcore.model.QuestProgressEntity;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.quest.QuestProgressApplicationService;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 组装玩家任务/活动/天赋快照，调用 {@link CoachRuleEngine} 产出规则教练提示。
 * <p>
 * 展示数量限制在 [1, 10]；总开关或 ruleCoach 关闭时返回空列表。
 */
@Service
public class PlayerCoachApplicationService {
    /**
     * 日志记录器
     */
    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_ASSIST, PlayerCoachApplicationService.class);
    /**
     * 读取 enabled / ruleCoach / 上下文预算
     */
    private final LunarCoreProperties properties;
    /**
     * 取在线会话与 PlayerData
     */
    private final GameSessionManager sessionManager;
    /**
     * 规则评估引擎
     */
    private final CoachRuleEngine coachRuleEngine;
    /**
     * 当前有效活动
     */
    private final ActivityQueryService activityQueryService;
    /**
     * 任务进度
     */
    private final QuestProgressApplicationService questProgressApplicationService;
    /**
     * 角色天赋列表
     */
    private final TalentApplicationService talentApplicationService;
    /**
     * 构造并注入依赖
     */
    public PlayerCoachApplicationService(LunarCoreProperties properties,
                                         GameSessionManager sessionManager,
                                         CoachRuleEngine coachRuleEngine,
                                         ActivityQueryService activityQueryService,
                                         QuestProgressApplicationService questProgressApplicationService,
                                         TalentApplicationService talentApplicationService) {
        this.properties = properties; // 保存配置
        this.sessionManager = sessionManager; // 保存会话管理器
        this.coachRuleEngine = coachRuleEngine; // 保存规则引擎
        this.activityQueryService = activityQueryService; // 保存活动查询
        this.questProgressApplicationService = questProgressApplicationService; // 保存任务进度服务
        this.talentApplicationService = talentApplicationService; // 保存天赋服务
    }
    /**
     * 在线路径：按 uid 拉会话与进度，评估后截断到 maxHints。
     */
    public List<CoachHint> suggest(long uid, int maxHints) {
        if (!properties.getAiAssist().isEnabled() || !properties.getAiAssist().isRuleCoachEnabled()) { // 若满足 !properties.getAiAssist().isEnabled() || !properties.getAiAssist().isRuleCoachEn 则走本分支
            return List.of(); // 总开关或规则教练关闭
        }
        GameSession session = sessionManager.getOrNull(uid); // 可能离线
        PlayerData playerData = session == null ? null : session.getPlayerData(); // 离线则无角色/背包
        int playerId = (int) (uid & 0xffffffffL); // 与存档 playerId 对齐
        List<QuestProgressEntity> quests = questProgressApplicationService.list(playerId); // 任务进度列表
        List<ActivityConfig> activities = activityQueryService.listCurrentlyActiveConfigs(); // 当前开放活动
        List<AvatarTalentEntity> talents = loadTalents(playerId, playerData); // 按预算加载天赋
        List<CoachHint> hints = coachRuleEngine.evaluate(playerData, quests, activities, talents); // 规则评估
        int limit = maxHints <= 0 ? 1 : Math.min(maxHints, 10); // 非法 max 回退 1，且不超过 10
        if (hints.size() > limit) { // 若满足 hints.size() > limit 则走本分支
            hints = hints.subList(0, limit); // 引擎已按优先级排序，取前 N
        }
        log.debug("coach suggest uid={}, hintCount={}", uid, hints.size()); // 调试日志
        return List.copyOf(hints); // 不可变快照返回
    }
    /**
     * 快照路径重载：无天赋列表时传空。
     */
    public List<CoachHint> suggestFromSnapshot(PlayerData playerData,
                                               List<QuestProgressEntity> quests,
                                               List<ActivityConfig> activities,
                                               int maxHints) {
        return suggestFromSnapshot(playerData, quests, activities, List.of(), maxHints); // 天赋默认空
    }
    /**
     * 快照路径：直接用调用方提供的数据评估（单测/离线）。
     */
    public List<CoachHint> suggestFromSnapshot(PlayerData playerData,
                                               List<QuestProgressEntity> quests,
                                               List<ActivityConfig> activities,
                                               List<AvatarTalentEntity> talents,
                                               int maxHints) {
        List<CoachHint> hints = coachRuleEngine.evaluate(playerData, quests, activities, talents); // 直接评估
        int limit = maxHints <= 0 ? 1 : Math.min(maxHints, 10); // 与在线路径相同截断策略
        if (hints.size() > limit) { // 若满足 hints.size() > limit 则走本分支
            hints = hints.subList(0, limit); // 截断
        }
        return List.copyOf(hints); // 不可变返回
    }
    /**
     * 按配置预算加载天赋：最多 contextMaxAvatars 个角色， 每角色最多 contextMaxTalentsPerAvatar 条。
     */
    private List<AvatarTalentEntity> loadTalents(int playerId, PlayerData playerData) {
        if (playerData == null || playerData.getAvatars() == null || playerData.getAvatars().isEmpty()) { // 若满足 playerData == null || playerData.getAvatars() == null || playerData.getAvatars() 则走本分支
            return List.of(); // 无角色则无天赋
        }
        int maxPerAvatar = Math.max(0, properties.getAiAssist().getContextMaxTalentsPerAvatar()); // 每角色天赋上限
        int maxAvatars = Math.max(0, properties.getAiAssist().getContextMaxAvatars()); // 角色扫描上限
        List<AvatarTalentEntity> all = new ArrayList<>(); // 汇总结果
        int avatarSeen = 0; // 已处理角色数
        for (AvatarEntity avatar : playerData.getAvatars()) { // 遍历 (AvatarEntity avatar : playerData.getAvatars()) { 处理每一项
            if (avatar == null) { // 若满足 avatar == null 则走本分支
                continue; // 跳过空槽
            }
            if (avatarSeen >= maxAvatars) { // 若满足 avatarSeen >= maxAvatars 则走本分支
                break; // 达到角色预算
            }
            avatarSeen++; // 计数有效角色
            List<AvatarTalentEntity> rows = talentApplicationService.listTalents(playerId, avatar.getAvatarId()); // 查库
            if (rows == null || rows.isEmpty()) { // 若满足 rows == null || rows.isEmpty() 则走本分支
                continue; // 该角色无天赋记录
            }
            int taken = 0; // 本角色已取条数
            for (AvatarTalentEntity row : rows) { // 遍历 (AvatarTalentEntity row : rows) { 处理每一项
                if (row == null) { // 若满足 row == null 则走本分支
                    continue; // 跳过空行
                }
                if (taken >= maxPerAvatar) { // 若满足 taken >= maxPerAvatar 则走本分支
                    break; // 达到每角色预算
                }
                all.add(row); // 纳入评估输入
                taken++; // 本角色计数 +1
            }
        }
        return List.copyOf(all); // 冻结列表
    }
}
