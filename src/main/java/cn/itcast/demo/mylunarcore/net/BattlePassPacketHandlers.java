package cn.itcast.demo.mylunarcore.net;

import cn.itcast.demo.mylunarcore.battlepass.BattlePassNettyService;
import cn.itcast.demo.mylunarcore.protocol.BattlePassSystemProto;
import io.netty.channel.ChannelHandlerContext;
import org.springframework.stereotype.Component;

/**
 * 战令协议入口（CmdId 990–995）。
 */
@Component
public class BattlePassPacketHandlers {

    private final BattlePassNettyService battlePassNettyService;

    public BattlePassPacketHandlers(BattlePassNettyService battlePassNettyService) {
        this.battlePassNettyService = battlePassNettyService;
    }

    @PacketCmd(CmdIds.GET_BATTLE_PASS_CS_REQ)
    public void onGet(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        BattlePassSystemProto.GetBattlePassCsReq req = packet.getPayload() == null || packet.getPayload().length == 0
                ? BattlePassSystemProto.GetBattlePassCsReq.getDefaultInstance()
                : BattlePassSystemProto.GetBattlePassCsReq.parseFrom(packet.getPayload());
        BattlePassSystemProto.GetBattlePassScRsp rsp = battlePassNettyService.handleGet(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.GET_BATTLE_PASS_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.CLAIM_BATTLE_PASS_CS_REQ)
    public void onClaim(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        BattlePassSystemProto.ClaimBattlePassCsReq req =
                BattlePassSystemProto.ClaimBattlePassCsReq.parseFrom(
                        packet.getPayload() == null ? new byte[0] : packet.getPayload());
        BattlePassSystemProto.ClaimBattlePassScRsp rsp = battlePassNettyService.handleClaim(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.CLAIM_BATTLE_PASS_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.BUY_BATTLE_PASS_PREMIUM_CS_REQ)
    public void onBuyPremium(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        BattlePassSystemProto.BuyBattlePassPremiumCsReq req =
                BattlePassSystemProto.BuyBattlePassPremiumCsReq.parseFrom(
                        packet.getPayload() == null ? new byte[0] : packet.getPayload());
        BattlePassSystemProto.BuyBattlePassPremiumScRsp rsp =
                battlePassNettyService.handleBuyPremium(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.BUY_BATTLE_PASS_PREMIUM_SC_RSP, rsp.toByteArray()));
    }
}
