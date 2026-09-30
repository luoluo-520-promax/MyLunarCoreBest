package cn.itcast.demo.mylunarcore.quest;

import cn.itcast.demo.mylunarcore.repo.QuestProgressRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * QuestTriggerEngine 任务触发引擎测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code QuestTriggerEngineTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("QuestTriggerEngine 任务触发引擎测试")
class QuestTriggerEngineTest {

    private static final Logger log = LoggerFactory.getLogger(QuestTriggerEngineTest.class);

    private static final int PLAYER_ID = QuestTestFixtures.PLAYER_ID;

    private QuestProgressRepository questProgressRepository;
    private QuestTriggerEngine engine;

    @BeforeEach
    void setUp() {
        questProgressRepository = mock(QuestProgressRepository.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<DailyMissionService> daily = mock(ObjectProvider.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<cn.itcast.demo.mylunarcore.story.StoryChapterService> story = mock(ObjectProvider.class);
        when(daily.getIfAvailable()).thenReturn(null);
        when(story.getIfAvailable()).thenReturn(null);
        engine = new QuestTriggerEngine(questProgressRepository, daily, story);
        log.info("触发引擎初始化: playerId={}", PLAYER_ID);
    }

    /**
     * 验证点：onTrigger 应透传参数给仓储 applyTrigger。
     * <p>测试方法 {@code onTriggerShouldDelegateToRepository}：
     * <ul>
     *   <li>{@code verify(questProgressRepository).applyTrigger(PLAYER_ID, 1, 10001L, 2L, 3L);}</li>
     * </ul>
     */
    @Test
    @DisplayName("onTrigger 应透传参数给仓储 applyTrigger")
    void onTriggerShouldDelegateToRepository() {
        engine.onTrigger(PLAYER_ID, 1, 10001L, 2L, 3L);

        log.info("通用触发校验: playerId={}, triggerType=1, p1=10001, p2=2, p3=3", PLAYER_ID);
        verify(questProgressRepository).applyTrigger(PLAYER_ID, 1, 10001L, 2L, 3L);
    }

    /**
     * 验证点：onSceneEventGeneric 应触发 targetType=4。
     * <p>测试方法 {@code onSceneEventGenericShouldUseType4}：
     * <ul>
     *   <li>{@code verify(questProgressRepository).applyTrigger(PLAYER_ID, 4, 0L, 0L, 0L);}</li>
     * </ul>
     */
    @Test
    @DisplayName("onSceneEventGeneric 应触发 targetType=4")
    void onSceneEventGenericShouldUseType4() {
        engine.onSceneEventGeneric(PLAYER_ID);

        log.info("通用场景事件校验: playerId={}, triggerType=4, p1=0, p2=0, p3=0", PLAYER_ID);
        verify(questProgressRepository).applyTrigger(PLAYER_ID, 4, 0L, 0L, 0L);
    }

    /**
     * 验证点：onSceneEvent 应将事件子类型写入 p2。
     * <p>测试方法 {@code onSceneEventShouldPassSubTypeInP2}：
     * <ul>
     *   <li>{@code verify(questProgressRepository).applyTrigger(PLAYER_ID, 3, 0L, eventType, 0L);}</li>
     * </ul>
     */
    @Test
    @DisplayName("onSceneEvent 应将事件子类型写入 p2")
    void onSceneEventShouldPassSubTypeInP2() {
        int eventType = 7;
        engine.onSceneEvent(PLAYER_ID, eventType);

        log.info("场景事件校验: playerId={}, triggerType=3, p1=0, p2={}, p3=0", PLAYER_ID, eventType);
        verify(questProgressRepository).applyTrigger(PLAYER_ID, 3, 0L, eventType, 0L);
    }

    /**
     * 验证点：onNpcInteract 应将 npcId 写入 p1。
     * <p>测试方法 {@code onNpcInteractShouldPassNpcIdInP1}：
     * <ul>
     *   <li>{@code verify(questProgressRepository).applyTrigger(PLAYER_ID, 2, npcId, 0L, 0L);}</li>
     * </ul>
     */
    @Test
    @DisplayName("onNpcInteract 应将 npcId 写入 p1")
    void onNpcInteractShouldPassNpcIdInP1() {
        int npcId = 1001;
        engine.onNpcInteract(PLAYER_ID, npcId);

        log.info("NPC交互校验: playerId={}, triggerType=2, p1={}, p2=0, p3=0", PLAYER_ID, npcId);
        verify(questProgressRepository).applyTrigger(PLAYER_ID, 2, npcId, 0L, 0L);
    }
}
