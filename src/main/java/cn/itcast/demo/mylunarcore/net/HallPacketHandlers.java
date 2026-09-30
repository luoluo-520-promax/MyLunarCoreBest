// 网络命令处理器实现所在包（大厅/社交系统相关）
package cn.itcast.demo.mylunarcore.net;

// 大厅领域 Netty 门面：好友、邮件、排行榜、聊天、组队等业务入口
import cn.itcast.demo.mylunarcore.hall.HallNettyService;
// 大厅系统 Protobuf 消息定义（CsReq 上行 / ScRsp 下行）
import cn.itcast.demo.mylunarcore.protocol.HallSystemProto;
// Netty 通道上下文：写回下行响应时使用
import io.netty.channel.ChannelHandlerContext;
// Spring 组件注解：让 PacketCommandRegistry 能扫描到本类中带 @PacketCmd 的方法
import org.springframework.stereotype.Component;

/**
 * 大厅/社交系统协议入口处理器（CmdId 100–129）。
 * <p>
 * 覆盖好友（列表/申请/处理申请）、邮件（列表/领取）、排行榜、聊天（发送/历史）、
 * 以及组队 Party（创建/邀请/离开/解散/查询）。
 * 每个方法通过 {@link PacketCmd} 绑定上行命令号，标准流程：
 * <ol>
 *   <li>把帧负载反序列化为 CsReq（无请求体的命令直接以 Channel 定位玩家）；</li>
 *   <li>委托 {@link HallNettyService} 处理业务（鉴权、读写库、广播在线通知）；</li>
 *   <li>把 ScRsp 序列化后封装为 {@link GamePacket} 写回客户端。</li>
 * </ol>
 * 部分命令（如创建/解散 Party）无上行请求体，直接以 {@code ctx.channel()} 为入参。
 */
@Component // 注册为 Spring Bean，供 PacketCommandRegistry 启动期扫描
public class HallPacketHandlers {

    // 大厅域服务（不可变依赖）：好友/邮件/聊天/组队等业务
    private final HallNettyService hallNettyService;
    private final cn.itcast.demo.mylunarcore.social.EmoteNettyService emoteNettyService;

    public HallPacketHandlers(HallNettyService hallNettyService,
                              cn.itcast.demo.mylunarcore.social.EmoteNettyService emoteNettyService) {
        this.hallNettyService = hallNettyService;
        this.emoteNettyService = emoteNettyService;
    }

    /**
     * 查询好友列表（GET_FRIEND_LIST_CS_REQ=100）。
     * 无上行请求体，直接以 Channel 定位玩家并返回好友列表。
     */
    @PacketCmd(CmdIds.GET_FRIEND_LIST_CS_REQ) // 绑定上行命令号 100
    public void onGetFriendList(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        // 服务端直接组装响应（无需解析请求体）
        HallSystemProto.GetFriendListScRsp rsp = hallNettyService.handleGetFriendList(ctx.channel());
        // 写出响应（命令号 101）
        ctx.writeAndFlush(new GamePacket(CmdIds.GET_FRIEND_LIST_SC_RSP, rsp.toByteArray()));
    }

    /**
     * 发送好友申请（SEND_FRIEND_REQUEST_CS_REQ=102）。
     * 向目标玩家发送好友申请；目标在线时可能触发推送。
     */
    @PacketCmd(CmdIds.SEND_FRIEND_REQUEST_CS_REQ) // 绑定上行命令号 102
    public void onSendFriendRequest(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        // 反序列化上行请求体（含目标玩家 id）
        HallSystemProto.SendFriendRequestCsReq req = HallSystemProto.SendFriendRequestCsReq.parseFrom(packet.getPayload());
        // 发送好友申请
        HallSystemProto.SendFriendRequestScRsp rsp = hallNettyService.handleSendFriendRequest(req, ctx.channel());
        // 写出响应（命令号 103）
        ctx.writeAndFlush(new GamePacket(CmdIds.SEND_FRIEND_REQUEST_SC_RSP, rsp.toByteArray()));
    }

    /**
     * 处理好友申请（RESPOND_FRIEND_REQUEST_CS_REQ=104）。
     * 接受或拒绝收到的申请，成功后双方进入好友关系。
     */
    @PacketCmd(CmdIds.RESPOND_FRIEND_REQUEST_CS_REQ) // 绑定上行命令号 104
    public void onRespondFriendRequest(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        // 反序列化上行请求体（含申请 id 与处理结果）
        HallSystemProto.RespondFriendRequestCsReq req = HallSystemProto.RespondFriendRequestCsReq.parseFrom(packet.getPayload());
        // 处理申请
        HallSystemProto.RespondFriendRequestScRsp rsp = hallNettyService.handleRespondFriendRequest(req, ctx.channel());
        // 写出响应（命令号 105）
        ctx.writeAndFlush(new GamePacket(CmdIds.RESPOND_FRIEND_REQUEST_SC_RSP, rsp.toByteArray()));
    }

