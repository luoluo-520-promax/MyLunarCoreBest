package cn.itcast.demo.mylunarcore.net; // 协议处理器与 CmdId 路由所在包

import cn.itcast.demo.mylunarcore.protocol.SettingsSystemProto; // 设置系统 Protobuf 消息
import cn.itcast.demo.mylunarcore.settings.SettingsNettyService; // 设置与客服业务门面
import io.netty.channel.ChannelHandlerContext; // 当前连接上下文，用于回写响应
import org.springframework.stereotype.Component; // 扫描进 PacketCommandRegistry

/**
 * 游戏设置与客服工单协议入口。
 * <p>每个方法用 {@link PacketCmd} 绑定上行 CmdId；流程固定为：反序列化请求 → 调门面 → 写回对应 SC_RSP。
 */
@Component // 启动时由 PacketCommandRegistry 扫描注册
public class SettingsPacketHandlers {

    /**
     * 设置/工单协议业务门面，完成鉴权、落库与推送
     */
    private final SettingsNettyService settingsNettyService;

    /**
     * 构造注入 SettingsNettyService
     */
    public SettingsPacketHandlers(SettingsNettyService settingsNettyService) {
        this.settingsNettyService = settingsNettyService; // 保存门面引用
    } // 构造结束

    /**
     * 处理「拉取玩家设置」上行包（GET_PLAYER_SETTINGS_CS_REQ=930），回包 931
     */
    @PacketCmd(CmdIds.GET_PLAYER_SETTINGS_CS_REQ) // 绑定命令号 930
    public void onGetPlayerSettings(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        SettingsSystemProto.GetPlayerSettingsCsReq req =
                SettingsSystemProto.GetPlayerSettingsCsReq.parseFrom(packet.getPayload()); // 反序列化空请求体亦可
        SettingsSystemProto.GetPlayerSettingsScRsp rsp =
                settingsNettyService.handleGetPlayerSettings(req, ctx.channel()); // 读设置或建默认
        ctx.writeAndFlush(new GamePacket(CmdIds.GET_PLAYER_SETTINGS_SC_RSP, rsp.toByteArray())); // 下行 931
    } // onGetPlayerSettings 结束

    /**
     * 处理「更新玩家设置」上行包（UPDATE_PLAYER_SETTINGS_CS_REQ=932），回包 933，门面内另推 936
     */
    @PacketCmd(CmdIds.UPDATE_PLAYER_SETTINGS_CS_REQ) // 绑定命令号 932
    public void onUpdatePlayerSettings(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        SettingsSystemProto.UpdatePlayerSettingsCsReq req =
                SettingsSystemProto.UpdatePlayerSettingsCsReq.parseFrom(packet.getPayload()); // 解析含分区与 reset 标志的请求
        SettingsSystemProto.UpdatePlayerSettingsScRsp rsp =
                settingsNettyService.handleUpdatePlayerSettings(req, ctx.channel()); // 合并更新并推送
        ctx.writeAndFlush(new GamePacket(CmdIds.UPDATE_PLAYER_SETTINGS_SC_RSP, rsp.toByteArray())); // 下行 933
    } // onUpdatePlayerSettings 结束

    /**
     * 处理「重置玩家设置」上行包（RESET_PLAYER_SETTINGS_CS_REQ=934），回包 935
     */
    @PacketCmd(CmdIds.RESET_PLAYER_SETTINGS_CS_REQ) // 绑定命令号 934
    public void onResetPlayerSettings(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        SettingsSystemProto.ResetPlayerSettingsCsReq req =
                SettingsSystemProto.ResetPlayerSettingsCsReq.parseFrom(packet.getPayload()); // 解析全量/分区重置标志
        SettingsSystemProto.ResetPlayerSettingsScRsp rsp =
                settingsNettyService.handleResetPlayerSettings(req, ctx.channel()); // 恢复默认并推送
        ctx.writeAndFlush(new GamePacket(CmdIds.RESET_PLAYER_SETTINGS_SC_RSP, rsp.toByteArray())); // 下行 935
    } // onResetPlayerSettings 结束

    /**
     * 处理「提交客服工单」上行包（SUBMIT_SUPPORT_TICKET_CS_REQ=937），回包 938
     */
    @PacketCmd(CmdIds.SUBMIT_SUPPORT_TICKET_CS_REQ) // 绑定命令号 937
    public void onSubmitSupportTicket(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        SettingsSystemProto.SubmitSupportTicketCsReq req =
                SettingsSystemProto.SubmitSupportTicketCsReq.parseFrom(packet.getPayload()); // 解析分类/标题/正文
        SettingsSystemProto.SubmitSupportTicketScRsp rsp =
                settingsNettyService.handleSubmitSupportTicket(req, ctx.channel()); // 落库工单
        ctx.writeAndFlush(new GamePacket(CmdIds.SUBMIT_SUPPORT_TICKET_SC_RSP, rsp.toByteArray())); // 下行 938
    } // onSubmitSupportTicket 结束

    /**
     * 处理「查询本人工单列表」上行包（GET_SUPPORT_TICKET_LIST_CS_REQ=939），回包 940
     */
    @PacketCmd(CmdIds.GET_SUPPORT_TICKET_LIST_CS_REQ) // 绑定命令号 939
    public void onGetSupportTicketList(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        SettingsSystemProto.GetSupportTicketListCsReq req =
                SettingsSystemProto.GetSupportTicketListCsReq.parseFrom(packet.getPayload()); // 解析分页参数
        SettingsSystemProto.GetSupportTicketListScRsp rsp =
                settingsNettyService.handleGetSupportTicketList(req, ctx.channel()); // 查本人工单
        ctx.writeAndFlush(new GamePacket(CmdIds.GET_SUPPORT_TICKET_LIST_SC_RSP, rsp.toByteArray())); // 下行 940
    } // onGetSupportTicketList 结束

    @PacketCmd(CmdIds.SYNC_KEY_BIND_CS_REQ)
    public void onSyncKeyBind(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        SettingsSystemProto.SyncKeyBindCsReq req = SettingsSystemProto.SyncKeyBindCsReq.parseFrom(
                packet.getPayload() == null ? new byte[0] : packet.getPayload());
        SettingsSystemProto.SyncKeyBindScRsp rsp = settingsNettyService.handleSyncKeyBind(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.SYNC_KEY_BIND_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.GET_DEVICE_HAPTICS_CS_REQ)
    public void onGetDeviceHaptics(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        SettingsSystemProto.GetDeviceHapticsCsReq req = SettingsSystemProto.GetDeviceHapticsCsReq.parseFrom(
                packet.getPayload() == null ? new byte[0] : packet.getPayload());
        SettingsSystemProto.GetDeviceHapticsScRsp rsp =
                settingsNettyService.handleGetDeviceHaptics(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.GET_DEVICE_HAPTICS_SC_RSP, rsp.toByteArray()));
    }
} // SettingsPacketHandlers 结束
