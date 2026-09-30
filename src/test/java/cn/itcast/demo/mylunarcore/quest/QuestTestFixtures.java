package cn.itcast.demo.mylunarcore.quest;

import cn.itcast.demo.mylunarcore.model.QuestProgressEntity;

import java.util.List;

final /**
 * QuestTestFixtures。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code QuestTestFixtures}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
class QuestTestFixtures {

    static final int PLAYER_ID = 1001;
    static final long PLAYER_UID = 1001L;
    static final int QUEST_ID = 10001;

    private QuestTestFixtures() {
    }

    static QuestConfigRepository.ObjectiveConfig objective(
            int objectiveId, int targetType, int targetId, int required) {
        return new QuestConfigRepository.ObjectiveConfig(objectiveId, targetType, targetId, required);
    }

    static QuestConfigRepository.RewardConfig currencyReward(int currencyId, int amount) {
        return new QuestConfigRepository.RewardConfig(currencyId, amount, null, null);
    }

    static QuestConfigRepository.RewardConfig itemReward(int itemId, int count) {
        return new QuestConfigRepository.RewardConfig(null, null, itemId, count);
    }

    static QuestConfigRepository.QuestConfig questConfig(int questId) {
        return new QuestConfigRepository.QuestConfig(
                questId,
                "测试任务",
                "测试描述",
                List.of(
                        objective(1, 2, 1001, 1),
                        objective(2, 3, 0, 1)),
                List.of(
                        currencyReward(1, 200),
                        itemReward(101, 5)));
    }

    static QuestProgressEntity progress(int questId, int status, String objectivesJson) {
        QuestProgressEntity entity = new QuestProgressEntity();
        entity.setPlayerId(PLAYER_ID);
        entity.setQuestId(questId);
        entity.setStatus(status);
        entity.setObjectivesJson(objectivesJson);
        return entity;
    }
}
