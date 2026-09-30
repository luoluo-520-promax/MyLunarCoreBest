package cn.itcast.demo.mylunarcore.quest;

import cn.itcast.demo.mylunarcore.economy.RewardDistributor;
import cn.itcast.demo.mylunarcore.model.QuestProgressEntity;
import cn.itcast.demo.mylunarcore.repo.QuestProgressRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QuestProgressApplicationService 任务进度服务测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code QuestProgressApplicationServiceTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("QuestProgressApplicationService 任务进度服务测试")
class QuestProgressApplicationServiceTest {

    private static final Logger log = LoggerFactory.getLogger(QuestProgressApplicationServiceTest.class);

    private static final int PLAYER_ID = QuestTestFixtures.PLAYER_ID;
    private static final int QUEST_ID = QuestTestFixtures.QUEST_ID;

    private QuestProgressRepository questProgressRepository;
    private QuestConfigRepository questConfigRepository;
    private RewardDistributor rewardDistributor;
    private QuestTriggerEngine triggerEngine;
    private QuestProgressApplicationService service;

    @BeforeEach
    void setUp() {
        questProgressRepository = mock(QuestProgressRepository.class);
        questConfigRepository = mock(QuestConfigRepository.class);
        rewardDistributor = mock(RewardDistributor.class);
        triggerEngine = mock(QuestTriggerEngine.class);
        service = new QuestProgressApplicationService(
                questProgressRepository, questConfigRepository, rewardDistributor, triggerEngine);
        log.info("任务进度服务初始化: playerId={}, questId={}", PLAYER_ID, QUEST_ID);
    }

    /**
     * 验证点：list 应返回玩家任务进度列表。
     * <p>测试方法 {@code listShouldReturnProgressEntities}：
     * <ul>
     *   <li>{@code when(questProgressRepository.list(PLAYER_ID)).thenReturn(List.of(}</li>
     *   <li>{@code assertEquals(2, list.size());}</li>
     *   <li>{@code assertEquals(QUEST_ID, list.get(0).getQuestId());}</li>
     *   <li>{@code assertEquals(1, list.get(0).getStatus());}</li>
     * </ul>
     */
    @Test
    @DisplayName("list 应返回玩家任务进度列表")
    void listShouldReturnProgressEntities() {
        when(questProgressRepository.list(PLAYER_ID)).thenReturn(List.of(
                QuestTestFixtures.progress(QUEST_ID, 1, "{}"),
                QuestTestFixtures.progress(10002, 3, "{}")));

        List<QuestProgressEntity> list = service.list(PLAYER_ID);

        log.info("任务列表校验: playerId={}, size={}, firstQuestId={}, firstStatus={}, secondStatus={}",
                PLAYER_ID, list.size(), list.get(0).getQuestId(),
                list.get(0).getStatus(), list.get(1).getStatus());
        assertEquals(2, list.size());
        assertEquals(QUEST_ID, list.get(0).getQuestId());
        assertEquals(1, list.get(0).getStatus());
    }

    /**
     * 验证点：accept 配置为空应返回 retcode=2。
     * <p>测试方法 {@code acceptShouldFailWhenConfigNull}：
     * <ul>
     *   <li>{@code assertFalse(result.success());}</li>
     *   <li>{@code assertEquals(2, result.retcode());}</li>
     *   <li>{@code verify(questProgressRepository, never()).ensureAccepted(anyInt(), anyInt());}</li>
     * </ul>
     */
    @Test
    @DisplayName("accept 配置为空应返回 retcode=2")
    void acceptShouldFailWhenConfigNull() {
        QuestProgressApplicationService.AcceptResult result = service.accept(PLAYER_ID, null);

        log.info("空配置接取校验: playerId={}, success={}, retcode={}",
                PLAYER_ID, result.success(), result.retcode());
        assertFalse(result.success());
        assertEquals(2, result.retcode());
        verify(questProgressRepository, never()).ensureAccepted(anyInt(), anyInt());
    }

    /**
     * 验证点：accept 重复接取应返回 retcode=3。
     * <p>测试方法 {@code acceptShouldFailWhenAlreadyAccepted}：
     * <ul>
     *   <li>{@code when(questProgressRepository.find(PLAYER_ID, QUEST_ID))}</li>
     *   <li>{@code assertFalse(result.success());}</li>
     *   <li>{@code assertEquals(3, result.retcode());}</li>
     *   <li>{@code verify(questProgressRepository, never()).ensureAccepted(anyInt(), anyInt());}</li>
     * </ul>
     */
    @Test
    @DisplayName("accept 重复接取应返回 retcode=3")
    void acceptShouldFailWhenAlreadyAccepted() {
        when(questProgressRepository.find(PLAYER_ID, QUEST_ID))
                .thenReturn(QuestTestFixtures.progress(QUEST_ID, 1, "{}"));

        QuestProgressApplicationService.AcceptResult result =
                service.accept(PLAYER_ID, QuestTestFixtures.questConfig(QUEST_ID));

        log.info("重复接取校验: playerId={}, questId={}, currentStatus=1, success={}, retcode={}",
                PLAYER_ID, QUEST_ID, result.success(), result.retcode());
        assertFalse(result.success());
        assertEquals(3, result.retcode());
        verify(questProgressRepository, never()).ensureAccepted(anyInt(), anyInt());
    }

