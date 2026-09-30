package cn.itcast.demo.mylunarcore.matchmaking;

import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.player.PlayerContextResolver;
import cn.itcast.demo.mylunarcore.protocol.MatchmakingSystemProto;
import io.netty.channel.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MatchNettyService 匹配协议服务测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code MatchNettyServiceTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("MatchNettyService 匹配协议服务测试")
class MatchNettyServiceTest {

    private static final Logger log = LoggerFactory.getLogger(MatchNettyServiceTest.class);

    private static final int PLAYER_ID = MatchmakingTestFixtures.PLAYER_A;
    private static final int MODE = MatchmakingTestFixtures.MODE;

    private MatchmakingService matchmakingService;
    private RoomService roomService;
    private PlayerContextResolver contextResolver;
    private MatchNettyService service;

    @BeforeEach
    void setUp() {
        matchmakingService = mock(MatchmakingService.class);
        roomService = mock(RoomService.class);
        contextResolver = mock(PlayerContextResolver.class);
        service = new MatchNettyService(matchmakingService, roomService, contextResolver);
        log.info("匹配协议服务初始化: playerId={}, mode={}", PLAYER_ID, MODE);
    }

    /**
     * 验证点：入队成功且未成局应返回 queuePosition。
     * <p>测试方法 {@code handleJoinQueueShouldReturnPositionWhenWaiting}：
     * <ul>
     *   <li>{@code when(matchmakingService.joinQueue(PLAYER_ID, MODE, 20, 1500))}</li>
     *   <li>{@code when(roomService.findRoomByPlayer(PLAYER_ID)).thenReturn(null);}</li>
     *   <li>{@code assertEquals(0, rsp.getRetcode());}</li>
     *   <li>{@code assertEquals(MODE, rsp.getMode());}</li>
     *   <li>{@code assertEquals(1, rsp.getQueuePosition());}</li>
     * </ul>
     */
    @Test
    @DisplayName("入队成功且未成局应返回 queuePosition")
    void handleJoinQueueShouldReturnPositionWhenWaiting() {
        when(matchmakingService.joinQueue(PLAYER_ID, MODE, 20, 1500))
                .thenReturn(new MatchmakingService.JoinResult(true, 1));
        when(roomService.findRoomByPlayer(PLAYER_ID)).thenReturn(null);

        MatchmakingSystemProto.JoinMatchQueueScRsp rsp = service.handleJoinQueue(
                MatchmakingSystemProto.JoinMatchQueueCsReq.newBuilder()
                        .setMode(MODE).setLevel(20).setPower(1500).build(),
                loggedInChannel());

        log.info("入队协议校验: retcode={}, mode={}, queuePosition={}, roomNotifyPushed={}",
                rsp.getRetcode(), rsp.getMode(), rsp.getQueuePosition(), false);
        assertEquals(0, rsp.getRetcode());
        assertEquals(MODE, rsp.getMode());
        assertEquals(1, rsp.getQueuePosition());
    }

    /**
     * 验证点：入队成局应推送 MatchRoomScNotify。
     * <p>测试方法 {@code handleJoinQueueShouldPushRoomNotifyWhenMatched}：
     * <ul>
     *   <li>{@code when(matchmakingService.joinQueue(PLAYER_ID, MODE, 20, 1500))}</li>
     *   <li>{@code when(roomService.findRoomByPlayer(PLAYER_ID)).thenReturn(room);}</li>
     *   <li>{@code verify(channel).writeAndFlush(packetCaptor.capture());}</li>
     *   <li>{@code assertEquals(0, rsp.getRetcode());}</li>
     *   <li>{@code assertEquals(CmdIds.MATCH_ROOM_SC_NOTIFY, packet.getCmdId());}</li>
     *   <li>{@code assertTrue(packet.getPayload().length > 0);}</li>
     * </ul>
     */
    @Test
    @DisplayName("入队成局应推送 MatchRoomScNotify")
    void handleJoinQueueShouldPushRoomNotifyWhenMatched() {
        when(matchmakingService.joinQueue(PLAYER_ID, MODE, 20, 1500))
                .thenReturn(new MatchmakingService.JoinResult(true, 2));
        RoomService.Room room = new RoomService.Room(
                11L, MODE, 1,
                List.of(
                        new RoomService.RoomMember(PLAYER_ID, false),
                        new RoomService.RoomMember(MatchmakingTestFixtures.PLAYER_B, false)));
        when(roomService.findRoomByPlayer(PLAYER_ID)).thenReturn(room);

        Channel channel = loggedInChannel();
        MatchmakingSystemProto.JoinMatchQueueScRsp rsp = service.handleJoinQueue(
                MatchmakingSystemProto.JoinMatchQueueCsReq.newBuilder()
                        .setMode(MODE).setLevel(20).setPower(1500).build(),
                channel);

        ArgumentCaptor<GamePacket> packetCaptor = ArgumentCaptor.forClass(GamePacket.class);
        verify(channel).writeAndFlush(packetCaptor.capture());
        GamePacket packet = packetCaptor.getValue();

        log.info("成局推送校验: retcode={}, queuePosition={}, notifyCmdId={}, expectedCmdId={}, roomId={}, payloadSize={}",
                rsp.getRetcode(), rsp.getQueuePosition(),
                packet.getCmdId(), CmdIds.MATCH_ROOM_SC_NOTIFY,
                room.roomId(), packet.getPayload().length);
        assertEquals(0, rsp.getRetcode());
        assertEquals(CmdIds.MATCH_ROOM_SC_NOTIFY, packet.getCmdId());
        assertTrue(packet.getPayload().length > 0);
    }

