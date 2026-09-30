package cn.itcast.demo.mylunarcore.matchmaking;

import cn.itcast.demo.mylunarcore.common.BusinessMetrics;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import static org.mockito.Mockito.mock;

/**
 * MatchmakingService 匹配编排测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code MatchmakingServiceTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("MatchmakingService 匹配编排测试")
class MatchmakingServiceTest {

    private static final Logger log = LoggerFactory.getLogger(MatchmakingServiceTest.class);

    private static final int MODE = MatchmakingTestFixtures.MODE;

    private RoomService roomService;
    private MatchmakingService service;

    @BeforeEach
    void setUp() {
        roomService = new RoomService();
        service = new MatchmakingService(
                roomService,
                mock(GameSessionManager.class),
                new LunarCoreProperties(),
                new BusinessMetrics(new SimpleMeterRegistry()));
        log.info("匹配编排初始化: mode={}, defaultTeamSize=2", MODE);
    }

    /**
     * 验证点：非法 playerId 入队应失败。
     * <p>测试方法 {@code joinQueueShouldRejectInvalidPlayerId}：
     * <ul>
     *   <li>{@code assertFalse(result.success());}</li>
     *   <li>{@code assertEquals(0, result.queuePosition());}</li>
     * </ul>
     */
    @Test
    @DisplayName("非法 playerId 入队应失败")
    void joinQueueShouldRejectInvalidPlayerId() {
        MatchmakingService.JoinResult result = service.joinQueue(0, MODE, 10, 1000);

        log.info("非法入队校验: playerId=0, success={}, queuePosition={}",
                result.success(), result.queuePosition());
        assertFalse(result.success());
        assertEquals(0, result.queuePosition());
    }

    /**
     * 验证点：单人入队成功但未成局。
     * <p>测试方法 {@code joinQueueSingleShouldNotCreateRoom}：
     * <ul>
     *   <li>{@code assertTrue(result.success());}</li>
     *   <li>{@code assertEquals(1, result.queuePosition());}</li>
     *   <li>{@code assertNull(room);}</li>
     * </ul>
     */
    @Test
    @DisplayName("单人入队成功但未成局")
    void joinQueueSingleShouldNotCreateRoom() {
        MatchmakingService.JoinResult result = service.joinQueue(
                MatchmakingTestFixtures.PLAYER_A, MODE, 10, 1000);
        RoomService.Room room = roomService.findRoomByPlayer(MatchmakingTestFixtures.PLAYER_A);

        log.info("单人入队校验: playerId={}, success={}, queuePosition={}, roomCreated={}",
                MatchmakingTestFixtures.PLAYER_A, result.success(), result.queuePosition(), room != null);
        assertTrue(result.success());
        assertEquals(1, result.queuePosition());
        assertNull(room);
    }

    /**
     * 验证点：两人同 mode 入队应立即成局建房。
     * <p>测试方法 {@code joinQueueTwoPlayersShouldCreateRoom}：
     * <ul>
     *   <li>{@code assertTrue(first.success());}</li>
     *   <li>{@code assertTrue(second.success());}</li>
     *   <li>{@code assertNotNull(roomA);}</li>
     *   <li>{@code assertNotNull(roomB);}</li>
     *   <li>{@code assertEquals(roomA.roomId(), roomB.roomId());}</li>
     *   <li>{@code assertEquals(1, roomA.status());}</li>
     * </ul>
     */
    @Test
    @DisplayName("两人同 mode 入队后经批次 Tick 应成局建房")
    void joinQueueTwoPlayersShouldCreateRoom() {
        MatchmakingService.JoinResult first = service.joinQueue(
                MatchmakingTestFixtures.PLAYER_A, MODE, 10, 1000);
        MatchmakingService.JoinResult second = service.joinQueue(
                MatchmakingTestFixtures.PLAYER_B, MODE, 20, 1500);
        // 批次匹配：入队本身不成房，由 batchMatchTick 统一结算
        service.batchMatchTick();

        RoomService.Room roomA = roomService.findRoomByPlayer(MatchmakingTestFixtures.PLAYER_A);
        RoomService.Room roomB = roomService.findRoomByPlayer(MatchmakingTestFixtures.PLAYER_B);

        log.info("双人成局校验: firstSuccess={}, firstPos={}, secondSuccess={}, secondPos={}, roomIdA={}, roomIdB={}, status={}, memberCount={}",
                first.success(), first.queuePosition(),
                second.success(), second.queuePosition(),
                roomA != null ? roomA.roomId() : -1,
                roomB != null ? roomB.roomId() : -1,
                roomA != null ? roomA.status() : -1,
                roomA != null ? roomA.members().size() : 0);
        assertTrue(first.success());
        assertTrue(second.success());
        assertNotNull(roomA);
        assertNotNull(roomB);
        assertEquals(roomA.roomId(), roomB.roomId());
        assertEquals(1, roomA.status());
        assertEquals(2, roomA.members().size());
    }

    /**
     * 验证点：cancelQueue 应取消排队。
     * <p>测试方法 {@code cancelQueueShouldRemoveWaitingPlayer}：
     * <ul>
     *   <li>{@code assertTrue(cancelled);}</li>
     *   <li>{@code assertFalse(cancelAgain);}</li>
     * </ul>
     */
    @Test
    @DisplayName("cancelQueue 应取消排队")
    void cancelQueueShouldRemoveWaitingPlayer() {
        service.joinQueue(MatchmakingTestFixtures.PLAYER_A, MODE, 10, 1000);
        boolean cancelled = service.cancelQueue(MatchmakingTestFixtures.PLAYER_A, MODE);
        boolean cancelAgain = service.cancelQueue(MatchmakingTestFixtures.PLAYER_A, MODE);

        log.info("取消匹配校验: playerId={}, mode={}, cancelled={}, cancelAgain={}",
                MatchmakingTestFixtures.PLAYER_A, MODE, cancelled, cancelAgain);
        assertTrue(cancelled);
        assertFalse(cancelAgain);
    }

    /**
     * 验证点：不同 mode 应互不干扰成局。
     * <p>测试方法 {@code differentModesShouldNotMatchTogether}：
     * <ul>
     *   <li>{@code assertNull(roomA);}</li>
     *   <li>{@code assertNull(roomB);}</li>
     * </ul>
     */
    @Test
    @DisplayName("不同 mode 应互不干扰成局")
    void differentModesShouldNotMatchTogether() {
        service.joinQueue(MatchmakingTestFixtures.PLAYER_A, 1, 10, 1000);
        service.joinQueue(MatchmakingTestFixtures.PLAYER_B, 2, 20, 1500);

        RoomService.Room roomA = roomService.findRoomByPlayer(MatchmakingTestFixtures.PLAYER_A);
        RoomService.Room roomB = roomService.findRoomByPlayer(MatchmakingTestFixtures.PLAYER_B);

        log.info("跨模式隔离校验: playerAMode=1 roomCreated={}, playerBMode=2 roomCreated={}",
                roomA != null, roomB != null);
        assertNull(roomA);
        assertNull(roomB);
    }
}