    /**
     * 验证点：accept 成功应写入进行中进度。
     * <p>测试方法 {@code acceptShouldSucceed}：
     * <ul>
     *   <li>{@code when(questProgressRepository.find(PLAYER_ID, QUEST_ID)).thenReturn(null);}</li>
     *   <li>{@code assertTrue(result.success());}</li>
     *   <li>{@code assertEquals(0, result.retcode());}</li>
     *   <li>{@code verify(questProgressRepository).ensureAccepted(PLAYER_ID, QUEST_ID);}</li>
     * </ul>
     */
    @Test
    @DisplayName("accept 成功应写入进行中进度")
    void acceptShouldSucceed() {
        when(questProgressRepository.find(PLAYER_ID, QUEST_ID)).thenReturn(null);

        QuestProgressApplicationService.AcceptResult result =
                service.accept(PLAYER_ID, QuestTestFixtures.questConfig(QUEST_ID));

        log.info("接取成功校验: playerId={}, questId={}, success={}, retcode={}",
                PLAYER_ID, QUEST_ID, result.success(), result.retcode());
        assertTrue(result.success());
        assertEquals(0, result.retcode());
        verify(questProgressRepository).ensureAccepted(PLAYER_ID, QUEST_ID);
    }

    /**
     * 验证点：syncTrigger 应委托 TriggerEngine。
     * <p>测试方法 {@code syncTriggerShouldDelegate}：
     * <ul>
     *   <li>{@code verify(triggerEngine).onTrigger(PLAYER_ID, 1, 101L, 0L, 0L);}</li>
     * </ul>
     */
    @Test
    @DisplayName("syncTrigger 应委托 TriggerEngine")
    void syncTriggerShouldDelegate() {
        service.syncTrigger(PLAYER_ID, 1, 101L, 0L, 0L);

        log.info("同步触发校验: playerId={}, triggerType=1, p1=101, p2=0, p3=0", PLAYER_ID);
        verify(triggerEngine).onTrigger(PLAYER_ID, 1, 101L, 0L, 0L);
    }

    /**
     * 验证点：submit 配置不存在应返回 retcode=2。
     * <p>测试方法 {@code submitShouldFailWhenConfigMissing}：
     * <ul>
     *   <li>{@code when(questConfigRepository.find(QUEST_ID)).thenReturn(null);}</li>
     *   <li>{@code assertFalse(result.success());}</li>
     *   <li>{@code assertEquals(2, result.retcode());}</li>
     * </ul>
     */
    @Test
    @DisplayName("submit 配置不存在应返回 retcode=2")
    void submitShouldFailWhenConfigMissing() {
        when(questConfigRepository.find(QUEST_ID)).thenReturn(null);

        QuestProgressApplicationService.SubmitResult result = service.submit(PLAYER_ID, QUEST_ID);

        log.info("提交缺配置校验: questId={}, success={}, retcode={}",
                QUEST_ID, result.success(), result.retcode());
        assertFalse(result.success());
        assertEquals(2, result.retcode());
    }

    /**
     * 验证点：submit 非进行中应返回 retcode=3。
     * <p>测试方法 {@code submitShouldFailWhenNotInProgress}：
     * <ul>
     *   <li>{@code when(questConfigRepository.find(QUEST_ID)).thenReturn(QuestTestFixtures.questConfig(QUEST_ID));}</li>
     *   <li>{@code when(questProgressRepository.find(PLAYER_ID, QUEST_ID))}</li>
     *   <li>{@code assertFalse(result.success());}</li>
     *   <li>{@code assertEquals(3, result.retcode());}</li>
     * </ul>
     */
    @Test
    @DisplayName("submit 非进行中应返回 retcode=3")
    void submitShouldFailWhenNotInProgress() {
        when(questConfigRepository.find(QUEST_ID)).thenReturn(QuestTestFixtures.questConfig(QUEST_ID));
        when(questProgressRepository.find(PLAYER_ID, QUEST_ID))
                .thenReturn(QuestTestFixtures.progress(QUEST_ID, 3, "{}"));

        QuestProgressApplicationService.SubmitResult result = service.submit(PLAYER_ID, QUEST_ID);

        log.info("提交状态错误校验: questId={}, status=3, success={}, retcode={}",
                QUEST_ID, result.success(), result.retcode());
        assertFalse(result.success());
        assertEquals(3, result.retcode());
    }

