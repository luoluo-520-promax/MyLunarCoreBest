package cn.itcast.demo.mylunarcore.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@DisplayName("BattleEventLoggingListener 战斗事件日志监听器测试")
class BattleEventLoggingListenerTest {

    private static final Logger log = LoggerFactory.getLogger(BattleEventLoggingListenerTest.class);

    @Test
    @DisplayName("onBattleStarted 应接受战斗开始事件")
    void onBattleStartedShouldAcceptEvent() {
        BattleEventLoggingListener listener = new BattleEventLoggingListener();
        BattleStartedEvent event = CommonTestFixtures.battleStartedEvent();

        listener.onBattleStarted(event);

        log.info("战斗开始监听校验: battleId={}, playerId={}, stageId={}, waveCount={}",
                event.battleId(), event.playerId(), event.battleStageId(), event.waveCount());
    }

    @Test
    @DisplayName("onBattleEnded 应接受战斗结束事件")
    void onBattleEndedShouldAcceptEvent() {
        BattleEventLoggingListener listener = new BattleEventLoggingListener();
        BattleEndedEvent event = CommonTestFixtures.battleEndedEvent();

        listener.onBattleEnded(event);

        log.info("战斗结束监听校验: battleId={}, playerId={}, endStatus={}, reason={}",
                event.battleId(), event.playerId(), event.endStatus(), event.reason());
    }
}
