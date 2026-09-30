package cn.itcast.demo.mylunarcore.player; // 主流程：开始游戏 / 返回主界面 单测

import cn.itcast.demo.mylunarcore.protocol.PlayerSessionProto;
import cn.itcast.demo.mylunarcore.protocol.SceneSystemProto;
import cn.itcast.demo.mylunarcore.scene.SceneManager;
import cn.itcast.demo.mylunarcore.scene.SceneNettyService;
import io.netty.channel.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 覆盖主界面 → 开始游戏 → 返回主界面的核心 retcode 与状态迁移。
 */
@DisplayName("GameFlowNettyService 主界面与开始/退出流程")
class GameFlowNettyServiceTest {

    private static final long UID = 1001L;

    private PlayerContextResolver contextResolver;
    private GameSessionManager sessionManager;
    private SceneManager sceneManager;
    private SceneNettyService sceneNettyService;
    private PlaySessionCleanupService playSessionCleanupService;
    private GameFlowNettyService service;
    private GameSession session;
    private Channel channel;

    @BeforeEach
    void setUp() {
        contextResolver = mock(PlayerContextResolver.class);
        sessionManager = mock(GameSessionManager.class);
        sceneManager = mock(SceneManager.class);
        sceneNettyService = mock(SceneNettyService.class);
        playSessionCleanupService = mock(PlaySessionCleanupService.class);
        service = new GameFlowNettyService(
                contextResolver, sessionManager, sceneManager, sceneNettyService, playSessionCleanupService);
        session = new GameSession(UID, mock(Channel.class), null);
        session.setSessionState(PlayerSessionState.HALL);
        channel = mock(Channel.class);
        when(contextResolver.resolveUid(any())).thenReturn(OptionalLong.of(UID));
        when(sessionManager.getOrNull(UID)).thenReturn(session);
    }

    @Test
    @DisplayName("未登录开始游戏应返回 retcode=1")
    void startGameWithoutLoginShouldFail() {
        when(contextResolver.resolveUid(any())).thenReturn(OptionalLong.empty());
        PlayerSessionProto.StartGameScRsp rsp = service.handleStartGame(
                PlayerSessionProto.StartGameCsReq.getDefaultInstance(), channel);
        assertEquals(GameFlowNettyService.RET_NOT_LOGIN, rsp.getRetcode());
        verify(sceneNettyService, never()).handleEnterScene(any(), any());
    }

    @Test
    @DisplayName("主界面开始游戏成功应进入场景并回传 SCENE 状态")
    void startGameFromHallShouldEnterScene() {
        when(sceneNettyService.handleEnterScene(any(), any())).thenAnswer(inv -> {
            session.setSessionState(PlayerSessionState.SCENE); // 模拟 SceneNettyService 切状态
            return SceneSystemProto.EnterSceneScRsp.newBuilder()
                    .setRetcode(0)
                    .setSceneInfo(SceneSystemProto.SceneLoadInfo.newBuilder()
                            .setPlaneId(10001).setFloorId(1).setEntryId(0)
                            .setPosX(10).setPosY(0).setPosZ(20).build())
                    .build();
        });

        PlayerSessionProto.StartGameScRsp rsp = service.handleStartGame(
                PlayerSessionProto.StartGameCsReq.newBuilder().setPlaneId(10001).setFloorId(1).build(),
                channel);

        assertEquals(GameFlowNettyService.RET_OK, rsp.getRetcode());
        assertEquals(1, rsp.getSessionState()); // SCENE
        assertEquals(10001, rsp.getPlaneId());
        verify(sceneNettyService).handleEnterScene(any(), any());
    }

    @Test
    @DisplayName("战斗中直接开始游戏应拒绝（需先回主界面）")
    void startGameFromBattleShouldFail() {
        session.setSessionState(PlayerSessionState.BATTLE);
        PlayerSessionProto.StartGameScRsp rsp = service.handleStartGame(
                PlayerSessionProto.StartGameCsReq.newBuilder().setPlaneId(10001).build(), channel);
        assertEquals(GameFlowNettyService.RET_NOT_IN_HALL, rsp.getRetcode());
        verify(sceneNettyService, never()).handleEnterScene(any(), any());
    }

    @Test
    @DisplayName("返回主界面应清理玩法并把状态置为 HALL")
    void returnMainMenuShouldCleanupAndSetHall() {
        session.setSessionState(PlayerSessionState.SCENE);
        PlayerSessionProto.ReturnMainMenuScRsp rsp = service.handleReturnMainMenu(
                PlayerSessionProto.ReturnMainMenuCsReq.newBuilder().setReason(0).build(), channel);
        assertEquals(GameFlowNettyService.RET_OK, rsp.getRetcode());
        assertEquals(0, rsp.getSessionState()); // HALL
        assertEquals(PlayerSessionState.HALL, session.getSessionState());
        verify(playSessionCleanupService).leaveCurrentPlay(UID);
    }

    @Test
    @DisplayName("已在主界面返回主界面应幂等成功")
    void returnMainMenuWhenAlreadyHallShouldSucceed() {
        session.setSessionState(PlayerSessionState.HALL);
        PlayerSessionProto.ReturnMainMenuScRsp rsp = service.handleReturnMainMenu(
                PlayerSessionProto.ReturnMainMenuCsReq.getDefaultInstance(), channel);
        assertEquals(GameFlowNettyService.RET_OK, rsp.getRetcode());
        verify(playSessionCleanupService, never()).leaveCurrentPlay(UID);
    }
}