    /**
     * 验证点：submit 目标未完成应返回 retcode=4。
     * <p>测试方法 {@code submitShouldFailWhenObjectivesIncomplete}：
     * <ul>
     *   <li>{@code when(questConfigRepository.find(QUEST_ID)).thenReturn(QuestTestFixtures.questConfig(QUEST_ID));}</li>
     *   <li>{@code when(questProgressRepository.find(PLAYER_ID, QUEST_ID))}</li>
     *   <li>{@code when(questProgressRepository.isCompleted(PLAYER_ID, QUEST_ID)).thenReturn(false);}</li>
     *   <li>{@code assertFalse(result.success());}</li>
     *   <li>{@code assertEquals(4, result.retcode());}</li>
     *   <li>{@code verify(rewardDistributor, never()).grantQuestRewards(anyInt(), any());}</li>
     * </ul>
     */
    @Test
    @DisplayName("submit 目标未完成应返回 retcode=4")
    void submitShouldFailWhenObjectivesIncomplete() {
        when(questConfigRepository.find(QUEST_ID)).thenReturn(QuestTestFixtures.questConfig(QUEST_ID));
        when(questProgressRepository.find(PLAYER_ID, QUEST_ID))
                .thenReturn(QuestTestFixtures.progress(QUEST_ID, 1, "{}"));
        when(questProgressRepository.isCompleted(PLAYER_ID, QUEST_ID)).thenReturn(false);

        QuestProgressApplicationService.SubmitResult result = service.submit(PLAYER_ID, QUEST_ID);

        log.info("目标未完成校验: questId={}, status=1, completed=false, success={}, retcode={}",
                QUEST_ID, result.success(), result.retcode());
        assertFalse(result.success());
        assertEquals(4, result.retcode());
        verify(rewardDistributor, never()).grantQuestRewards(anyInt(), any());
    }

    /**
     * 验证点：submit 发奖失败应返回 retcode=5。
     * <p>测试方法 {@code submitShouldFailWhenRewardFails}：
     * <ul>
     *   <li>{@code when(questConfigRepository.find(QUEST_ID)).thenReturn(config);}</li>
     *   <li>{@code when(questProgressRepository.find(PLAYER_ID, QUEST_ID))}</li>
     *   <li>{@code when(questProgressRepository.isCompleted(PLAYER_ID, QUEST_ID)).thenReturn(true);}</li>
     *   <li>{@code when(rewardDistributor.grantQuestRewards(PLAYER_ID, config)).thenReturn(false);}</li>
     *   <li>{@code assertFalse(result.success());}</li>
     *   <li>{@code assertEquals(5, result.retcode());}</li>
     * </ul>
     */
    @Test
    @DisplayName("submit 发奖失败应返回 retcode=5")
    void submitShouldFailWhenRewardFails() {
        QuestConfigRepository.QuestConfig config = QuestTestFixtures.questConfig(QUEST_ID);
        when(questConfigRepository.find(QUEST_ID)).thenReturn(config);
        when(questProgressRepository.find(PLAYER_ID, QUEST_ID))
                .thenReturn(QuestTestFixtures.progress(QUEST_ID, 1, "{}"));
        when(questProgressRepository.isCompleted(PLAYER_ID, QUEST_ID)).thenReturn(true);
        when(rewardDistributor.grantQuestRewards(PLAYER_ID, config)).thenReturn(false);

        QuestProgressApplicationService.SubmitResult result = service.submit(PLAYER_ID, QUEST_ID);

        log.info("发奖失败校验: questId={}, completed=true, rewardOk=false, success={}, retcode={}",
                QUEST_ID, result.success(), result.retcode());
        assertFalse(result.success());
        assertEquals(5, result.retcode());
        verify(questProgressRepository, never()).markSubmitted(anyInt(), anyInt());
    }

