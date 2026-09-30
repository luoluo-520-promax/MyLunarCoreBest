// 匹配协议适配层：入队、取消、房间准备、房间通知推送
package cn.itcast.demo.mylunarcore.matchmaking;

import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.player.PlayerContextResolver;
import cn.itcast.demo.mylunarcore.protocol.MatchmakingSystemProto;
import io.netty.channel.Channel;
import org.springframework.stereotype.Service;

/**
 * 匹配协议适配层。
 * 把 protobuf 请求映射到 MatchmakingService / RoomService，不直接维护队列或房间状态。
 * retcode：1 未登录；2 业务失败；0 成功。
 */
@Service // 注册为 Spring Bean，由 Netty 包处理器路由匹配相关协议到此服务
public class MatchNettyService {

    /** 匹配编排服务：处理 JoinMatchQueue / CancelMatchQueue 的业务逻辑。 */
    private final MatchmakingService matchmakingService;
    /** 房间状态服务：查询房间、更新准备态、获取成员列表。 */
    private final RoomService roomService;
    /** 玩家上下文解析器：从 Netty Channel 绑定信息中解析当前连接的 playerId。 */
    private final PlayerContextResolver contextResolver;

    /**
     * 构造器注入匹配、房间、上下文解析三个依赖。
     * 协议层只负责序列化/反序列化，业务状态全部由下层服务维护。
     */
    public MatchNettyService(MatchmakingService matchmakingService,
                             RoomService roomService,
                             PlayerContextResolver contextResolver) {
        this.matchmakingService = matchmakingService; // 入队与取消排队委托给匹配编排
        this.roomService = roomService;               // 房间查询与准备态更新委托给房间服务
        this.contextResolver = contextResolver;       // 从 Channel 获取玩家身份，防止未登录操作
    }

    /**
     * 处理 JoinMatchQueue 客户端请求：解析身份 → 入队 → 若已成局则推送房间通知。
     */
    public MatchmakingSystemProto.JoinMatchQueueScRsp handleJoinQueue(
            MatchmakingSystemProto.JoinMatchQueueCsReq req, Channel channel) {
        int playerId = contextResolver.resolvePlayerId(channel); // 从当前 TCP/KCP 连接解析已登录玩家 uid
        if (playerId <= 0) {
            // 未绑定玩家身份（未登录或会话过期），返回 retcode=1
            return MatchmakingSystemProto.JoinMatchQueueScRsp.newBuilder().setRetcode(1).build();
        }
        // 携带客户端指定的 mode/level/power 入队，并同步尝试 pollMatch 成局
        MatchmakingService.JoinResult result = matchmakingService.joinQueue(
                playerId, req.getMode(), req.getLevel(), req.getPower());
        // 若本次入队后已凑齐人数，RoomService 会立即创建房间并写入 playerRoom 映射
        RoomService.Room room = roomService.findRoomByPlayer(playerId);
        if (room != null) {
            // 已成局：主动推送 MatchRoomScNotify，客户端可立即展示队伍面板而无需轮询
            pushRoomNotify(channel, room);
        }
        // 构造入队响应：retcode 0=成功 2=业务失败；附带 mode 与 queuePosition 供 UI 展示
        return MatchmakingSystemProto.JoinMatchQueueScRsp.newBuilder()
                .setRetcode(result.success() ? 0 : 2)
                .setMode(req.getMode())
                .setQueuePosition(result.queuePosition())
                .build();
    }

    /**
     * 处理 CancelMatchQueue 客户端请求：从指定 mode 队列移除当前玩家。
     */
    public MatchmakingSystemProto.CancelMatchQueueScRsp handleCancelQueue(
            MatchmakingSystemProto.CancelMatchQueueCsReq req, Channel channel) {
        int playerId = contextResolver.resolvePlayerId(channel); // 解析请求发起者的玩家 uid
        if (playerId <= 0) {
            return MatchmakingSystemProto.CancelMatchQueueScRsp.newBuilder().setRetcode(1).build(); // 未登录
        }
        // 从 req.getMode() 对应队列中移除该玩家；成功则 retcode=0，未在队列中则 retcode=2
        boolean ok = matchmakingService.cancelQueue(playerId, req.getMode());
        return MatchmakingSystemProto.CancelMatchQueueScRsp.newBuilder()
                .setRetcode(ok ? 0 : 2)
                .build();
    }

