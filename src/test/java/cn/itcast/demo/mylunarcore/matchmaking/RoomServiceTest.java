package cn.itcast.demo.mylunarcore.matchmaking;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RoomService 房间状态测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code RoomServiceTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("RoomService 房间状态测试")
class RoomServiceTest {

    private static final Logger log = LoggerFactory.getLogger(RoomServiceTest.class);

    private static final int MODE = MatchmakingTestFixtures.MODE;

    private RoomService roomService;

    @BeforeEach
    void setUp() {
        roomService = new RoomService();
        log.info("房间服务初始化: mode={}, defaultStatus=1", MODE);
    }

    /**
     * 验证点：createRoom 应创建 status=1 且全员未准备的房间。
     * <p>测试方法 {@code createRoomShouldInitWaitingRoom}：
     * <ul>
     *   <li>{@code assertEquals(MODE, room.mode());}</li>
     *   <li>{@code assertEquals(1, room.status());}</li>
     *   <li>{@code assertEquals(2, room.members().size());}</li>
     *   <li>{@code assertFalse(room.members().get(0).ready());}</li>
     *   <li>{@code assertFalse(room.members().get(1).ready());}</li>
     *   <li>{@code assertTrue(room.roomId() > 0);}</li>
     * </ul>
     */
    @Test
    @DisplayName("createRoom 应创建 status=1 且全员未准备的房间")
    void createRoomShouldInitWaitingRoom() {
        RoomService.Room room = roomService.createRoom(MODE, MatchmakingTestFixtures.twoPlayers(MODE));

        log.info("创建房间校验: roomId={}, mode={}, status={}, memberCount={}, firstReady={}, secondReady={}",
                room.roomId(), room.mode(), room.status(), room.members().size(),
                room.members().get(0).ready(), room.members().get(1).ready());
        assertEquals(MODE, room.mode());
        assertEquals(1, room.status());
        assertEquals(2, room.members().size());
        assertFalse(room.members().get(0).ready());
        assertFalse(room.members().get(1).ready());
        assertTrue(room.roomId() > 0);
    }

    /**
     * 验证点：findRoomByPlayer 应按玩家反查房间。
     * <p>测试方法 {@code findRoomByPlayerShouldLocateRoom}：
     * <ul>
     *   <li>{@code assertNotNull(foundA);}</li>
     *   <li>{@code assertEquals(created.roomId(), foundA.roomId());}</li>
     *   <li>{@code assertNull(foundMissing);}</li>
     * </ul>
     */
    @Test
    @DisplayName("findRoomByPlayer 应按玩家反查房间")
    void findRoomByPlayerShouldLocateRoom() {
        RoomService.Room created = roomService.createRoom(MODE, MatchmakingTestFixtures.twoPlayers(MODE));
        RoomService.Room foundA = roomService.findRoomByPlayer(MatchmakingTestFixtures.PLAYER_A);
        RoomService.Room foundMissing = roomService.findRoomByPlayer(MatchmakingTestFixtures.PLAYER_C);

        log.info("按玩家查房校验: playerA={}, foundRoomId={}, expectedRoomId={}, missingPlayer={}, missingIsNull={}",
                MatchmakingTestFixtures.PLAYER_A,
                foundA != null ? foundA.roomId() : -1,
                created.roomId(),
                MatchmakingTestFixtures.PLAYER_C,
                foundMissing == null);
        assertNotNull(foundA);
        assertEquals(created.roomId(), foundA.roomId());
        assertNull(foundMissing);
    }

    /**
     * 验证点：setReady 单人准备后 status 仍为 1。
     * <p>测试方法 {@code setReadyPartialShouldKeepWaitingStatus}：
     * <ul>
     *   <li>{@code assertNotNull(updated);}</li>
     *   <li>{@code assertEquals(1, updated.status());}</li>
     *   <li>{@code assertTrue(updated.members().stream()}</li>
     *   <li>{@code assertFalse(updated.members().stream()}</li>
     * </ul>
     */
    @Test
    @DisplayName("setReady 单人准备后 status 仍为 1")
    void setReadyPartialShouldKeepWaitingStatus() {
        RoomService.Room created = roomService.createRoom(MODE, MatchmakingTestFixtures.twoPlayers(MODE));
        RoomService.Room updated = roomService.setReady(
                MatchmakingTestFixtures.PLAYER_A, created.roomId(), true);

        log.info("部分准备校验: roomId={}, status={}, playerAReady={}, playerBReady={}, allReady={}",
                updated.roomId(), updated.status(),
                updated.members().get(0).ready(),
                updated.members().get(1).ready(),
                updated.members().stream().allMatch(RoomService.RoomMember::ready));
        assertNotNull(updated);
        assertEquals(1, updated.status());
        assertTrue(updated.members().stream()
                .filter(m -> m.playerId() == MatchmakingTestFixtures.PLAYER_A)
                .findFirst().orElseThrow().ready());
        assertFalse(updated.members().stream()
                .filter(m -> m.playerId() == MatchmakingTestFixtures.PLAYER_B)
                .findFirst().orElseThrow().ready());
    }

    /**
     * 验证点：全员 ready 后 status 应变为 2。
     * <p>测试方法 {@code setReadyAllShouldMarkRoomReady}：
     * <ul>
     *   <li>{@code assertEquals(2, allReady.status());}</li>
     *   <li>{@code assertTrue(allReady.members().stream().allMatch(RoomService.RoomMember::ready));}</li>
     * </ul>
     */
    @Test
    @DisplayName("全员 ready 后 status 应变为 2")
    void setReadyAllShouldMarkRoomReady() {
        RoomService.Room created = roomService.createRoom(MODE, MatchmakingTestFixtures.twoPlayers(MODE));
        roomService.setReady(MatchmakingTestFixtures.PLAYER_A, created.roomId(), true);
        RoomService.Room allReady = roomService.setReady(
                MatchmakingTestFixtures.PLAYER_B, created.roomId(), true);

        log.info("全员准备校验: roomId={}, statusBeforePartial=1, statusAfterAll={}, memberCount={}, allReady={}",
                allReady.roomId(), allReady.status(), allReady.members().size(),
                allReady.members().stream().allMatch(RoomService.RoomMember::ready));
        assertEquals(2, allReady.status());
        assertTrue(allReady.members().stream().allMatch(RoomService.RoomMember::ready));
    }

    /**
     * 验证点：setReady 对不存在房间应返回 null。
     * <p>测试方法 {@code setReadyMissingRoomShouldReturnNull}：
     * <ul>
     *   <li>{@code assertNull(missing);}</li>
     * </ul>
     */
    @Test
    @DisplayName("setReady 对不存在房间应返回 null")
    void setReadyMissingRoomShouldReturnNull() {
        RoomService.Room missing = roomService.setReady(MatchmakingTestFixtures.PLAYER_A, 999L, true);

        log.info("无效房间准备校验: roomId=999, playerId={}, resultIsNull={}",
                MatchmakingTestFixtures.PLAYER_A, missing == null);
        assertNull(missing);
    }
}
