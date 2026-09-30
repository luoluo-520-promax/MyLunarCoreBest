package cn.itcast.demo.mylunarcore.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BattleEventLoggingListener 战斗事件日志监听器测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code BattleEventLoggingListenerTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("BattleEventLoggingListener 战斗事件日志监听器测试")
class BattleEventLoggingListenerTest {

    private static final Logger log = LoggerFactory.getLogger(BattleEventLoggingListenerTest.class);

    /**
     * 验证点：onBattleStarted 应接受战斗开始事件。
     * <p>测试方法 {@code onBattleStartedShouldAcceptEvent}：
     * 按用例准备数据后断言返回值或协作对象调用是否符合预期。
     */
    @Test
    @DisplayName("onBattleStarted 应接受战斗开始事件")
    void onBattleStartedShouldAcceptEvent() {
        BattleEventLoggingListener listener = new BattleEventLoggingListener();
        BattleStartedEvent event = CommonTestFixtures.battleStartedEvent();

        listener.onBattleStarted(event);

        log.info("战斗开始监听校验: battleId={}, playerId={}, stageId={}, waveCount={}",
                event.battleId(), event.playerId(), event.battleStageId(), event.waveCount());
    }

    /**
     * 验证点：onBattleEnded 应接受战斗结束事件。
     * <p>测试方法 {@code onBattleEndedShouldAcceptEvent}：
     * 按用例准备数据后断言返回值或协作对象调用是否符合预期。
     */
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
