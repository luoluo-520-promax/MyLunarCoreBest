package cn.itcast.demo.mylunarcore.net;

import cn.itcast.demo.mylunarcore.protocol.SkinSystemProto;
import cn.itcast.demo.mylunarcore.skin.SkinNettyService;
import io.netty.channel.ChannelHandlerContext;
import org.springframework.stereotype.Component;

/**
 * 皮肤系统协议入口：衣柜 / 穿戴。
 */
@Component
public class SkinPacketHandlers {

    private final SkinNettyService skinNettyService;

    public SkinPacketHandlers(SkinNettyService skinNettyService) {
        this.skinNettyService = skinNettyService;
    }

    @PacketCmd(CmdIds.GET_SKIN_WARDROBE_CS_REQ)
    public void onGetSkinWardrobe(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        SkinSystemProto.GetSkinWardrobeCsReq req =
                SkinSystemProto.GetSkinWardrobeCsReq.parseFrom(packet.getPayload());
        SkinSystemProto.GetSkinWardrobeScRsp rsp =
                skinNettyService.handleGetSkinWardrobe(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.GET_SKIN_WARDROBE_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.EQUIP_SKIN_CS_REQ)
    public void onEquipSkin(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        SkinSystemProto.EquipSkinCsReq req =
                SkinSystemProto.EquipSkinCsReq.parseFrom(packet.getPayload());
        SkinSystemProto.EquipSkinScRsp rsp =
                skinNettyService.handleEquipSkin(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.EQUIP_SKIN_SC_RSP, rsp.toByteArray()));
    }
}
