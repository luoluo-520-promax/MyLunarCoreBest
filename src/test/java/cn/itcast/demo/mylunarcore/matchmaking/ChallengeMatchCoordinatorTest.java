package cn.itcast.demo.mylunarcore.matchmaking;

import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.player.PlayerSessionState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChallengeMatchCoordinator 挑战门禁测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code ChallengeMatchCoordinatorTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("ChallengeMatchCoordinator 挑战门禁测试")
class ChallengeMatchCoordinatorTest {

    private static final Logger log = LoggerFactory.getLogger(ChallengeMatchCoordinatorTest.class);

    private static final int PLAYER_ID = MatchmakingTestFixtures.PLAYER_A;

    private MatchmakingService matchmakingService;
    private RoomService roomService;
    private GameSessionManager sessionManager;
    private ChallengeMatchCoordinator coordinator;

    @BeforeEach
    void setUp() {
        matchmakingService = mock(MatchmakingService.class);
        roomService = mock(RoomService.class);
        sessionManager = mock(GameSessionManager.class);
        coordinator = new ChallengeMatchCoordinator(matchmakingService, roomService, sessionManager);
        log.info("挑战门禁初始化: playerId={}", PLAYER_ID);
    }

    /**
     * 验证点：不走匹配时应直接放行。
     * <p>测试方法 {@code ensureReadyShouldBypassWhenMatchmakingDisabled}：
     * <ul>
     *   <li>{@code assertTrue(result.ready());}</li>
     *   <li>{@code assertEquals(0, result.retcode());}</li>
     *   <li>{@code assertEquals(0, result.roomId());}</li>
     *   <li>{@code verify(matchmakingService, never()).joinQueue(anyInt(), anyInt(), anyInt(), anyInt());}</li>
     * </ul>
     */
    @Test
    @DisplayName("不走匹配时应直接放行")
    void ensureReadyShouldBypassWhenMatchmakingDisabled() {
        ChallengeMatchCoordinator.MatchGateResult result =
                coordinator.ensureReadyForChallenge(PLAYER_ID, false, 0);

        log.info("跳过匹配校验: useMatchmaking=false, ready={}, retcode={}, roomId={}",
                result.ready(), result.retcode(), result.roomId());
        assertTrue(result.ready());
        assertEquals(0, result.retcode());
        assertEquals(0, result.roomId());
        verify(matchmakingService, never()).joinQueue(anyInt(), anyInt(), anyInt(), anyInt());
    }

    /**
     * 验证点：指定房间校验失败应返回 retcode=10。
     * <p>测试方法 {@code ensureReadySpecifiedRoomShouldFailWhenInvalid}：
     * <ul>
     *   <li>{@code when(roomService.findRoomByPlayer(PLAYER_ID)).thenReturn(null);}</li>
     *   <li>{@code assertFalse(result.ready());}</li>
     *   <li>{@code assertEquals(10, result.retcode());}</li>
     *   <li>{@code assertEquals(88L, result.roomId());}</li>
     * </ul>
     */
    @Test
    @DisplayName("指定房间校验失败应返回 retcode=10")
    void ensureReadySpecifiedRoomShouldFailWhenInvalid() {
        when(roomService.findRoomByPlayer(PLAYER_ID)).thenReturn(null);

        ChallengeMatchCoordinator.MatchGateResult result =
                coordinator.ensureReadyForChallenge(PLAYER_ID, true, 88L);

        log.info("指定房间失败校验: requestRoomId=88, ready={}, retcode={}, roomId={}",
                result.ready(), result.retcode(), result.roomId());
        assertFalse(result.ready());
        assertEquals(10, result.retcode());
        assertEquals(88L, result.roomId());
    }

