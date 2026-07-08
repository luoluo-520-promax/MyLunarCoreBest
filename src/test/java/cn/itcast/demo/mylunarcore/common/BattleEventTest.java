package cn.itcast.demo.mylunarcore.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("战斗生命周期事件测试")
class BattleEventTest {

    private static final Logger log = LoggerFactory.getLogger(BattleEventTest.class);

    @Test
    @DisplayName("BattleStartedEvent 应保存战斗开始快照")
    void battleStartedEventShouldExposeFields() {
        BattleStartedEvent event = CommonTestFixtures.battleStartedEvent();

        log.info("战斗开始事件校验: battleId={}, playerId={}, stageId={}, lineupId={}, waveCount={}, startTime={}",
                event.battleId(), event.playerId(), event.battleStageId(),
                event.lineupId(), event.waveCount(), event.startTimeSeconds());
        assertEquals(9001L, event.battleId());
        assertEquals(77, event.playerId());
        assertEquals(1001, event.battleStageId());
        assertEquals(1, event.lineupId());
        assertEquals(2, event.waveCount());
        assertEquals(1_700_000_000L, event.startTimeSeconds());
    }

    @Test
    @DisplayName("BattleEndedEvent 应保存战斗结束快照")
    void battleEndedEventShouldExposeFields() {
        BattleEndedEvent event = CommonTestFixtures.battleEndedEvent();

        log.info("战斗结束事件校验: battleId={}, playerId={}, endStatus={}, reason={}, endTime={}",
                event.battleId(), event.playerId(), event.endStatus(),
                event.reason(), event.endTimeSeconds());
        assertEquals(9001L, event.battleId());
        assertEquals(77, event.playerId());
        assertEquals(1, event.endStatus());
        assertEquals("victory", event.reason());
        assertEquals(1_700_000_100L, event.endTimeSeconds());
    }
}
