// 网络命令处理器实现所在包（战斗系统相关）
package cn.itcast.demo.mylunarcore.net;

// 战斗业务 Netty 门面
import cn.itcast.demo.mylunarcore.battle.BattleNettyService;
// 战斗相关 cmdId
import cn.itcast.demo.mylunarcore.net.CmdIds;
// 解码后的业务包
import cn.itcast.demo.mylunarcore.net.GamePacket;
// 战斗系统 Protobuf
import cn.itcast.demo.mylunarcore.protocol.BattleSystemProto;
// Netty 上下文
import io.netty.channel.ChannelHandlerContext;
// Spring 组件
import org.springframework.stereotype.Component;

/**
 * 战斗系统命令号入口：开战、战斗内操作与结算，委托 {@link cn.itcast.demo.mylunarcore.battle.BattleNettyService}。
 */
@Component // 注册为 Spring Bean
public class BattlePacketHandlers {

    private final BattleNettyService battleNettyService; // 战斗域服务（不可变依赖）

    /**
     * 构造器注入战斗服务。
     */
    public BattlePacketHandlers(BattleNettyService battleNettyService) {
        this.battleNettyService = battleNettyService; // 保存引用
    }

    /**
     * 客户端请求开战。
     */
    @PacketCmd(CmdIds.FIGHT_START_CS_REQ)
    public void onFightStart(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        byte[] payload = packet.getPayload(); // 请求正文
        BattleSystemProto.FightStartCsReq req = BattleSystemProto.FightStartCsReq.parseFrom(payload); // 反序列化
        BattleSystemProto.FightStartScRsp rsp = battleNettyService.handleFightStart(req, ctx.channel()); // 业务处理
        ctx.writeAndFlush(new GamePacket(CmdIds.FIGHT_START_SC_RSP, rsp.toByteArray())); // 写回响应
    }

    /**
     * 战斗内进行动作（技能/普攻等）。
     */
    @PacketCmd(CmdIds.FIGHT_ACTION_CS_REQ)
    public void onFightAction(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        byte[] payload = packet.getPayload(); // 请求正文
        BattleSystemProto.FightActionCsReq req = BattleSystemProto.FightActionCsReq.parseFrom(payload); // 反序列化
        BattleSystemProto.FightActionScRsp rsp = battleNettyService.handleFightAction(req, ctx.channel()); // 业务处理
        ctx.writeAndFlush(new GamePacket(CmdIds.FIGHT_ACTION_SC_RSP, rsp.toByteArray())); // 写回响应
    }

    /**
     * 客户端上报战斗结算结果。
     */
    @PacketCmd(CmdIds.FIGHT_RESULT_CS_REQ)
    public void onFightResult(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        byte[] payload = packet.getPayload(); // 请求正文
        BattleSystemProto.FightResultCsReq req = BattleSystemProto.FightResultCsReq.parseFrom(payload); // 反序列化
        BattleSystemProto.FightResultScRsp rsp = battleNettyService.handleFightResult(req, ctx.channel()); // 业务处理
        ctx.writeAndFlush(new GamePacket(CmdIds.FIGHT_RESULT_SC_RSP, rsp.toByteArray())); // 写回响应
    }

    /**
     * 客户端请求退出当前战斗实例。
     */
    @PacketCmd(CmdIds.FIGHT_QUIT_CS_REQ)
    public void onFightQuit(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        byte[] payload = packet.getPayload(); // 请求正文
        BattleSystemProto.FightQuitCsReq req = BattleSystemProto.FightQuitCsReq.parseFrom(payload); // 反序列化
        BattleSystemProto.FightQuitScRsp rsp = battleNettyService.handleFightQuit(req, ctx.channel()); // 业务处理
        ctx.writeAndFlush(new GamePacket(CmdIds.FIGHT_QUIT_SC_RSP, rsp.toByteArray())); // 写回响应
    }

    /**
     * 查询当前战斗实例信息。
     */
    @PacketCmd(CmdIds.GET_BATTLE_INFO_CS_REQ)
    public void onGetBattleInfo(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        byte[] payload = packet.getPayload(); // 请求正文
        BattleSystemProto.GetBattleInfoCsReq req = BattleSystemProto.GetBattleInfoCsReq.parseFrom(payload); // 反序列化
        BattleSystemProto.GetBattleInfoScRsp rsp = battleNettyService.handleGetBattleInfo(req, ctx.channel()); // 业务处理
        ctx.writeAndFlush(new GamePacket(CmdIds.GET_BATTLE_INFO_SC_RSP, rsp.toByteArray())); // 写回响应
    }
}
