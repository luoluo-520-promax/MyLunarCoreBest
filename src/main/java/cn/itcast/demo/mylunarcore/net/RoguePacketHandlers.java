// 网络命令处理器实现所在包（模拟宇宙 Rogue 相关）
package cn.itcast.demo.mylunarcore.net;

// Rogue 相关 cmdId
import cn.itcast.demo.mylunarcore.net.CmdIds;
// 解码后的业务包
import cn.itcast.demo.mylunarcore.net.GamePacket;
// Rogue 系统 Protobuf
import cn.itcast.demo.mylunarcore.protocol.RogueSystemProto;
// Rogue 领域 Netty 服务
import cn.itcast.demo.mylunarcore.rogue.RogueNettyService;
// Netty 上下文
import io.netty.channel.ChannelHandlerContext;
// Spring 组件
import org.springframework.stereotype.Component;

/**
 * 模拟宇宙命令入口：开局、移动、战斗等 Rogue 协议，委托 {@link cn.itcast.demo.mylunarcore.rogue.RogueNettyService}。
 */
@Component // 注册为 Spring Bean
public class RoguePacketHandlers {

    private final RogueNettyService rogueNettyService; // Rogue 域服务（不可变依赖）

    /**
     * 构造器注入 Rogue 服务。
     */
    public RoguePacketHandlers(RogueNettyService rogueNettyService) {
        this.rogueNettyService = rogueNettyService; // 保存引用
    }

    /**
     * 客户端请求开始一局 Rogue。
     */
    @PacketCmd(CmdIds.START_ROGUE_CS_REQ)
    public void onStartRogue(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        byte[] payload = packet.getPayload(); // 请求正文
        RogueSystemProto.StartRogueCsReq req = RogueSystemProto.StartRogueCsReq.parseFrom(payload); // 反序列化
        RogueSystemProto.StartRogueScRsp rsp = rogueNettyService.handleStartRogue(req, ctx.channel()); // 业务处理
        ctx.writeAndFlush(new GamePacket(CmdIds.START_ROGUE_SC_RSP, rsp.toByteArray())); // 写回响应
    }

    /**
     * 查询当前 Rogue 进度信息。
     */
    @PacketCmd(CmdIds.GET_ROGUE_INFO_CS_REQ)
    public void onGetRogueInfo(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        byte[] payload = packet.getPayload(); // 请求正文
        RogueSystemProto.GetRogueInfoScRsp rsp = rogueNettyService.handleGetRogueInfo(
                RogueSystemProto.GetRogueInfoCsReq.parseFrom(payload), ctx.channel()); // 一行解析并调用服务
        ctx.writeAndFlush(new GamePacket(CmdIds.GET_ROGUE_INFO_SC_RSP, rsp.toByteArray())); // 写回响应
    }

    /**
     * Rogue 地图格移动。
     */
    @PacketCmd(CmdIds.ROGUE_MOVE_CS_REQ)
    public void onRogueMove(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        byte[] payload = packet.getPayload(); // 请求正文
        RogueSystemProto.RogueMoveCsReq req = RogueSystemProto.RogueMoveCsReq.parseFrom(payload); // 反序列化
        RogueSystemProto.RogueMoveScRsp rsp = rogueNettyService.handleMove(req, ctx.channel()); // 业务处理
        ctx.writeAndFlush(new GamePacket(CmdIds.ROGUE_MOVE_SC_RSP, rsp.toByteArray())); // 写回响应
    }

    /**
     * 选择祝福（Buff）。
     */
    @PacketCmd(CmdIds.ROGUE_SELECT_BLESSING_CS_REQ)
    public void onSelectBlessing(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        byte[] payload = packet.getPayload(); // 请求正文
        RogueSystemProto.RogueSelectBlessingCsReq req = RogueSystemProto.RogueSelectBlessingCsReq.parseFrom(payload); // 反序列化
        RogueSystemProto.RogueSelectBlessingScRsp rsp = rogueNettyService.handleSelectBlessing(req, ctx.channel()); // 业务处理
        ctx.writeAndFlush(new GamePacket(CmdIds.ROGUE_SELECT_BLESSING_SC_RSP, rsp.toByteArray())); // 写回响应
    }

    /**
     * 选择奇物。
     */
    @PacketCmd(CmdIds.ROGUE_SELECT_MIRACLE_CS_REQ)
    public void onSelectMiracle(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        byte[] payload = packet.getPayload(); // 请求正文
        RogueSystemProto.RogueSelectMiracleCsReq req = RogueSystemProto.RogueSelectMiracleCsReq.parseFrom(payload); // 反序列化
        RogueSystemProto.RogueSelectMiracleScRsp rsp = rogueNettyService.handleSelectMiracle(req, ctx.channel()); // 业务处理
        ctx.writeAndFlush(new GamePacket(CmdIds.ROGUE_SELECT_MIRACLE_SC_RSP, rsp.toByteArray())); // 写回响应
    }