    /**
     * 验证点：指定房间全员准备应通过。
     * <p>测试方法 {@code ensureReadySpecifiedRoomShouldPassWhenReady}：
     * <ul>
     *   <li>{@code when(roomService.findRoomByPlayer(PLAYER_ID)).thenReturn(room);}</li>
     *   <li>{@code assertTrue(result.ready());}</li>
     *   <li>{@code assertEquals(0, result.retcode());}</li>
     *   <li>{@code assertEquals(88L, result.roomId());}</li>
     * </ul>
     */
    @Test
    @DisplayName("指定房间全员准备应通过")
    void ensureReadySpecifiedRoomShouldPassWhenReady() {
        RoomService.Room room = new RoomService.Room(
                88L, 1, 2,
                java.util.List.of(new RoomService.RoomMember(PLAYER_ID, true)));
        when(roomService.findRoomByPlayer(PLAYER_ID)).thenReturn(room);

        ChallengeMatchCoordinator.MatchGateResult result =
                coordinator.ensureReadyForChallenge(PLAYER_ID, true, 88L);

        log.info("指定房间通过校验: requestRoomId=88, ready={}, retcode={}, roomId={}, roomStatus={}",
                result.ready(), result.retcode(), result.roomId(), room.status());
        assertTrue(result.ready());
        assertEquals(0, result.retcode());
        assertEquals(88L, result.roomId());
    }

    /**
     * 验证点：自动匹配入队失败应返回 retcode=11。
     * <p>测试方法 {@code ensureReadyAutoMatchShouldFailWhenJoinFails}：
     * <ul>
     *   <li>{@code when(sessionManager.findByUid(PLAYER_ID)).thenReturn(Optional.empty());}</li>
     *   <li>{@code when(matchmakingService.joinQueue(PLAYER_ID, 1, 1, 0))}</li>
     *   <li>{@code assertFalse(result.ready());}</li>
     *   <li>{@code assertEquals(11, result.retcode());}</li>
     *   <li>{@code assertEquals(0, result.roomId());}</li>
     * </ul>
     */
    @Test
    @DisplayName("自动匹配入队失败应返回 retcode=11")
    void ensureReadyAutoMatchShouldFailWhenJoinFails() {
        when(sessionManager.findByUid(PLAYER_ID)).thenReturn(Optional.empty());
        when(matchmakingService.joinQueue(PLAYER_ID, 1, 1, 0))
                .thenReturn(new MatchmakingService.JoinResult(false, 0));

        ChallengeMatchCoordinator.MatchGateResult result =
                coordinator.ensureReadyForChallenge(PLAYER_ID, true, 0);

        log.info("入队失败校验: ready={}, retcode={}, roomId={}",
                result.ready(), result.retcode(), result.roomId());
        assertFalse(result.ready());
        assertEquals(11, result.retcode());
        assertEquals(0, result.roomId());
    }

    /**
     * 验证点：自动匹配排队中应返回 retcode=12。
     * <p>测试方法 {@code ensureReadyAutoMatchShouldReturnMatchingWhenNoRoom}：
     * <ul>
     *   <li>{@code when(sessionManager.findByUid(PLAYER_ID)).thenReturn(Optional.of(session));}</li>
     *   <li>{@code when(matchmakingService.joinQueue(PLAYER_ID, 1, 1, 0))}</li>
     *   <li>{@code when(roomService.findRoomByPlayer(PLAYER_ID)).thenReturn(null);}</li>
     *   <li>{@code assertFalse(result.ready());}</li>
     *   <li>{@code assertEquals(12, result.retcode());}</li>
     *   <li>{@code verify(session).setSessionState(PlayerSessionState.MATCHING);}</li>
     * </ul>
     */
    @Test
    @DisplayName("自动匹配排队中应返回 retcode=12")
    void ensureReadyAutoMatchShouldReturnMatchingWhenNoRoom() {
        GameSession session = mock(GameSession.class);
        when(sessionManager.findByUid(PLAYER_ID)).thenReturn(Optional.of(session));
        when(matchmakingService.joinQueue(PLAYER_ID, 1, 1, 0))
                .thenReturn(new MatchmakingService.JoinResult(true, 1));
        when(roomService.findRoomByPlayer(PLAYER_ID)).thenReturn(null);

        ChallengeMatchCoordinator.MatchGateResult result =
                coordinator.ensureReadyForChallenge(PLAYER_ID, true, 0);

        log.info("排队中校验: ready={}, retcode={}, roomId={}, sessionStateSet=MATCHING",
                result.ready(), result.retcode(), result.roomId());
        assertFalse(result.ready());
        assertEquals(12, result.retcode());
        verify(session).setSessionState(PlayerSessionState.MATCHING);
    }

