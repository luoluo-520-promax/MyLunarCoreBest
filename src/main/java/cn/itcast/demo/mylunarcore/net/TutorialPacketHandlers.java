package cn.itcast.demo.mylunarcore.net;

import cn.itcast.demo.mylunarcore.protocol.NewbieGuideSystemProto;
import cn.itcast.demo.mylunarcore.tutorial.NewbieGuideNettyService;
import io.netty.channel.ChannelHandlerContext;
import org.springframework.stereotype.Component;

/**
 * 新手引导协议入口（CmdId 960–966）。
 */
@Component
public class TutorialPacketHandlers {

    private final NewbieGuideNettyService newbieGuideNettyService;

    public TutorialPacketHandlers(NewbieGuideNettyService newbieGuideNettyService) {
        this.newbieGuideNettyService = newbieGuideNettyService;
    }

    @PacketCmd(CmdIds.GET_NEWBIE_GUIDE_CS_REQ)
    public void onGet(ChannelHandlerContext ctx, GamePacket packet) {
        NewbieGuideSystemProto.GetNewbieGuideScRsp rsp = newbieGuideNettyService.handleGet(ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.GET_NEWBIE_GUIDE_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.ADVANCE_NEWBIE_GUIDE_CS_REQ)
    public void onAdvance(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        NewbieGuideSystemProto.AdvanceNewbieGuideCsReq req =
                NewbieGuideSystemProto.AdvanceNewbieGuideCsReq.parseFrom(
                        packet.getPayload() == null ? new byte[0] : packet.getPayload());
        NewbieGuideSystemProto.AdvanceNewbieGuideScRsp rsp =
                newbieGuideNettyService.handleAdvance(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.ADVANCE_NEWBIE_GUIDE_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.SKIP_NEWBIE_GUIDE_CS_REQ)
    public void onSkip(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        NewbieGuideSystemProto.SkipNewbieGuideCsReq req =
                NewbieGuideSystemProto.SkipNewbieGuideCsReq.parseFrom(
                        packet.getPayload() == null ? new byte[0] : packet.getPayload());
        NewbieGuideSystemProto.SkipNewbieGuideScRsp rsp =
                newbieGuideNettyService.handleSkip(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.SKIP_NEWBIE_GUIDE_SC_RSP, rsp.toByteArray()));
    }
}
