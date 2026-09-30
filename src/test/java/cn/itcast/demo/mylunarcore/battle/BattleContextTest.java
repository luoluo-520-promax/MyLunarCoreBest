package cn.itcast.demo.mylunarcore.battle;

import cn.itcast.demo.mylunarcore.repo.MazeSkillActionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * BattleContext 战斗上下文测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code BattleContextTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("BattleContext 战斗上下文测试")
class BattleContextTest {

    private static final Logger log = LoggerFactory.getLogger(BattleContextTest.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private BattleContext context;

    @BeforeEach
    void setUp() {
        context = BattleTestFixtures.createContext(9001L, 77, BattleTestFixtures.twoWaveStage());
        log.info("战局初始化: battleId={}, waveCount={}, currentWave={}, turn={}",
                context.getBattleId(), context.getWaveCount(), context.getCurrentWave(), context.getTurn());
    }

    /**
     * 验证点：createNew 应初始化玩家与第一波怪物。
     * <p>测试方法 {@code createNewShouldInitializePlayerAndFirstWave}：
     * <ul>
     *   <li>{@code assertNotNull(player);}</li>
     *   <li>{@code assertNotNull(monster101);}</li>
     *   <li>{@code assertNotNull(monster102);}</li>
     *   <li>{@code assertEquals(1000, player.getHp());}</li>
     *   <li>{@code assertFalse(player.isDead());}</li>
     * </ul>
     */
    @Test
    @DisplayName("createNew 应初始化玩家与第一波怪物")
    void createNewShouldInitializePlayerAndFirstWave() {
        EntityState player = context.getEntity(77);
        EntityState monster101 = context.getEntity(101);
        EntityState monster102 = context.getEntity(102);

        assertNotNull(player);
        assertNotNull(monster101);
        assertNotNull(monster102);
        log.info("初始实体校验: playerHp={}, playerDead={}, monster101Hp={}, monster102Hp={}",
                player.getHp(), player.isDead(), monster101.getHp(), monster102.getHp());
        assertEquals(1000, player.getHp());
        assertFalse(player.isDead());
    }

    /**
     * 验证点：incrementTurn 应递增回合数。
     * <p>测试方法 {@code incrementTurnShouldAdvanceTurnCounter}：
     * <ul>
     *   <li>{@code assertEquals(3, context.getTurn());}</li>
     * </ul>
     */
    @Test
    @DisplayName("incrementTurn 应递增回合数")
    void incrementTurnShouldAdvanceTurnCounter() {
        int turnBefore = context.getTurn();
        context.incrementTurn();
        context.incrementTurn();
        log.info("回合递增校验: turnBefore={}, turnAfter={}", turnBefore, context.getTurn());
        assertEquals(3, context.getTurn());
    }

    /**
     * 验证点：switchToWave 应切换波次并注册新怪物。
     * <p>测试方法 {@code switchToWaveShouldLoadMonstersForWave}：
     * <ul>
     *   <li>{@code assertNotNull(monster201);}</li>
     *   <li>{@code assertEquals(2, context.getCurrentWave());}</li>
     *   <li>{@code assertEquals(300, monster201.getHp());}</li>
     * </ul>
     */
    @Test
    @DisplayName("switchToWave 应切换波次并注册新怪物")
    void switchToWaveShouldLoadMonstersForWave() {
        int waveBefore = context.getCurrentWave();
        context.switchToWave(2);
        EntityState monster201 = context.getEntity(201);

        assertNotNull(monster201);
        log.info("切波校验: waveBefore={}, waveAfter={}, monster201Hp={}",
                waveBefore, context.getCurrentWave(), monster201.getHp());
        assertEquals(2, context.getCurrentWave());
        assertEquals(300, monster201.getHp());
    }

    /**
     * 验证点：isAllMonstersDeadInWave 应正确判断波次清场。
     * <p>测试方法 {@code isAllMonstersDeadInWaveShouldDetectWaveClear}：
     * <ul>
     *   <li>{@code assertFalse(clearedBefore);}</li>
     *   <li>{@code assertTrue(clearedAfter);}</li>
     * </ul>
     */
    @Test
    @DisplayName("isAllMonstersDeadInWave 应正确判断波次清场")
    void isAllMonstersDeadInWaveShouldDetectWaveClear() {
        boolean clearedBefore = context.isAllMonstersDeadInWave(1);
        context.getEntity(101).setHp(0);
        context.getEntity(101).setDead(true);
        context.getEntity(102).setHp(0);
        context.getEntity(102).setDead(true);
        boolean clearedAfter = context.isAllMonstersDeadInWave(1);

        log.info("波次清场校验: waveIndex=1, clearedBefore={}, clearedAfter={}, monster101Dead={}, monster102Dead={}",
                clearedBefore, clearedAfter,
                context.getEntity(101).isDead(), context.getEntity(102).isDead());
        assertFalse(clearedBefore);
        assertTrue(clearedAfter);
    }

    /**
     * 验证点：getResolvedActionsForSkill 应解析技能行为链。
     * <p>测试方法 {@code getResolvedActionsForSkillShouldParseActions}：
     * <ul>
     *   <li>{@code when(repo.findBySkillId(1001)).thenReturn(List.of(}</li>
     *   <li>{@code assertEquals(3, actions.size());}</li>
     *   <li>{@code assertEquals(-150, actions.get(0).tryParseHpDelta());}</li>
     *   <li>{@code assertEquals(List.of(5), actions.get(1).tryParseBuffIds());}</li>
     *   <li>{@code assertTrue(actions.get(2).tryParseKillTrue());}</li>
     * </ul>
     */
    @Test
    @DisplayName("getResolvedActionsForSkill 应解析技能行为链")
    void getResolvedActionsForSkillShouldParseActions() {
        MazeSkillActionRepository repo = mock(MazeSkillActionRepository.class);
        when(repo.findBySkillId(1001)).thenReturn(List.of(
                BattleTestFixtures.skillActionRow(1, 1001, 1, 1, "{\"hp_change\":-150}"),
                BattleTestFixtures.skillActionRow(2, 1001, 2, 2, "{\"buff_id\":5}"),
                BattleTestFixtures.skillActionRow(3, 1001, 5, 3, "{\"kill\":true}")
        ));

        List<BattleContext.MazeSkillActionRuntime> actions =
                context.getResolvedActionsForSkill(1001, repo);

        assertEquals(3, actions.size());
        log.info("技能行为链解析: skillId=1001, actionCount={}, hpDelta={}, buffIds={}, kill={}",
                actions.size(),
                actions.get(0).tryParseHpDelta(),
                actions.get(1).tryParseBuffIds(),
                actions.get(2).tryParseKillTrue());
        assertEquals(-150, actions.get(0).tryParseHpDelta());
        assertEquals(List.of(5), actions.get(1).tryParseBuffIds());
        assertTrue(actions.get(2).tryParseKillTrue());
    }

    /**
     * 验证点：非法 skillId 或空行为应返回空列表。
     * <p>测试方法 {@code invalidSkillShouldReturnEmptyActions}：
     * <ul>
     *   <li>{@code when(repo.findBySkillId(2000)).thenReturn(List.of());}</li>
     *   <li>{@code assertTrue(sizeForZero == 0 && sizeForNegative == 0 && sizeForEmpty == 0);}</li>
     * </ul>
     */
    @Test
    @DisplayName("非法 skillId 或空行为应返回空列表")
    void invalidSkillShouldReturnEmptyActions() {
        MazeSkillActionRepository repo = mock(MazeSkillActionRepository.class);
        when(repo.findBySkillId(2000)).thenReturn(List.of());

        int sizeForZero = context.getResolvedActionsForSkill(0, repo).size();
        int sizeForNegative = context.getResolvedActionsForSkill(-1, repo).size();
        int sizeForEmpty = context.getResolvedActionsForSkill(2000, repo).size();

        log.info("非法技能校验: skillId=0 size={}, skillId=-1 size={}, skillId=2000 size={}",
                sizeForZero, sizeForNegative, sizeForEmpty);
        assertTrue(sizeForZero == 0 && sizeForNegative == 0 && sizeForEmpty == 0);
    }

    /**
     * 验证点：MazeSkillActionRuntime 应兼容多种 JSON 字段名。
     * <p>测试方法 {@code mazeSkillActionRuntimeShouldParseAlternateFieldNames}：
     * <ul>
     *   <li>{@code assertEquals(-80, hpAction.tryParseHpDelta());}</li>
     *   <li>{@code assertEquals(List.of(7, 8), buffAction.tryParseBuffIds());}</li>
     *   <li>{@code assertTrue(killAction.tryParseKillTrue());}</li>
     * </ul>
     */
    @Test
    @DisplayName("MazeSkillActionRuntime 应兼容多种 JSON 字段名")
    void mazeSkillActionRuntimeShouldParseAlternateFieldNames() throws Exception {
        BattleContext.MazeSkillActionRuntime hpAction = new BattleContext.MazeSkillActionRuntime(
                1, MAPPER.readTree("{\"hpChange\":-80}"));
        BattleContext.MazeSkillActionRuntime buffAction = new BattleContext.MazeSkillActionRuntime(
                2, MAPPER.readTree("{\"buffIds\":[7,8]}"));
        BattleContext.MazeSkillActionRuntime killAction = new BattleContext.MazeSkillActionRuntime(
                5, MAPPER.readTree("{\"dead\":\"1\"}"));

        assertEquals(-80, hpAction.tryParseHpDelta());
        assertEquals(List.of(7, 8), buffAction.tryParseBuffIds());
        assertTrue(killAction.tryParseKillTrue());
        log.info("多字段名解析: hpDelta={}, buffIds={}, kill={}",
                hpAction.tryParseHpDelta(), buffAction.tryParseBuffIds(), killAction.tryParseKillTrue());
    }

    /**
     * 验证点：非法 paramsJson 应被忽略而不中断流程。
     * <p>测试方法 {@code invalidParamsJsonShouldBeIgnored}：
     * <ul>
     *   <li>{@code when(repo.findBySkillId(3000)).thenReturn(List.of(}</li>
     *   <li>{@code assertEquals(1, actions.size());}</li>
     *   <li>{@code assertEquals(0, actions.get(0).tryParseHpDelta());}</li>
     * </ul>
     */
    @Test
    @DisplayName("非法 paramsJson 应被忽略而不中断流程")
    void invalidParamsJsonShouldBeIgnored() {
        MazeSkillActionRepository repo = mock(MazeSkillActionRepository.class);
        when(repo.findBySkillId(3000)).thenReturn(List.of(
                BattleTestFixtures.skillActionRow(1, 3000, 1, 1, "not-json")
        ));

        List<BattleContext.MazeSkillActionRuntime> actions =
                context.getResolvedActionsForSkill(3000, repo);
        assertEquals(1, actions.size());
        log.info("非法 JSON 容错: skillId=3000, actionCount={}, hpDelta={}",
                actions.size(), actions.get(0).tryParseHpDelta());
        assertEquals(0, actions.get(0).tryParseHpDelta());
    }

    /**
     * 验证点：setEnded 应更新战斗结束标记。
     * <p>测试方法 {@code setEndedShouldUpdateFlag}：
     * <ul>
     *   <li>{@code assertFalse(endedBefore);}</li>
     *   <li>{@code assertTrue(context.isEnded());}</li>
     * </ul>
     */
    @Test
    @DisplayName("setEnded 应更新战斗结束标记")
    void setEndedShouldUpdateFlag() {
        boolean endedBefore = context.isEnded();
        context.setEnded(true);
        log.info("结束标记校验: endedBefore={}, endedAfter={}", endedBefore, context.isEnded());
        assertFalse(endedBefore);
        assertTrue(context.isEnded());
    }
}
