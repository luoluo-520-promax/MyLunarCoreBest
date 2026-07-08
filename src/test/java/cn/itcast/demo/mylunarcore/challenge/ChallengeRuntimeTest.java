package cn.itcast.demo.mylunarcore.challenge;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("ChallengeRuntime 挑战运行时状态测试")
class ChallengeRuntimeTest {

    private static final Logger log = LoggerFactory.getLogger(ChallengeRuntimeTest.class);

    private static final long CHALLENGE_UID = 10_001L;
    private static final int PLAYER_ID = 77;
    private static final int CHALLENGE_ID = 1500;

    private ChallengeRuntime runtime;

    @BeforeEach
    void setUp() {
        runtime = ChallengeTestFixtures.createRuntime(CHALLENGE_UID, PLAYER_ID, CHALLENGE_ID);
        log.info("挑战运行时初始化: challengeUid={}, playerId={}, challengeId={}, groupId={}, stageId={}, status={}, waveCount={}",
                runtime.getChallengeUid(), runtime.getPlayerId(), runtime.getChallengeId(),
                runtime.getGroupId(), runtime.getStageId(), runtime.getStatus(), runtime.getWaveCount());
    }

    @Test
    @DisplayName("构造后应处于进行中且得分与星级为 0")
    void constructorShouldInitializeDefaults() {
        log.info("初始状态校验: status={}, currentStage={}, roundsUsed={}, currentScore={}, currentStarsMask={}, enemyWaveCount={}",
                runtime.getStatus(), runtime.getCurrentStage(), runtime.getRoundsUsed(),
                runtime.getCurrentScore(), runtime.getCurrentStarsMask(), runtime.getEnemyInfo().size());
        assertEquals(1, runtime.getStatus());
        assertEquals(1, runtime.getCurrentStage());
        assertEquals(0, runtime.getRoundsUsed());
        assertEquals(0, runtime.getCurrentScore());
        assertEquals(0, runtime.getCurrentStarsMask());
        assertEquals(2, runtime.getEnemyInfo().size());
        assertEquals(100, runtime.getGroupId());
        assertEquals(2500, runtime.getStageId());
    }

    @Test
    @DisplayName("markSettled 胜利应写入分数、星级与状态 2")
    void markSettledWinShouldUpdateFields() {
        runtime.markSettled(true, 3200, 0b111, 8);
        log.info("胜利结算校验: status={}, currentScore={}, currentStarsMask={}, roundsUsed={}",
                runtime.getStatus(), runtime.getCurrentScore(), runtime.getCurrentStarsMask(), runtime.getRoundsUsed());
        assertEquals(2, runtime.getStatus());
        assertEquals(3200, runtime.getCurrentScore());
        assertEquals(0b111, runtime.getCurrentStarsMask());
        assertEquals(8, runtime.getRoundsUsed());
    }

    @Test
    @DisplayName("markSettled 失败应写入状态 3 且分数不低于 0")
    void markSettledLossShouldClampScore() {
        runtime.markSettled(false, -50, 0, -3);
        log.info("失败结算校验: status={}, currentScore={}, currentStarsMask={}, roundsUsed={}",
                runtime.getStatus(), runtime.getCurrentScore(), runtime.getCurrentStarsMask(), runtime.getRoundsUsed());
        assertEquals(3, runtime.getStatus());
        assertEquals(0, runtime.getCurrentScore());
        assertEquals(0, runtime.getCurrentStarsMask());
        assertEquals(0, runtime.getRoundsUsed());
    }
}