    /**
     * Rogue 内战斗结算上报。
     */
    @PacketCmd(CmdIds.ROGUE_BATTLE_RESULT_CS_REQ)
    public void onBattleResult(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        byte[] payload = packet.getPayload(); // 请求正文
        RogueSystemProto.RogueBattleResultCsReq req = RogueSystemProto.RogueBattleResultCsReq.parseFrom(payload); // 反序列化
        RogueSystemProto.RogueBattleResultScRsp rsp = rogueNettyService.handleBattleResult(req, ctx.channel()); // 业务处理
        ctx.writeAndFlush(new GamePacket(CmdIds.ROGUE_BATTLE_RESULT_SC_RSP, rsp.toByteArray())); // 写回响应
    }

    /**
     * Rogue 随机事件分支处理。
     */
    @PacketCmd(CmdIds.ROGUE_EVENT_CS_REQ)
    public void onRogueEvent(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        byte[] payload = packet.getPayload(); // 请求正文
        RogueSystemProto.RogueEventCsReq req = RogueSystemProto.RogueEventCsReq.parseFrom(payload); // 反序列化
        RogueSystemProto.RogueEventScRsp rsp = rogueNettyService.handleEvent(req, ctx.channel()); // 业务处理
        ctx.writeAndFlush(new GamePacket(CmdIds.ROGUE_EVENT_SC_RSP, rsp.toByteArray())); // 写回响应
    }

    /**
     * 主动退出 Rogue。
     */
    @PacketCmd(CmdIds.ROGUE_QUIT_CS_REQ)
    public void onRogueQuit(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        byte[] payload = packet.getPayload(); // 请求正文
        RogueSystemProto.RogueQuitScRsp rsp = rogueNettyService.handleQuit(
                RogueSystemProto.RogueQuitCsReq.parseFrom(payload), ctx.channel()); // 解析并退出
        ctx.writeAndFlush(new GamePacket(CmdIds.ROGUE_QUIT_SC_RSP, rsp.toByteArray())); // 写回响应
    }

    /**
     * 查询 Rogue 全局进度/解锁信息。
     */
    @PacketCmd(CmdIds.GET_ROGUE_GLOBAL_INFO_CS_REQ)
    public void onGetGlobalInfo(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        byte[] payload = packet.getPayload(); // 请求正文
        RogueSystemProto.GetRogueGlobalInfoScRsp rsp = rogueNettyService.handleGetGlobalInfo(
                RogueSystemProto.GetRogueGlobalInfoCsReq.parseFrom(payload), ctx.channel()); // 解析并查询
        ctx.writeAndFlush(new GamePacket(CmdIds.GET_ROGUE_GLOBAL_INFO_SC_RSP, rsp.toByteArray())); // 写回响应
    }

    /**
     * 升级 Rogue 天赋树节点。
     */
    @PacketCmd(CmdIds.ROGUE_UPGRADE_TALENT_CS_REQ)
    public void onUpgradeTalent(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        byte[] payload = packet.getPayload(); // 请求正文
        RogueSystemProto.RogueUpgradeTalentCsReq req = RogueSystemProto.RogueUpgradeTalentCsReq.parseFrom(payload); // 反序列化
        RogueSystemProto.RogueUpgradeTalentScRsp rsp = rogueNettyService.handleUpgradeTalent(req, ctx.channel()); // 业务处理
        ctx.writeAndFlush(new GamePacket(CmdIds.ROGUE_UPGRADE_TALENT_SC_RSP, rsp.toByteArray())); // 写回响应
    }

    /**
     * 选择命途/路径。
     */
    @PacketCmd(CmdIds.ROGUE_SELECT_PATH_CS_REQ)
    public void onSelectPath(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        byte[] payload = packet.getPayload(); // 请求正文
        RogueSystemProto.RogueSelectPathCsReq req = RogueSystemProto.RogueSelectPathCsReq.parseFrom(payload); // 反序列化
        RogueSystemProto.RogueSelectPathScRsp rsp = rogueNettyService.handleSelectPath(req, ctx.channel()); // 业务处理
        ctx.writeAndFlush(new GamePacket(CmdIds.ROGUE_SELECT_PATH_SC_RSP, rsp.toByteArray())); // 写回响应
    }
}
