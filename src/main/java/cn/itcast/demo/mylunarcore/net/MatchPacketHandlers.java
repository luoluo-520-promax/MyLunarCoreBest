package cn.itcast.demo.mylunarcore.net;

// 匹配队列/房间就绪的 Netty 业务实现
import cn.itcast.demo.mylunarcore.matchmaking.MatchNettyService;
// 匹配系统 Protobuf 请求与响应类型
import cn.itcast.demo.mylunarcore.protocol.MatchmakingSystemProto;
import io.netty.channel.ChannelHandlerContext;
import org.springframework.stereotype.Component;

/**
 * 匹配相关协议包处理器：将客户端 CsReq 反序列化后交给 {@link MatchNettyService}，
 * 再把 ScRsp 封成 {@link GamePacket} 写回同一 Channel。
 * <p>
 * 方法由 {@link PacketCmd} 注解注册到 {@link PacketCommandRegistry}，
 * 在 {@link GamePacketDispatcher} 按 CmdId 分发时调用。
 */
@Component
public class MatchPacketHandlers {

    // 匹配入队、取消、房间就绪的具体业务逻辑
    private final MatchNettyService matchNettyService;

    /**
     * @param matchNettyService 匹配 Netty 适配服务（含会话校验与队列状态机）
     */
    public MatchPacketHandlers(MatchNettyService matchNettyService) {
        this.matchNettyService = matchNettyService;
    }

    /**
     * 处理加入匹配队列请求 {@link CmdIds#JOIN_MATCH_QUEUE_CS_REQ}。
     * 解析 payload → handleJoinQueue（绑定 channel 以便匹配成功推送）→ 回 {@code JOIN_MATCH_QUEUE_SC_RSP}。
     */
    @PacketCmd(CmdIds.JOIN_MATCH_QUEUE_CS_REQ)
    public void onJoinMatchQueue(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        // 从帧 payload 反序列化加入队列请求（模式、段位等字段在 proto 内）
        MatchmakingSystemProto.JoinMatchQueueCsReq req = MatchmakingSystemProto.JoinMatchQueueCsReq.parseFrom(packet.getPayload());
        // 传入 channel，匹配成功后可向该连接推送 MatchFound
        MatchmakingSystemProto.JoinMatchQueueScRsp rsp = matchNettyService.handleJoinQueue(req, ctx.channel());
        // 用对应 ScRsp CmdId 封包并异步写出
        ctx.writeAndFlush(new GamePacket(CmdIds.JOIN_MATCH_QUEUE_SC_RSP, rsp.toByteArray()));
    }

    /**
     * 处理取消匹配队列 {@link CmdIds#CANCEL_MATCH_QUEUE_CS_REQ}。
     * 从队列移除玩家后回 {@code CANCEL_MATCH_QUEUE_SC_RSP}（含是否成功等 retcode）。
     */
    @PacketCmd(CmdIds.CANCEL_MATCH_QUEUE_CS_REQ)
    public void onCancelMatchQueue(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        MatchmakingSystemProto.CancelMatchQueueCsReq req = MatchmakingSystemProto.CancelMatchQueueCsReq.parseFrom(packet.getPayload());
        MatchmakingSystemProto.CancelMatchQueueScRsp rsp = matchNettyService.handleCancelQueue(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.CANCEL_MATCH_QUEUE_SC_RSP, rsp.toByteArray()));
    }

    /**
     * 处理房间内准备状态 {@link CmdIds#SET_ROOM_READY_CS_REQ}。
     * 全员就绪后业务层可开局；本处理器只负责协议转换与回包。
     */
    @PacketCmd(CmdIds.SET_ROOM_READY_CS_REQ)
    public void onSetRoomReady(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        MatchmakingSystemProto.SetRoomReadyCsReq req = MatchmakingSystemProto.SetRoomReadyCsReq.parseFrom(packet.getPayload());
        MatchmakingSystemProto.SetRoomReadyScRsp rsp = matchNettyService.handleSetRoomReady(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.SET_ROOM_READY_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.MATCH_READY_CHECK_CS_REQ)
    public void onMatchReadyCheck(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        MatchmakingSystemProto.MatchReadyCheckCsReq req =
                MatchmakingSystemProto.MatchReadyCheckCsReq.parseFrom(
                        packet.getPayload() == null ? new byte[0] : packet.getPayload());
        MatchmakingSystemProto.MatchReadyCheckScRsp rsp =
                matchNettyService.handleReadyCheck(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.MATCH_READY_CHECK_SC_RSP, rsp.toByteArray()));
    }
}