    /**
     * 查询邮件列表（GET_MAIL_LIST_CS_REQ=106）。
     * 返回玩家邮箱中的邮件摘要与附件状态。
     */
    @PacketCmd(CmdIds.GET_MAIL_LIST_CS_REQ) // 绑定上行命令号 106
    public void onGetMailList(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        // 反序列化上行请求体（分页等参数）
        HallSystemProto.GetMailListCsReq req = HallSystemProto.GetMailListCsReq.parseFrom(packet.getPayload());
        // 查询邮件列表
        HallSystemProto.GetMailListScRsp rsp = hallNettyService.handleGetMailList(req, ctx.channel());
        // 写出响应（命令号 107）
        ctx.writeAndFlush(new GamePacket(CmdIds.GET_MAIL_LIST_SC_RSP, rsp.toByteArray()));
    }

    /**
     * 领取邮件附件（CLAIM_MAIL_CS_REQ=108）。
     * 校验邮件后发放附件到背包并标记已领取。
     */
    @PacketCmd(CmdIds.CLAIM_MAIL_CS_REQ) // 绑定上行命令号 108
    public void onClaimMail(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        // 反序列化上行请求体（含邮件 id 列表）
        HallSystemProto.ClaimMailCsReq req = HallSystemProto.ClaimMailCsReq.parseFrom(packet.getPayload());
        // 领取邮件附件
        HallSystemProto.ClaimMailScRsp rsp = hallNettyService.handleClaimMail(req, ctx.channel());
        // 写出响应（命令号 109）
        ctx.writeAndFlush(new GamePacket(CmdIds.CLAIM_MAIL_SC_RSP, rsp.toByteArray()));
    }

    /**
     * 查询排行榜（GET_LEADERBOARD_CS_REQ=110）。
     * 按榜单类型返回对应排行（如练度、战力等）。
     */
    @PacketCmd(CmdIds.GET_LEADERBOARD_CS_REQ) // 绑定上行命令号 110
    public void onGetLeaderboard(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        // 反序列化上行请求体（含榜单类型与分页）
        HallSystemProto.GetLeaderboardCsReq req = HallSystemProto.GetLeaderboardCsReq.parseFrom(packet.getPayload());
        // 查询排行榜
        HallSystemProto.GetLeaderboardScRsp rsp = hallNettyService.handleGetLeaderboard(req, ctx.channel());
        // 写出响应（命令号 111）
        ctx.writeAndFlush(new GamePacket(CmdIds.GET_LEADERBOARD_SC_RSP, rsp.toByteArray()));
    }

    /**
     * 发送聊天消息（SEND_CHAT_CS_REQ=114）。
     * 经过敏感词过滤后写入聊天，并广播给同频道/好友在线玩家。
     */
    @PacketCmd(CmdIds.SEND_CHAT_CS_REQ) // 绑定上行命令号 114
    public void onSendChat(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        // 反序列化上行请求体（频道与消息内容）
        HallSystemProto.SendChatCsReq req = HallSystemProto.SendChatCsReq.parseFrom(packet.getPayload());
        // 发送聊天消息（含过滤与广播）
        HallSystemProto.SendChatScRsp rsp = hallNettyService.handleSendChat(req, ctx.channel());
        // 写出响应（命令号 115）
        ctx.writeAndFlush(new GamePacket(CmdIds.SEND_CHAT_SC_RSP, rsp.toByteArray()));
    }