    /**
     * 验证点：自动匹配已成局未全员 ready 应返回 retcode=13。
     * <p>测试方法 {@code ensureReadyAutoMatchShouldReturnNotReadyWhenStatusWaiting}：
     * <ul>
     *   <li>{@code when(sessionManager.findByUid(PLAYER_ID)).thenReturn(Optional.empty());}</li>
     *   <li>{@code when(matchmakingService.joinQueue(PLAYER_ID, 1, 1, 0))}</li>
     *   <li>{@code when(roomService.findRoomByPlayer(PLAYER_ID)).thenReturn(room);}</li>
     *   <li>{@code assertFalse(result.ready());}</li>
     *   <li>{@code assertEquals(13, result.retcode());}</li>
     *   <li>{@code assertEquals(55L, result.roomId());}</li>
     * </ul>
     */
    @Test
    @DisplayName("自动匹配已成局未全员 ready 应返回 retcode=13")
    void ensureReadyAutoMatchShouldReturnNotReadyWhenStatusWaiting() {
        when(sessionManager.findByUid(PLAYER_ID)).thenReturn(Optional.empty());
        when(matchmakingService.joinQueue(PLAYER_ID, 1, 1, 0))
                .thenReturn(new MatchmakingService.JoinResult(true, 2));
        RoomService.Room room = new RoomService.Room(
                55L, 1, 1,
                java.util.List.of(
                        new RoomService.RoomMember(PLAYER_ID, false),
                        new RoomService.RoomMember(MatchmakingTestFixtures.PLAYER_B, false)));
        when(roomService.findRoomByPlayer(PLAYER_ID)).thenReturn(room);

        ChallengeMatchCoordinator.MatchGateResult result =
                coordinator.ensureReadyForChallenge(PLAYER_ID, true, 0);

        log.info("未全员准备校验: ready={}, retcode={}, roomId={}, roomStatus={}",
                result.ready(), result.retcode(), result.roomId(), room.status());
        assertFalse(result.ready());
        assertEquals(13, result.retcode());
        assertEquals(55L, result.roomId());
    }

    /**
     * 验证点：自动匹配成局且全员 ready 应通过。
     * <p>测试方法 {@code ensureReadyAutoMatchShouldPassWhenRoomReady}：
     * <ul>
     *   <li>{@code when(sessionManager.findByUid(PLAYER_ID)).thenReturn(Optional.empty());}</li>
     *   <li>{@code when(matchmakingService.joinQueue(PLAYER_ID, 1, 1, 0))}</li>
     *   <li>{@code when(roomService.findRoomByPlayer(PLAYER_ID)).thenReturn(room);}</li>
     *   <li>{@code assertTrue(result.ready());}</li>
     *   <li>{@code assertEquals(0, result.retcode());}</li>
     *   <li>{@code assertEquals(66L, result.roomId());}</li>
     * </ul>
     */
    @Test
    @DisplayName("自动匹配成局且全员 ready 应通过")
    void ensureReadyAutoMatchShouldPassWhenRoomReady() {
        when(sessionManager.findByUid(PLAYER_ID)).thenReturn(Optional.empty());
        when(matchmakingService.joinQueue(PLAYER_ID, 1, 1, 0))
                .thenReturn(new MatchmakingService.JoinResult(true, 2));
        RoomService.Room room = new RoomService.Room(
                66L, 1, 2,
                java.util.List.of(
                        new RoomService.RoomMember(PLAYER_ID, true),
                        new RoomService.RoomMember(MatchmakingTestFixtures.PLAYER_B, true)));
        when(roomService.findRoomByPlayer(PLAYER_ID)).thenReturn(room);

        ChallengeMatchCoordinator.MatchGateResult result =
                coordinator.ensureReadyForChallenge(PLAYER_ID, true, 0);

        log.info("自动匹配通过校验: ready={}, retcode={}, roomId={}, roomStatus={}",
                result.ready(), result.retcode(), result.roomId(), room.status());
        assertTrue(result.ready());
        assertEquals(0, result.retcode());
        assertEquals(66L, result.roomId());
        verify(matchmakingService).joinQueue(eq(PLAYER_ID), eq(1), eq(1), eq(0));
        verify(roomService, never()).setReady(anyInt(), anyLong(), eq(true));
    }
}
