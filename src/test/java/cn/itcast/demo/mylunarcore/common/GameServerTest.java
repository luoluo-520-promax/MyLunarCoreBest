package cn.itcast.demo.mylunarcore.common;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("GameServer 游戏主循环测试")
class GameServerTest {

    private static final Logger log = LoggerFactory.getLogger(GameServerTest.class);

    private PlayerTickRegistry playerTickRegistry;
    private ActivityScheduleService activityScheduleService;
    private GameServer gameServer;

    @BeforeEach
    void setUp() {
        LunarCoreProperties properties = CommonTestFixtures.defaultProperties();
        properties.getGameLoop().setEnabled(false);
        playerTickRegistry = new PlayerTickRegistry();
        activityScheduleService = mock(ActivityScheduleService.class);
        gameServer = new GameServer(properties, playerTickRegistry, activityScheduleService);
        log.info("GameServer 初始化: gameLoopEnabled={}, periodMs={}",
                properties.getGameLoop().isEnabled(), properties.getGameLoop().getPeriodMs());
    }

    @Test
    @DisplayName("onTick 应遍历在线玩家并 Tick 活动排期")
    void onTickShouldDrivePlayersAndActivitySchedule() {
        OnlinePlayer player = mock(OnlinePlayer.class);
        when(player.getUid()).thenReturn(CommonTestFixtures.PLAYER_UID);
        playerTickRegistry.register(player);

        gameServer.onTick();

        log.info("主循环 Tick 校验: registeredUid={}, activityTickInvoked=true",
                CommonTestFixtures.PLAYER_UID);
        verify(player).onTick(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
        verify(activityScheduleService).onTick(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
    }
}
