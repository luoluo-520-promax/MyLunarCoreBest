package cn.itcast.demo.mylunarcore.net;

import cn.itcast.demo.mylunarcore.home.HomeNettyService;
import cn.itcast.demo.mylunarcore.protocol.HomeSystemProto;
import io.netty.channel.ChannelHandlerContext;
import org.springframework.stereotype.Component;

/**
 * 家园协议入口（CmdId 850–857）。
 */
@Component
public class HomePacketHandlers {

    private final HomeNettyService homeNettyService;

    public HomePacketHandlers(HomeNettyService homeNettyService) {
        this.homeNettyService = homeNettyService;
    }

    @PacketCmd(CmdIds.GET_HOME_INFO_CS_REQ)
    public void onGetInfo(ChannelHandlerContext ctx, GamePacket packet) {
        HomeSystemProto.GetHomeInfoScRsp rsp = homeNettyService.handleGetInfo(ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.GET_HOME_INFO_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.HOME_PLACE_FACILITY_CS_REQ)
    public void onPlace(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        HomeSystemProto.HomePlaceFacilityCsReq req =
                HomeSystemProto.HomePlaceFacilityCsReq.parseFrom(packet.getPayload());
        HomeSystemProto.HomePlaceFacilityScRsp rsp = homeNettyService.handlePlace(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.HOME_PLACE_FACILITY_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.HOME_CLAIM_PRODUCE_CS_REQ)
    public void onClaim(ChannelHandlerContext ctx, GamePacket packet) {
        HomeSystemProto.HomeClaimProduceScRsp rsp = homeNettyService.handleClaim(ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.HOME_CLAIM_PRODUCE_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.HOME_VISIT_CS_REQ)
    public void onVisit(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        HomeSystemProto.HomeVisitCsReq req = HomeSystemProto.HomeVisitCsReq.parseFrom(packet.getPayload());
        HomeSystemProto.HomeVisitScRsp rsp = homeNettyService.handleVisit(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.HOME_VISIT_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.HOME_PLACE_FURNITURE_CS_REQ)
    public void onPlaceFurniture(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        HomeSystemProto.HomePlaceFurnitureCsReq req =
                HomeSystemProto.HomePlaceFurnitureCsReq.parseFrom(
                        packet.getPayload() == null ? new byte[0] : packet.getPayload());
        HomeSystemProto.HomePlaceFurnitureScRsp rsp = homeNettyService.handlePlaceFurniture(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.HOME_PLACE_FURNITURE_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.HOME_FURNITURE_INTERACT_CS_REQ)
    public void onFurnitureInteract(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        HomeSystemProto.HomeFurnitureInteractCsReq req =
                HomeSystemProto.HomeFurnitureInteractCsReq.parseFrom(
                        packet.getPayload() == null ? new byte[0] : packet.getPayload());
        HomeSystemProto.HomeFurnitureInteractScRsp rsp =
                homeNettyService.handleFurnitureInteract(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.HOME_FURNITURE_INTERACT_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.HOME_UPDATE_PRESENCE_CS_REQ)
    public void onUpdatePresence(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        HomeSystemProto.HomeUpdatePresenceCsReq req =
                HomeSystemProto.HomeUpdatePresenceCsReq.parseFrom(
                        packet.getPayload() == null ? new byte[0] : packet.getPayload());
        HomeSystemProto.HomeUpdatePresenceScRsp rsp =
                homeNettyService.handleUpdatePresence(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.HOME_UPDATE_PRESENCE_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.GET_HOME_VISITOR_LOG_CS_REQ)
    public void onGetVisitorLog(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        HomeSystemProto.GetHomeVisitorLogCsReq req =
                HomeSystemProto.GetHomeVisitorLogCsReq.parseFrom(
                        packet.getPayload() == null ? new byte[0] : packet.getPayload());
        HomeSystemProto.GetHomeVisitorLogScRsp rsp =
                homeNettyService.handleGetVisitorLog(req.getLimit(), ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.GET_HOME_VISITOR_LOG_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.HOME_LEAVE_MESSAGE_CS_REQ)
    public void onLeaveMessage(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        HomeSystemProto.HomeLeaveMessageCsReq req =
                HomeSystemProto.HomeLeaveMessageCsReq.parseFrom(
                        packet.getPayload() == null ? new byte[0] : packet.getPayload());
        HomeSystemProto.HomeLeaveMessageScRsp rsp =
                homeNettyService.handleLeaveMessage(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.HOME_LEAVE_MESSAGE_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.HOME_HARVEST_ASSIST_CS_REQ)
    public void onHarvestAssist(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        HomeSystemProto.HomeHarvestAssistCsReq req =
                HomeSystemProto.HomeHarvestAssistCsReq.parseFrom(
                        packet.getPayload() == null ? new byte[0] : packet.getPayload());
        HomeSystemProto.HomeHarvestAssistScRsp rsp = homeNettyService.handleHarvestAssist(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.HOME_HARVEST_ASSIST_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.HOME_SHADOW_GREETING_CS_REQ)
    public void onShadowGreeting(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        HomeSystemProto.HomeShadowGreetingCsReq req =
                HomeSystemProto.HomeShadowGreetingCsReq.parseFrom(
                        packet.getPayload() == null ? new byte[0] : packet.getPayload());
        HomeSystemProto.HomeShadowGreetingScRsp rsp = homeNettyService.handleShadowGreeting(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.HOME_SHADOW_GREETING_SC_RSP, rsp.toByteArray()));
    }
}
