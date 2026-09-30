// 任务触发引擎：将战斗杀怪、NPC 交互等事件统一委托给进度仓储更新 objectivesJson
package cn.itcast.demo.mylunarcore.quest;

import cn.itcast.demo.mylunarcore.repo.QuestProgressRepository;
import cn.itcast.demo.mylunarcore.story.StoryChapterService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 任务触发引擎。
 * 把战斗杀怪、NPC 交互、场景事件等上报统一委托给 QuestProgressRepository.applyTrigger，
 * 并同步推进每日任务；存在主线章节时提升主线任务优先级、压制低级支线干扰新手引导。
 */
@Component
public class QuestTriggerEngine {

    private final QuestProgressRepository questProgressRepository;
    private final ObjectProvider<DailyMissionService> dailyMissionProvider;
    private final ObjectProvider<StoryChapterService> storyChapterProvider;

    public QuestTriggerEngine(QuestProgressRepository questProgressRepository,
                              ObjectProvider<DailyMissionService> dailyMissionProvider,
                              ObjectProvider<StoryChapterService> storyChapterProvider) {
        this.questProgressRepository = questProgressRepository;
        this.dailyMissionProvider = dailyMissionProvider;
        this.storyChapterProvider = storyChapterProvider;
    }

    /**
     * 响应一次任务触发事件。
     *
     * @param playerId    玩家 uid
     * @param triggerType 触发类型，与 ObjectiveConfig.targetType 对齐（1=杀怪 2=NPC 等）
     * @param p1          主参数（如 monsterId、npcId）
     * @param p2          扩展参数（如数量、子类型）
     * @param p3          扩展参数（预留）
     */
    public void onTrigger(int playerId, int triggerType, long p1, long p2, long p3) {
        StoryChapterService story = storyChapterProvider.getIfAvailable();
        if (story != null && story.currentMainlinePriority(playerId) > 0) {
            // 主线强制触发：先确保主线任务处于进行中，再应用触发（支线仍可推进，但不抢引导）
            for (int questId : story.activeMainlineQuestIds(playerId)) {
                questProgressRepository.ensureAccepted(playerId, questId);
            }
        }
        questProgressRepository.applyTrigger(playerId, triggerType, p1, p2, p3);
        DailyMissionService daily = dailyMissionProvider.getIfAvailable();
        if (daily != null) {
            daily.onTrigger(playerId, triggerType, p1);
        }
    }

    public void onSceneEventGeneric(int playerId) {
        onTrigger(playerId, 4, 0, 0, 0);
    }

    public void onSceneEvent(int playerId, int triggerType) {
        onTrigger(playerId, 3, 0, triggerType, 0);
    }

    public void onNpcInteract(int playerId, int npcId) {
        onTrigger(playerId, 2, npcId, 0, 0);
    }

    /** 是否应屏蔽某支线任务的主动推送（主线优先级更高时）。 */
    public boolean shouldSuppressSideQuest(int playerId, int questId) {
        StoryChapterService story = storyChapterProvider.getIfAvailable();
        if (story == null) {
            return false;
        }
        if (story.isMainlineQuest(questId)) {
            return false;
        }
        return story.currentMainlinePriority(playerId) >= 80;
    }
}