    /**
     * 验证点：未登录入队应返回 retcode=1。
     * <p>测试方法 {@code handleJoinQueueWithoutLoginShouldFail}：
     * <ul>
     *   <li>{@code assertEquals(1, rsp.getRetcode());}</li>
     *   <li>{@code verify(matchmakingService, never()).joinQueue(anyInt(), anyInt(), anyInt(), anyInt());}</li>
     * </ul>
     */
    @Test
    @DisplayName("未登录入队应返回 retcode=1")
    void handleJoinQueueWithoutLoginShouldFail() {
        MatchmakingSystemProto.JoinMatchQueueScRsp rsp = service.handleJoinQueue(
                MatchmakingSystemProto.JoinMatchQueueCsReq.newBuilder().setMode(MODE).build(),
                loggedOutChannel());

        log.info("未登录入队校验: retcode={}", rsp.getRetcode());
        assertEquals(1, rsp.getRetcode());
        verify(matchmakingService, never()).joinQueue(anyInt(), anyInt(), anyInt(), anyInt());
    }

    /**
     * 验证点：取消排队成功应返回 retcode=0。
     * <p>测试方法 {@code handleCancelQueueShouldSucceed}：
     * <ul>
     *   <li>{@code when(matchmakingService.cancelQueue(PLAYER_ID, MODE)).thenReturn(true);}</li>
     *   <li>{@code assertEquals(0, rsp.getRetcode());}</li>
     * </ul>
     */
    @Test
    @DisplayName("取消排队成功应返回 retcode=0")
    void handleCancelQueueShouldSucceed() {
        when(matchmakingService.cancelQueue(PLAYER_ID, MODE)).thenReturn(true);

        MatchmakingSystemProto.CancelMatchQueueScRsp rsp = service.handleCancelQueue(
                MatchmakingSystemProto.CancelMatchQueueCsReq.newBuilder().setMode(MODE).build(),
                loggedInChannel());

        log.info("取消排队协议校验: mode={}, retcode={}, cancelled={}",
                MODE, rsp.getRetcode(), true);
        assertEquals(0, rsp.getRetcode());
    }

    /**
     * 验证点：取消排队失败应返回 retcode=2。
     * <p>测试方法 {@code handleCancelQueueShouldFailWhenNotInQueue}：
     * <ul>
     *   <li>{@code when(matchmakingService.cancelQueue(PLAYER_ID, MODE)).thenReturn(false);}</li>
     *   <li>{@code assertEquals(2, rsp.getRetcode());}</li>
     * </ul>
     */
    @Test
    @DisplayName("取消排队失败应返回 retcode=2")
    void handleCancelQueueShouldFailWhenNotInQueue() {
        when(matchmakingService.cancelQueue(PLAYER_ID, MODE)).thenReturn(false);

        MatchmakingSystemProto.CancelMatchQueueScRsp rsp = service.handleCancelQueue(
                MatchmakingSystemProto.CancelMatchQueueCsReq.newBuilder().setMode(MODE).build(),
                loggedInChannel());

        log.info("取消失败协议校验: mode={}, retcode={}", MODE, rsp.getRetcode());
        assertEquals(2, rsp.getRetcode());
    }