    /**
     * 查询聊天历史（GET_CHAT_HISTORY_CS_REQ=117）。
     * 拉取指定频道/好友会话的最近聊天记录。
     */
    @PacketCmd(CmdIds.GET_CHAT_HISTORY_CS_REQ) // 绑定上行命令号 117
    public void onGetChatHistory(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        // 反序列化上行请求体（频道与游标）
        HallSystemProto.GetChatHistoryCsReq req = HallSystemProto.GetChatHistoryCsReq.parseFrom(packet.getPayload());
        // 查询聊天历史
        HallSystemProto.GetChatHistoryScRsp rsp = hallNettyService.handleGetChatHistory(req, ctx.channel());
        // 写出响应（命令号 118）
        ctx.writeAndFlush(new GamePacket(CmdIds.GET_CHAT_HISTORY_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.GET_EMOTE_INVENTORY_CS_REQ)
    public void onGetEmoteInventory(ChannelHandlerContext ctx, GamePacket packet) {
        HallSystemProto.GetEmoteInventoryScRsp rsp = emoteNettyService.handleGetInventory(ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.GET_EMOTE_INVENTORY_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.SEND_CHAT_EMOTE_CS_REQ)
    public void onSendChatEmote(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        HallSystemProto.SendChatEmoteCsReq req = HallSystemProto.SendChatEmoteCsReq.parseFrom(
                packet.getPayload() == null ? new byte[0] : packet.getPayload());
        HallSystemProto.SendChatEmoteScRsp rsp = emoteNettyService.handleSendChatEmote(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.SEND_CHAT_EMOTE_SC_RSP, rsp.toByteArray()));
    }

    /**
     * 创建队伍（CREATE_PARTY_CS_REQ=120）。
     * 无上行请求体，直接创建以当前玩家为队长的队伍。
     */
    @PacketCmd(CmdIds.CREATE_PARTY_CS_REQ) // 绑定上行命令号 120
    public void onCreateParty(ChannelHandlerContext ctx, GamePacket packet) {
        // 服务端直接创建队伍并返回队伍信息
        HallSystemProto.CreatePartyScRsp rsp = hallNettyService.handleCreateParty(ctx.channel());
        // 写出响应（命令号 121）
        ctx.writeAndFlush(new GamePacket(CmdIds.CREATE_PARTY_SC_RSP, rsp.toByteArray()));
    }

    /**
     * 邀请玩家加入队伍（INVITE_PARTY_CS_REQ=122）。
     * 向目标玩家发送组队邀请。
     */
    @PacketCmd(CmdIds.INVITE_PARTY_CS_REQ) // 绑定上行命令号 122
    public void onInviteParty(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        // 反序列化上行请求体（含被邀请玩家 id）
        HallSystemProto.InvitePartyCsReq req = HallSystemProto.InvitePartyCsReq.parseFrom(packet.getPayload());
        // 发送组队邀请
        HallSystemProto.InvitePartyScRsp rsp = hallNettyService.handleInviteParty(req, ctx.channel());
        // 写出响应（命令号 123）
        ctx.writeAndFlush(new GamePacket(CmdIds.INVITE_PARTY_SC_RSP, rsp.toByteArray()));
    }

    /**
     * 离开队伍（LEAVE_PARTY_CS_REQ=124）。
     * 无上行请求体；队长离开时队伍解散。
     */
    @PacketCmd(CmdIds.LEAVE_PARTY_CS_REQ) // 绑定上行命令号 124
    public void onLeaveParty(ChannelHandlerContext ctx, GamePacket packet) {
        // 服务端直接处理离队
        HallSystemProto.LeavePartyScRsp rsp = hallNettyService.handleLeaveParty(ctx.channel());
        // 写出响应（命令号 125）
        ctx.writeAndFlush(new GamePacket(CmdIds.LEAVE_PARTY_SC_RSP, rsp.toByteArray()));
    }

    /**
     * 解散队伍（DISBAND_PARTY_CS_REQ=126）。
     * 无上行请求体；仅队长可解散。
     */
    @PacketCmd(CmdIds.DISBAND_PARTY_CS_REQ) // 绑定上行命令号 126
    public void onDisbandParty(ChannelHandlerContext ctx, GamePacket packet) {
        // 服务端直接处理解散
        HallSystemProto.DisbandPartyScRsp rsp = hallNettyService.handleDisbandParty(ctx.channel());
        // 写出响应（命令号 127）
        ctx.writeAndFlush(new GamePacket(CmdIds.DISBAND_PARTY_SC_RSP, rsp.toByteArray()));
    }

    /**
     * 查询队伍信息（GET_PARTY_INFO_CS_REQ=128）。
     * 无上行请求体；返回当前玩家所在队伍及成员信息。
     */
    @PacketCmd(CmdIds.GET_PARTY_INFO_CS_REQ) // 绑定上行命令号 128
    public void onGetPartyInfo(ChannelHandlerContext ctx, GamePacket packet) {
        // 服务端直接查询队伍信息
        HallSystemProto.GetPartyInfoScRsp rsp = hallNettyService.handleGetPartyInfo(ctx.channel());
        // 写出响应（命令号 129）
        ctx.writeAndFlush(new GamePacket(CmdIds.GET_PARTY_INFO_SC_RSP, rsp.toByteArray()));
    }
}