    /**
     * 验证点：submit 成功应发奖并标记已提交。
     * <p>测试方法 {@code submitShouldSucceed}：
     * <ul>
     *   <li>{@code when(questConfigRepository.find(QUEST_ID)).thenReturn(config);}</li>
     *   <li>{@code when(questProgressRepository.find(PLAYER_ID, QUEST_ID))}</li>
     *   <li>{@code when(questProgressRepository.isCompleted(PLAYER_ID, QUEST_ID)).thenReturn(true);}</li>
     *   <li>{@code when(rewardDistributor.grantQuestRewards(PLAYER_ID, config)).thenReturn(true);}</li>
     *   <li>{@code assertTrue(result.success());}</li>
     *   <li>{@code assertEquals(0, result.retcode());}</li>
     * </ul>
     */
    @Test
    @DisplayName("submit 成功应发奖并标记已提交")
    void submitShouldSucceed() {
        QuestConfigRepository.QuestConfig config = QuestTestFixtures.questConfig(QUEST_ID);
        when(questConfigRepository.find(QUEST_ID)).thenReturn(config);
        when(questProgressRepository.find(PLAYER_ID, QUEST_ID))
                .thenReturn(QuestTestFixtures.progress(QUEST_ID, 1, "{}"));
        when(questProgressRepository.isCompleted(PLAYER_ID, QUEST_ID)).thenReturn(true);
        when(rewardDistributor.grantQuestRewards(PLAYER_ID, config)).thenReturn(true);

        QuestProgressApplicationService.SubmitResult result = service.submit(PLAYER_ID, QUEST_ID);

        log.info("提交成功校验: playerId={}, questId={}, success={}, retcode={}, rewardCurrencyAmount=200",
                PLAYER_ID, QUEST_ID, result.success(), result.retcode());
        assertTrue(result.success());
        assertEquals(0, result.retcode());
        verify(rewardDistributor).grantQuestRewards(PLAYER_ID, config);
        verify(questProgressRepository).markSubmitted(PLAYER_ID, QUEST_ID);
    }

    /**
     * 验证点：abandon 任务不存在应返回 retcode=2。
     * <p>测试方法 {@code abandonShouldFailWhenMissing}：
     * <ul>
     *   <li>{@code when(questProgressRepository.find(PLAYER_ID, QUEST_ID)).thenReturn(null);}</li>
     *   <li>{@code assertFalse(result.success());}</li>
     *   <li>{@code assertEquals(2, result.retcode());}</li>
     * </ul>
     */
    @Test
    @DisplayName("abandon 任务不存在应返回 retcode=2")
    void abandonShouldFailWhenMissing() {
        when(questProgressRepository.find(PLAYER_ID, QUEST_ID)).thenReturn(null);

        QuestProgressApplicationService.AbandonResult result = service.abandon(PLAYER_ID, QUEST_ID);

        log.info("放弃缺任务校验: questId={}, success={}, retcode={}",
                QUEST_ID, result.success(), result.retcode());
        assertFalse(result.success());
        assertEquals(2, result.retcode());
    }

    /**
     * 验证点：abandon 非进行中应返回 retcode=3。
     * <p>测试方法 {@code abandonShouldFailWhenNotInProgress}：
     * <ul>
     *   <li>{@code when(questProgressRepository.find(PLAYER_ID, QUEST_ID))}</li>
     *   <li>{@code assertFalse(result.success());}</li>
     *   <li>{@code assertEquals(3, result.retcode());}</li>
     *   <li>{@code verify(questProgressRepository, never()).update(anyInt(), anyInt(), anyInt(), anyString());}</li>
     * </ul>
     */
    @Test
    @DisplayName("abandon 非进行中应返回 retcode=3")
    void abandonShouldFailWhenNotInProgress() {
        when(questProgressRepository.find(PLAYER_ID, QUEST_ID))
                .thenReturn(QuestTestFixtures.progress(QUEST_ID, 3, "{}"));

        QuestProgressApplicationService.AbandonResult result = service.abandon(PLAYER_ID, QUEST_ID);

        log.info("放弃状态错误校验: questId={}, status=3, success={}, retcode={}",
                QUEST_ID, result.success(), result.retcode());
        assertFalse(result.success());
        assertEquals(3, result.retcode());
        verify(questProgressRepository, never()).update(anyInt(), anyInt(), anyInt(), anyString());
    }

    /**
     * 验证点：abandon 成功应将 status 置为 4。
     * <p>测试方法 {@code abandonShouldSucceed}：
     * <ul>
     *   <li>{@code when(questProgressRepository.find(PLAYER_ID, QUEST_ID))}</li>
     *   <li>{@code assertTrue(result.success());}</li>
     *   <li>{@code assertEquals(0, result.retcode());}</li>
     *   <li>{@code verify(questProgressRepository).update(PLAYER_ID, QUEST_ID, 4, "{\"1\":0}");}</li>
     * </ul>
     */
    @Test
    @DisplayName("abandon 成功应将 status 置为 4")
    void abandonShouldSucceed() {
        when(questProgressRepository.find(PLAYER_ID, QUEST_ID))
                .thenReturn(QuestTestFixtures.progress(QUEST_ID, 1, "{\"1\":0}"));

        QuestProgressApplicationService.AbandonResult result = service.abandon(PLAYER_ID, QUEST_ID);

        log.info("放弃成功校验: playerId={}, questId={}, success={}, retcode={}, newStatus=4",
                PLAYER_ID, QUEST_ID, result.success(), result.retcode());
        assertTrue(result.success());
        assertEquals(0, result.retcode());
        verify(questProgressRepository).update(PLAYER_ID, QUEST_ID, 4, "{\"1\":0}");
    }
}