    /**
     * 验证点：设置准备成功应回传 roomId 并推送通知。
     * <p>测试方法 {@code handleSetRoomReadyShouldSucceed}：
     * <ul>
     *   <li>{@code when(roomService.setReady(PLAYER_ID, roomId, true)).thenReturn(room);}</li>
     *   <li>{@code verify(channel).writeAndFlush(packetCaptor.capture());}</li>
     *   <li>{@code assertEquals(0, rsp.getRetcode());}</li>
     *   <li>{@code assertEquals(roomId, rsp.getRoomId());}</li>
     *   <li>{@code assertEquals(CmdIds.MATCH_ROOM_SC_NOTIFY, packetCaptor.getValue().getCmdId());}</li>
     * </ul>
     */
    @Test
    @DisplayName("设置准备成功应回传 roomId 并推送通知")
    void handleSetRoomReadyShouldSucceed() {
        long roomId = 22L;
        RoomService.Room room = new RoomService.Room(
                roomId, MODE, 1,
                List.of(new RoomService.RoomMember(PLAYER_ID, true)));
        when(roomService.setReady(PLAYER_ID, roomId, true)).thenReturn(room);

        Channel channel = loggedInChannel();
        MatchmakingSystemProto.SetRoomReadyScRsp rsp = service.handleSetRoomReady(
                MatchmakingSystemProto.SetRoomReadyCsReq.newBuilder()
                        .setRoomId(roomId).setReady(true).build(),
                channel);

        ArgumentCaptor<GamePacket> packetCaptor = ArgumentCaptor.forClass(GamePacket.class);
        verify(channel).writeAndFlush(packetCaptor.capture());

        log.info("准备协议校验: retcode={}, roomId={}, ready=true, notifyCmdId={}, payloadSize={}",
                rsp.getRetcode(), rsp.getRoomId(),
                packetCaptor.getValue().getCmdId(),
                packetCaptor.getValue().getPayload().length);
        assertEquals(0, rsp.getRetcode());
        assertEquals(roomId, rsp.getRoomId());
        assertEquals(CmdIds.MATCH_ROOM_SC_NOTIFY, packetCaptor.getValue().getCmdId());
    }

    /**
     * 验证点：设置准备房间不存在应返回 retcode=2。
     * <p>测试方法 {@code handleSetRoomReadyShouldFailWhenRoomMissing}：
     * <ul>
     *   <li>{@code when(roomService.setReady(eq(PLAYER_ID), anyLong(), eq(true))).thenReturn(null);}</li>
     *   <li>{@code assertEquals(2, rsp.getRetcode());}</li>
     * </ul>
     */
    @Test
    @DisplayName("设置准备房间不存在应返回 retcode=2")
    void handleSetRoomReadyShouldFailWhenRoomMissing() {
        when(roomService.setReady(eq(PLAYER_ID), anyLong(), eq(true))).thenReturn(null);

        MatchmakingSystemProto.SetRoomReadyScRsp rsp = service.handleSetRoomReady(
                MatchmakingSystemProto.SetRoomReadyCsReq.newBuilder()
                        .setRoomId(999L).setReady(true).build(),
                loggedInChannel());

        log.info("无效房间准备协议校验: roomId=999, retcode={}", rsp.getRetcode());
        assertEquals(2, rsp.getRetcode());
    }

    /**
     * 验证点：未登录设置准备应返回 retcode=1。
     * <p>测试方法 {@code handleSetRoomReadyWithoutLoginShouldFail}：
     * <ul>
     *   <li>{@code assertEquals(1, rsp.getRetcode());}</li>
     *   <li>{@code verify(roomService, never()).setReady(anyInt(), anyLong(), eq(true));}</li>
     * </ul>
     */
    @Test
    @DisplayName("未登录设置准备应返回 retcode=1")
    void handleSetRoomReadyWithoutLoginShouldFail() {
        MatchmakingSystemProto.SetRoomReadyScRsp rsp = service.handleSetRoomReady(
                MatchmakingSystemProto.SetRoomReadyCsReq.newBuilder()
                        .setRoomId(1L).setReady(true).build(),
                loggedOutChannel());

        log.info("未登录准备校验: retcode={}", rsp.getRetcode());
        assertEquals(1, rsp.getRetcode());
        verify(roomService, never()).setReady(anyInt(), anyLong(), eq(true));
    }

    private Channel loggedInChannel() {
        Channel channel = mock(Channel.class);
        when(contextResolver.resolvePlayerId(channel)).thenReturn(PLAYER_ID);
        when(channel.writeAndFlush(any())).thenReturn(null);
        log.info("模拟登录 Channel: playerId={}", PLAYER_ID);
        return channel;
    }

    private Channel loggedOutChannel() {
        Channel channel = mock(Channel.class);
        when(contextResolver.resolvePlayerId(channel)).thenReturn(0);
        log.info("模拟未登录 Channel: playerId=0");
        return channel;
    }
}
