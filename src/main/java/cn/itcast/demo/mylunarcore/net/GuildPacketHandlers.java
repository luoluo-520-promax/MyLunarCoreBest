package cn.itcast.demo.mylunarcore.net;

import cn.itcast.demo.mylunarcore.guild.GuildNettyService;
import cn.itcast.demo.mylunarcore.protocol.GuildSystemProto;
import io.netty.channel.ChannelHandlerContext;
import org.springframework.stereotype.Component;

/**
 * 公会协议入口（CmdId 970–989）。
 */
@Component
public class GuildPacketHandlers {

    private final GuildNettyService guildNettyService;

    public GuildPacketHandlers(GuildNettyService guildNettyService) {
        this.guildNettyService = guildNettyService;
    }

    @PacketCmd(CmdIds.CREATE_GUILD_CS_REQ)
    public void onCreate(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        GuildSystemProto.CreateGuildCsReq req =
                GuildSystemProto.CreateGuildCsReq.parseFrom(packet.getPayload());
        GuildSystemProto.CreateGuildScRsp rsp = guildNettyService.handleCreate(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.CREATE_GUILD_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.JOIN_GUILD_CS_REQ)
    public void onJoin(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        GuildSystemProto.JoinGuildCsReq req =
                GuildSystemProto.JoinGuildCsReq.parseFrom(packet.getPayload());
        GuildSystemProto.JoinGuildScRsp rsp = guildNettyService.handleJoin(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.JOIN_GUILD_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.LEAVE_GUILD_CS_REQ)
    public void onLeave(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        GuildSystemProto.LeaveGuildScRsp rsp = guildNettyService.handleLeave(ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.LEAVE_GUILD_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.GET_GUILD_INFO_CS_REQ)
    public void onGetInfo(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        GuildSystemProto.GetGuildInfoScRsp rsp = guildNettyService.handleGetInfo(ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.GET_GUILD_INFO_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.CONTRIBUTE_GUILD_CS_REQ)
    public void onContribute(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        GuildSystemProto.ContributeGuildCsReq req =
                GuildSystemProto.ContributeGuildCsReq.parseFrom(packet.getPayload());
        GuildSystemProto.ContributeGuildScRsp rsp = guildNettyService.handleContribute(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.CONTRIBUTE_GUILD_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.GET_GUILD_SHOP_CS_REQ)
    public void onGetShop(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        GuildSystemProto.GetGuildShopScRsp rsp = guildNettyService.handleGetShop(ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.GET_GUILD_SHOP_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.BUY_GUILD_SHOP_CS_REQ)
    public void onBuyShop(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        GuildSystemProto.BuyGuildShopCsReq req =
                GuildSystemProto.BuyGuildShopCsReq.parseFrom(packet.getPayload());
        GuildSystemProto.BuyGuildShopScRsp rsp = guildNettyService.handleBuyShop(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.BUY_GUILD_SHOP_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.GUILD_WAR_MATCH_CS_REQ)
    public void onWarMatch(ChannelHandlerContext ctx, GamePacket packet) {
        GuildSystemProto.GuildWarMatchScRsp rsp = guildNettyService.handleWarMatch(ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.GUILD_WAR_MATCH_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.GUILD_WAR_REPORT_CS_REQ)
    public void onWarReport(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        GuildSystemProto.GuildWarReportCsReq req =
                GuildSystemProto.GuildWarReportCsReq.parseFrom(packet.getPayload());
        GuildSystemProto.GuildWarReportScRsp rsp = guildNettyService.handleWarReport(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.GUILD_WAR_REPORT_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.GUILD_WAR_RANK_CS_REQ)
    public void onWarRank(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        GuildSystemProto.GuildWarRankCsReq req =
                GuildSystemProto.GuildWarRankCsReq.parseFrom(packet.getPayload());
        GuildSystemProto.GuildWarRankScRsp rsp = guildNettyService.handleWarRank(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.GUILD_WAR_RANK_SC_RSP, rsp.toByteArray()));
    }
}
