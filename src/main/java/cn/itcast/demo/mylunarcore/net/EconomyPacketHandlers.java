// 经济系统协议处理器所在包：将上行 cmdId 路由到 EconomyNettyService 业务方法
package cn.itcast.demo.mylunarcore.net;

import cn.itcast.demo.mylunarcore.economy.EconomyNettyService;
import cn.itcast.demo.mylunarcore.protocol.EconomySystemProto;
import io.netty.channel.ChannelHandlerContext;
import org.springframework.stereotype.Component;

/**
 * 经济系统协议入口处理器：游戏币商店 + IAP 下单/验单/月卡日领。
 */
@Component
public class EconomyPacketHandlers {

    private final EconomyNettyService economyNettyService;

    public EconomyPacketHandlers(EconomyNettyService economyNettyService) {
        this.economyNettyService = economyNettyService;
    }

    @PacketCmd(CmdIds.GET_SHOP_LIST_CS_REQ)
    public void onGetShopList(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        EconomySystemProto.GetShopListCsReq req = EconomySystemProto.GetShopListCsReq.parseFrom(packet.getPayload());
        EconomySystemProto.GetShopListScRsp rsp = economyNettyService.handleGetShopList(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.GET_SHOP_LIST_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.BUY_SHOP_ITEM_CS_REQ)
    public void onBuyShopItem(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        EconomySystemProto.BuyShopItemCsReq req = EconomySystemProto.BuyShopItemCsReq.parseFrom(packet.getPayload());
        EconomySystemProto.BuyShopItemScRsp rsp = economyNettyService.handleBuyShopItem(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.BUY_SHOP_ITEM_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.CREATE_IAP_ORDER_CS_REQ)
    public void onCreateIapOrder(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        EconomySystemProto.CreateIapOrderCsReq req =
                EconomySystemProto.CreateIapOrderCsReq.parseFrom(packet.getPayload());
        EconomySystemProto.CreateIapOrderScRsp rsp =
                economyNettyService.handleCreateIapOrder(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.CREATE_IAP_ORDER_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.CONFIRM_IAP_ORDER_CS_REQ)
    public void onConfirmIapOrder(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        EconomySystemProto.ConfirmIapOrderCsReq req =
                EconomySystemProto.ConfirmIapOrderCsReq.parseFrom(packet.getPayload());
        EconomySystemProto.ConfirmIapOrderScRsp rsp =
                economyNettyService.handleConfirmIapOrder(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.CONFIRM_IAP_ORDER_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.CLAIM_IAP_DAILY_CS_REQ)
    public void onClaimIapDaily(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        EconomySystemProto.ClaimIapDailyCsReq req =
                EconomySystemProto.ClaimIapDailyCsReq.parseFrom(packet.getPayload());
        EconomySystemProto.ClaimIapDailyScRsp rsp =
                economyNettyService.handleClaimIapDaily(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.CLAIM_IAP_DAILY_SC_RSP, rsp.toByteArray()));
    }
}
