package cn.itcast.demo.mylunarcore.common;

import cn.itcast.demo.mylunarcore.center.OnlinePresenceService;
import cn.itcast.demo.mylunarcore.center.SceneRegistry;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.scene.ZoneManager;
import cn.itcast.demo.mylunarcore.scene.ZoneTickService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GameServer 游戏主循环测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code GameServerTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
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
        properties.getGameLoop().setZonePeriodMs(0);
        properties.getGameLoop().setBattlePeriodMs(0);
        playerTickRegistry = new PlayerTickRegistry();
        activityScheduleService = mock(ActivityScheduleService.class);
        gameServer = new GameServer(properties, playerTickRegistry, activityScheduleService,
                new cn.itcast.demo.mylunarcore.battle.BattleManager(
                        org.mockito.Mockito.mock(cn.itcast.demo.mylunarcore.battle.BattleSnapshotService.class)),
                mock(ZoneTickService.class),
                new SceneRegistry(),
                mock(ZoneManager.class),
                new OnlinePresenceService(),
                new BusinessMetrics(new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
        log.info("GameServer 初始化: gameLoopEnabled={}, periodMs={}",
                properties.getGameLoop().isEnabled(), properties.getGameLoop().getPeriodMs());
    }

    /**
     * 验证点：onGlobalTick 应遍历在线玩家并 Tick 活动排期。
     * <p>测试方法 {@code onGlobalTickShouldDrivePlayersAndActivitySchedule}：
     * <ul>
     *   <li>{@code when(player.getUid()).thenReturn(CommonTestFixtures.PLAYER_UID);}</li>
     *   <li>{@code verify(player).onTick(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());}</li>
     *   <li>{@code verify(activityScheduleService).onTick(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());}</li>
     * </ul>
     */
    @Test
    @DisplayName("onGlobalTick 应遍历在线玩家并 Tick 活动排期")
    void onGlobalTickShouldDrivePlayersAndActivitySchedule() {
        OnlinePlayer player = mock(OnlinePlayer.class);
        when(player.getUid()).thenReturn(CommonTestFixtures.PLAYER_UID);
        playerTickRegistry.register(player);

        gameServer.onGlobalTick(System.currentTimeMillis(), 1000L);

        log.info("主循环 Tick 校验: registeredUid={}, activityTickInvoked=true",
                CommonTestFixtures.PLAYER_UID);
        verify(player).onTick(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
        verify(activityScheduleService).onTick(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong());
    }
}
