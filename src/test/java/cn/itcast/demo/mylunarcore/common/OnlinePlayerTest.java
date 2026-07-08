package cn.itcast.demo.mylunarcore.common;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.player.PlayerDataAsyncLoadService;
import cn.itcast.demo.mylunarcore.player.PlayerDataPeriodicPersistenceService;
import cn.itcast.demo.mylunarcore.player.SyncReason;
import cn.itcast.demo.mylunarcore.scene.SceneContext;
import cn.itcast.demo.mylunarcore.scene.SceneManager;
import io.netty.channel.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("OnlinePlayer 在线玩家 Tick 测试")
class OnlinePlayerTest {

    private static final Logger log = LoggerFactory.getLogger(OnlinePlayerTest.class);

    private GameSessionManager sessionManager;
    private SceneManager sceneManager;
    private PlayerDataAsyncLoadService asyncLoadService;
    private PlayerDataPeriodicPersistenceService persistenceService;
    private LunarCoreProperties properties;
    private OnlinePlayer onlinePlayer;

    @BeforeEach
    void setUp() {
        sessionManager = mock(GameSessionManager.class);
        sceneManager = mock(SceneManager.class);
        asyncLoadService = mock(PlayerDataAsyncLoadService.class);
        persistenceService = mock(PlayerDataPeriodicPersistenceService.class);
        properties = CommonTestFixtures.defaultProperties();
        properties.getSync().setPassiveIntervalMs(0L);
        when(persistenceService.intervalMs()).thenReturn(0L);

        onlinePlayer = new OnlinePlayer(
                CommonTestFixtures.PLAYER_UID,
                sessionManager,
                sceneManager,
                asyncLoadService,
                persistenceService,
                properties);
        log.info("在线玩家初始化: uid={}, passiveIntervalMs={}, persistIntervalMs={}",
                onlinePlayer.getUid(),
                properties.getSync().getPassiveIntervalMs(),
                persistenceService.intervalMs());
    }

    @Test
    @DisplayName("会话不存在时 onTick 应直接返回")
    void onTickWithoutSessionShouldReturnEarly() {
        when(sessionManager.getOrNull(CommonTestFixtures.PLAYER_UID)).thenReturn(null);

        onlinePlayer.onTick(System.currentTimeMillis(), 100L);

        log.info("无会话 Tick 校验: uid={}, sessionPresent=false", CommonTestFixtures.PLAYER_UID);
        verify(asyncLoadService, never()).reloadFullAsync(eq(CommonTestFixtures.PLAYER_UID), eq(SyncReason.TIMER));
        verify(sceneManager, never()).getByPlayerUid(CommonTestFixtures.PLAYER_UID);
    }

    @Test
    @DisplayName("会话存在时应转发场景 Tick")
    void onTickWithSessionShouldForwardSceneTick() {
        GameSession session = new GameSession(CommonTestFixtures.PLAYER_UID, mock(Channel.class), null);
        SceneContext scene = mock(SceneContext.class);
        when(sessionManager.getOrNull(CommonTestFixtures.PLAYER_UID)).thenReturn(session);
        when(sceneManager.getByPlayerUid(CommonTestFixtures.PLAYER_UID)).thenReturn(scene);

        long now = System.currentTimeMillis();
        onlinePlayer.onTick(now, 50L);

        log.info("场景 Tick 转发校验: uid={}, nowMillis={}, deltaMillis={}",
                CommonTestFixtures.PLAYER_UID, now, 50L);
        verify(scene).onTick(now, 50L);
    }

    @Test
    @DisplayName("被动同步间隔到达时应触发异步全量加载")
    void onTickShouldTriggerPassiveSyncWhenIntervalElapsed() {
        properties.getSync().setPassiveIntervalMs(100L);
        OnlinePlayer syncPlayer = new OnlinePlayer(
                CommonTestFixtures.PLAYER_UID,
                sessionManager,
                sceneManager,
                asyncLoadService,
                persistenceService,
                properties);
        GameSession session = new GameSession(CommonTestFixtures.PLAYER_UID, mock(Channel.class), null);
        when(sessionManager.getOrNull(CommonTestFixtures.PLAYER_UID)).thenReturn(session);
        when(sceneManager.getByPlayerUid(CommonTestFixtures.PLAYER_UID)).thenReturn(null);

        long now = System.currentTimeMillis() + 200L;
        syncPlayer.onTick(now, 50L);

        log.info("被动同步校验: uid={}, passiveIntervalMs={}, syncReason={}, tickAt={}",
                CommonTestFixtures.PLAYER_UID, properties.getSync().getPassiveIntervalMs(), SyncReason.TIMER, now);
        verify(asyncLoadService).reloadFullAsync(CommonTestFixtures.PLAYER_UID, SyncReason.TIMER);
    }
}