    /**
     * 处理 SetRoomReady 客户端请求：更新成员准备态；全员 ready 时 room.status 变为 2 并推送通知。
     */
    public MatchmakingSystemProto.SetRoomReadyScRsp handleSetRoomReady(
            MatchmakingSystemProto.SetRoomReadyCsReq req, Channel channel) {
        int playerId = contextResolver.resolvePlayerId(channel); // 解析点击准备/取消准备的玩家 uid
        if (playerId <= 0) {
            return MatchmakingSystemProto.SetRoomReadyScRsp.newBuilder().setRetcode(1).build(); // 未登录
        }
        // 更新指定 roomId 内该玩家的 ready 位；roomId 不存在或玩家不在该房间时返回 null
        RoomService.Room room = roomService.setReady(playerId, req.getRoomId(), req.getReady());
        if (room == null) {
            return MatchmakingSystemProto.SetRoomReadyScRsp.newBuilder().setRetcode(2).build(); // 房间或成员校验失败
        }
        // 准备态变更后推送最新房间快照，客户端可刷新全员 ready 状态与 room.status
        pushRoomNotify(channel, room);
        return MatchmakingSystemProto.SetRoomReadyScRsp.newBuilder()
                .setRetcode(0)
                .setRoomId(req.getRoomId())
                .build();
    }

    /**
     * Ready Check：准备确认后才锁定会话（MATCHING / LOCKED）。
     */
    public MatchmakingSystemProto.MatchReadyCheckScRsp handleReadyCheck(
            MatchmakingSystemProto.MatchReadyCheckCsReq req, Channel channel) {
        int playerId = contextResolver.resolvePlayerId(channel);
        if (playerId <= 0) {
            return MatchmakingSystemProto.MatchReadyCheckScRsp.newBuilder().setRetcode(1).build();
        }
        MatchmakingService.ReadyCheckResult result =
                matchmakingService.readyCheck(playerId, req.getRoomId(), req.getReady());
        return MatchmakingSystemProto.MatchReadyCheckScRsp.newBuilder()
                .setRetcode(result.retcode())
                .setRoomId(req.getRoomId())
                .setLocked(result.locked())
                .build();
    }

    /**
     * 向指定 Channel 推送 MatchRoomScNotify。
     * 包含 roomId、mode、status 及全部成员 ready 状态，客户端收到后可刷新队伍面板，无需再请求房间详情。
     */
    public void pushRoomNotify(Channel channel, RoomService.Room room) {
        // 构建房间通知 protobuf：roomId 唯一标识、mode 匹配模式、status 1=等待准备 2=可开战
        MatchmakingSystemProto.MatchRoomScNotify.Builder builder =
                MatchmakingSystemProto.MatchRoomScNotify.newBuilder()
                        .setRoomId(room.roomId())
                        .setMode(room.mode())
                        .setStatus(room.status());
        // 遍历房间成员，逐个写入 playerId 与 ready 状态供客户端渲染队伍列表
        for (RoomService.RoomMember member : room.members()) {
            builder.addMembers(MatchmakingSystemProto.MatchRoomMember.newBuilder()
                    .setPlayerId(member.playerId())
                    .setReady(member.ready())
                    .build());
        }
        // 封装为 GamePacket 并通过 Netty Channel 异步写出；CmdIds.MATCH_ROOM_SC_NOTIFY 为服务端推送命令字
        channel.writeAndFlush(new GamePacket(CmdIds.MATCH_ROOM_SC_NOTIFY, builder.build().toByteArray()));
    }
}
