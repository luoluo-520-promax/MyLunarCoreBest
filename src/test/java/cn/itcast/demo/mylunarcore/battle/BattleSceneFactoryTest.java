package cn.itcast.demo.mylunarcore.battle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * {@link BattleSceneFactory#createBattleScene}：按 battleId/player/lineup/stage/时间戳与关卡配置装配上下文。
 */
@DisplayName("BattleSceneFactory 战斗场景工厂测试")
class BattleSceneFactoryTest {

    private static final Logger log = LoggerFactory.getLogger(BattleSceneFactoryTest.class);

    /**
     * twoWaveStage 装配后：波次数=2、当前波=1、未结束，且存在玩家实体 88；
     * 入参 battleId/lineupId/stageId 原样写入上下文。
     */
    @Test
    @DisplayName("createBattleScene 应返回可用的 BattleContext")
    void createBattleSceneShouldBuildContext() {
        BattleSceneFactory factory = new BattleSceneFactory();
        BattleContext context = factory.createBattleScene(
                5001L, 88, 2, 300, 1_700_000_100L, BattleTestFixtures.twoWaveStage());

        log.info("战局装配校验: battleId={}, playerId={}, lineupId={}, stageId={}, waveCount={}, currentWave={}, ended={}, playerEntityExists={}",
                context.getBattleId(), context.getPlayerId(), context.getLineupId(),
                context.getBattleStageId(), context.getWaveCount(), context.getCurrentWave(),
                context.isEnded(), context.getEntity(88) != null);
        assertEquals(5001L, context.getBattleId());
        assertEquals(88, context.getPlayerId());
        assertEquals(2, context.getLineupId());
        assertEquals(300, context.getBattleStageId());
        assertEquals(2, context.getWaveCount());
        assertEquals(1, context.getCurrentWave());
        assertFalse(context.isEnded());
        assertNotNull(context.getEntity(88));
    }
}
