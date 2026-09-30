package cn.itcast.demo.mylunarcore.player;

import cn.itcast.demo.mylunarcore.model.PlayerData;
import cn.itcast.demo.mylunarcore.protocol.PlayerSessionProto;
import io.netty.channel.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PlayerSyncCoordinator 统一同步协调器测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code PlayerSyncCoordinatorTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("PlayerSyncCoordinator 统一同步协调器测试")
class PlayerSyncCoordinatorTest {

    private static final Logger log = LoggerFactory.getLogger(PlayerSyncCoordinatorTest.class);

    private PlayerCoreSyncable coreSyncable;
    private PlayerSyncCoordinator coordinator;

    @BeforeEach
    void setUp() {
        coreSyncable = new PlayerCoreSyncable();
        coordinator = new PlayerSyncCoordinator(List.of(coreSyncable));
        log.info("协调器初始化: syncableCount=1");
    }

    /**
     * 验证点：build 应写入同步原因与核心字段。
     * <p>测试方法 {@code buildShouldPopulateReasonAndCoreFields}：
     * <ul>
     *   <li>{@code assertEquals(SyncReason.LOGIN.getCode(), notify.getSyncReason());}</li>
     *   <li>{@code assertTrue(notify.getServerTime() > 0);}</li>
     *   <li>{@code assertEquals(45, notify.getLevel());}</li>
     *   <li>{@code assertEquals(180, notify.getStamina());}</li>
     *   <li>{@code assertEquals(PlayerTestFixtures.NICKNAME, notify.getNickname());}</li>
     *   <li>{@code assertEquals(1000, notify.getCurrencyOrDefault(1, 0));}</li>
     * </ul>
     */
    @Test
    @DisplayName("build 应写入同步原因与核心字段")
    void buildShouldPopulateReasonAndCoreFields() {
        PlayerData data = PlayerTestFixtures.playerData(PlayerTestFixtures.PLAYER_UID);

        PlayerSessionProto.PlayerUnifiedSyncScNotify notify =
                coordinator.build(data, SyncReason.LOGIN);

        log.info("组包校验: syncReason={}, level={}, stamina={}, nickname={}, currencySize={}",
                notify.getSyncReason(), notify.getLevel(), notify.getStamina(),
                notify.getNickname(), notify.getCurrencyCount());
        assertEquals(SyncReason.LOGIN.getCode(), notify.getSyncReason());
        assertTrue(notify.getServerTime() > 0);
        assertEquals(45, notify.getLevel());
        assertEquals(180, notify.getStamina());
        assertEquals(PlayerTestFixtures.NICKNAME, notify.getNickname());
        assertEquals(1000, notify.getCurrencyOrDefault(1, 0));
        assertEquals(50, notify.getCurrencyOrDefault(2, 0));
    }

    /**
     * 验证点：data 为 null 时仅填充元信息。
     * <p>测试方法 {@code buildWithNullDataShouldOnlySetMetadata}：
     * <ul>
     *   <li>{@code assertEquals(SyncReason.DATA_CHANGE.getCode(), notify.getSyncReason());}</li>
     *   <li>{@code assertEquals(0, notify.getLevel());}</li>
     *   <li>{@code assertEquals(0, notify.getCurrencyCount());}</li>
     * </ul>
     */
    @Test
    @DisplayName("data 为 null 时仅填充元信息")
    void buildWithNullDataShouldOnlySetMetadata() {
        PlayerSessionProto.PlayerUnifiedSyncScNotify notify =
                coordinator.build(null, SyncReason.DATA_CHANGE);

        log.info("空数据组包: syncReason={}, level={}, currencySize={}",
                notify.getSyncReason(), notify.getLevel(), notify.getCurrencyCount());
        assertEquals(SyncReason.DATA_CHANGE.getCode(), notify.getSyncReason());
        assertEquals(0, notify.getLevel());
        assertEquals(0, notify.getCurrencyCount());
    }

    /**
     * 验证点：pushToSession 在 session 为 null 时应静默忽略。
     * <p>测试方法 {@code pushToSessionWithNullShouldNoOp}：
     * 按用例准备数据后断言返回值或协作对象调用是否符合预期。
     */
    @Test
    @DisplayName("pushToSession 在 session 为 null 时应静默忽略")
    void pushToSessionWithNullShouldNoOp() {
        coordinator.pushToSession(null, PlayerTestFixtures.playerData(PlayerTestFixtures.PLAYER_UID), SyncReason.TIMER);
        log.info("空会话推送校验: session=null, pushed=false");
    }

    /**
     * 验证点：pushToSession 应通过会话下发同步包。
     * <p>测试方法 {@code pushToSessionShouldSendViaChannel}：
     * <ul>
     *   <li>{@code when(channel.writeAndFlush(any())).thenReturn(null);}</li>
     *   <li>{@code verify(channel).writeAndFlush(any());}</li>
     * </ul>
     */
    @Test
    @DisplayName("pushToSession 应通过会话下发同步包")
    void pushToSessionShouldSendViaChannel() {
        Channel channel = mock(Channel.class);
        when(channel.writeAndFlush(any())).thenReturn(null);
        GameSession session = new GameSession(PlayerTestFixtures.PLAYER_UID, channel, null);

        coordinator.pushToSession(session, PlayerTestFixtures.playerData(PlayerTestFixtures.PLAYER_UID), SyncReason.TIMER);

        log.info("会话推送校验: uid={}, syncReason={}", session.getUid(), SyncReason.TIMER.getCode());
        verify(channel).writeAndFlush(any());
    }
}
