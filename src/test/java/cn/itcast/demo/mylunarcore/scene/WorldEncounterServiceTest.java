package cn.itcast.demo.mylunarcore.scene;

import cn.itcast.demo.mylunarcore.battle.BattleManager;
import cn.itcast.demo.mylunarcore.battle.BattleNettyService;
import cn.itcast.demo.mylunarcore.battle.BattleSnapshotService;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.player.PlayerSessionState;
import cn.itcast.demo.mylunarcore.protocol.BattleSystemProto;
import io.netty.channel.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * WorldEncounterService 遭遇引擎测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code WorldEncounterServiceTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("WorldEncounterService 遭遇引擎测试")
class WorldEncounterServiceTest {

    private ZoneManager zoneManager;
    private GameSessionManager sessionManager;
    private BattleManager battleManager;
    private BattleNettyService battleNettyService;
    private SceneManager sceneManager;
    private WorldEncounterService service;

    @BeforeEach
    void setUp() {
        zoneManager = mock(ZoneManager.class);
        sessionManager = mock(GameSessionManager.class);
        battleManager = new BattleManager(org.mockito.Mockito.mock(BattleSnapshotService.class));
        battleNettyService = mock(BattleNettyService.class);
        sceneManager = mock(SceneManager.class);
        service = new WorldEncounterService(
                zoneManager, sessionManager, battleManager, battleNettyService, sceneManager);
    }

    /**
     * 验证点：主动怪进入仇恨范围应自动开战。
     * <p>测试方法 {@code aggressiveMonsterInRangeShouldStartFight}：
     * <ul>
     *   <li>{@code when(zoneManager.get(scene.getZoneId())).thenReturn(zone);}</li>
     *   <li>{@code when(sessionManager.getOrNull(77L)).thenReturn(session);}</li>
     *   <li>{@code when(battleNettyService.handleFightStart(any(), any())).thenReturn(}</li>
     *   <li>{@code verify(battleNettyService).handleFightStart(any(), any());}</li>
     *   <li>{@code verify(channel).writeAndFlush(any());}</li>
     * </ul>
     */
    @Test
    @DisplayName("主动怪进入仇恨范围应自动开战")
    void aggressiveMonsterInRangeShouldStartFight() {
        SceneContext scene = new SceneContext(77L, 1, 1, 1, new SceneContext.ScenePos(0f, 0f, 0f));
        scene.setInitialized(true);
        ZoneContext zone = new ZoneContext(scene.getZoneId(), 1, 1);
        ZoneContext.ZoneMonster monster = new ZoneContext.ZoneMonster(
                1001, 101, 5, 100, 100, new SceneContext.ScenePos(1f, 0f, 1f), java.util.List.of());
        monster.setAggressive(true);
        monster.setAggroRadius(5f);
        zone.putMonster(monster);
        when(zoneManager.get(scene.getZoneId())).thenReturn(zone);

        Channel channel = mock(Channel.class);
        GameSession session = new GameSession(77L, channel, null);
        session.setSessionState(PlayerSessionState.SCENE);
        when(sessionManager.getOrNull(77L)).thenReturn(session);
        when(battleNettyService.handleFightStart(any(), any())).thenReturn(
                BattleSystemProto.FightStartScRsp.newBuilder().setRetcode(0).setBattleId(9).build());

        service.onSceneTick(scene, System.currentTimeMillis());

        verify(battleNettyService).handleFightStart(any(), any());
        verify(channel).writeAndFlush(any());
    }

    /**
     * 验证点：ZoneTick 应对区内玩家做仇恨判定。
     * <p>测试方法 {@code zoneTickShouldScanPlayers}：
     * <ul>
     *   <li>{@code when(sceneManager.getByPlayerUid(77L)).thenReturn(scene);}</li>
     *   <li>{@code when(sessionManager.getOrNull(77L)).thenReturn(session);}</li>
     *   <li>{@code when(battleNettyService.handleFightStart(any(), any())).thenReturn(}</li>
     *   <li>{@code verify(battleNettyService).handleFightStart(any(), any());}</li>
     * </ul>
     */
    @Test
    @DisplayName("ZoneTick 应对区内玩家做仇恨判定")
    void zoneTickShouldScanPlayers() {
        SceneContext scene = new SceneContext(77L, 1, 1, 1, new SceneContext.ScenePos(0f, 0f, 0f));
        scene.setInitialized(true);
        ZoneContext zone = new ZoneContext(scene.getZoneId(), 1, 1);
        zone.addPlayer(77L, scene.getPlayerPos());
        ZoneContext.ZoneMonster monster = new ZoneContext.ZoneMonster(
                1001, 101, 5, 100, 100, new SceneContext.ScenePos(1f, 0f, 1f), java.util.List.of());
        monster.setAggressive(true);
        monster.setAggroRadius(5f);
        zone.putMonster(monster);
        when(sceneManager.getByPlayerUid(77L)).thenReturn(scene);
        Channel channel = mock(Channel.class);
        GameSession session = new GameSession(77L, channel, null);
        session.setSessionState(PlayerSessionState.SCENE);
        when(sessionManager.getOrNull(77L)).thenReturn(session);
        when(battleNettyService.handleFightStart(any(), any())).thenReturn(
                BattleSystemProto.FightStartScRsp.newBuilder().setRetcode(0).setBattleId(9).build());

        service.onZoneTick(zone, System.currentTimeMillis());
        verify(battleNettyService).handleFightStart(any(), any());
    }

    /**
     * 验证点：非 SCENE 状态不应开战。
     * <p>测试方法 {@code nonSceneStateShouldSkip}：
     * <ul>
     *   <li>{@code when(zoneManager.get(scene.getZoneId())).thenReturn(zone);}</li>
     *   <li>{@code when(sessionManager.getOrNull(77L)).thenReturn(session);}</li>
     *   <li>{@code verify(battleNettyService, never()).handleFightStart(any(), any());}</li>
     * </ul>
     */
    @Test
    @DisplayName("非 SCENE 状态不应开战")
    void nonSceneStateShouldSkip() {
        SceneContext scene = new SceneContext(77L, 1, 1, 1, new SceneContext.ScenePos(0f, 0f, 0f));
        scene.setInitialized(true);
        ZoneContext zone = new ZoneContext(scene.getZoneId(), 1, 1);
        when(zoneManager.get(scene.getZoneId())).thenReturn(zone);
        GameSession session = new GameSession(77L, mock(Channel.class), null);
        session.setSessionState(PlayerSessionState.BATTLE);
        when(sessionManager.getOrNull(77L)).thenReturn(session);

        service.onSceneTick(scene, System.currentTimeMillis());
        verify(battleNettyService, never()).handleFightStart(any(), any());
    }
}
