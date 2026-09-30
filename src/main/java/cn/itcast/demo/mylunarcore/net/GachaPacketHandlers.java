package cn.itcast.demo.mylunarcore.net;

import cn.itcast.demo.mylunarcore.gacha.GachaNettyService;
import cn.itcast.demo.mylunarcore.player.PlayerContextResolver;
import cn.itcast.demo.mylunarcore.protocol.GachaSystemProto;
import io.netty.channel.ChannelHandlerContext;
import org.springframework.stereotype.Component;

/**
 * 抽卡命令入口：拉取卡池信息、抽卡与兑换保底等；抽卡动作叠加 UID/IP 限流。
 */
@Component
public class GachaPacketHandlers {

    private final GachaNettyService gachaNettyService;
    private final SensitiveApiRateLimiter sensitiveApiRateLimiter;
    private final PlayerContextResolver playerContextResolver;

    public GachaPacketHandlers(GachaNettyService gachaNettyService,
                               SensitiveApiRateLimiter sensitiveApiRateLimiter,
                               PlayerContextResolver playerContextResolver) {
        this.gachaNettyService = gachaNettyService;
        this.sensitiveApiRateLimiter = sensitiveApiRateLimiter;
        this.playerContextResolver = playerContextResolver;
    }

    @PacketCmd(CmdIds.GET_GACHA_INFO_CS_REQ)
    public void onGetGachaInfo(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        GachaSystemProto.GetGachaInfoScRsp rsp = gachaNettyService.handleGetGachaInfo(ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.GET_GACHA_INFO_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.DO_GACHA_CS_REQ)
    public void onDoGacha(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        int playerId = playerContextResolver.resolvePlayerId(ctx.channel());
        String ip = resolveIp(ctx);
        if (!sensitiveApiRateLimiter.tryAcquireGacha(ip, playerId)) {
            GachaSystemProto.DoGachaScRsp limited = GachaSystemProto.DoGachaScRsp.newBuilder()
                    .setRetcode(8)
                    .build();
            ctx.writeAndFlush(new GamePacket(CmdIds.DO_GACHA_SC_RSP, limited.toByteArray()));
            return;
        }
        byte[] payload = packet.getPayload();
        GachaSystemProto.DoGachaCsReq req = GachaSystemProto.DoGachaCsReq.parseFrom(
                payload == null ? new byte[0] : payload);
        GachaSystemProto.DoGachaScRsp rsp = gachaNettyService.handleDoGacha(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.DO_GACHA_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.EXCHANGE_GACHA_CEILING_CS_REQ)
    public void onExchangeCeiling(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        byte[] payload = packet.getPayload();
        GachaSystemProto.ExchangeGachaCeilingCsReq req =
                GachaSystemProto.ExchangeGachaCeilingCsReq.parseFrom(
                        payload == null ? new byte[0] : payload);
        GachaSystemProto.ExchangeGachaCeilingScRsp rsp =
                gachaNettyService.handleExchangeCeiling(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.EXCHANGE_GACHA_CEILING_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.GET_GACHA_HISTORY_CS_REQ)
    public void onGetHistory(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        byte[] payload = packet.getPayload();
        GachaSystemProto.GetGachaHistoryCsReq req = GachaSystemProto.GetGachaHistoryCsReq.parseFrom(
                payload == null ? new byte[0] : payload);
        GachaSystemProto.GetGachaHistoryScRsp rsp = gachaNettyService.handleGetHistory(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.GET_GACHA_HISTORY_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.GACHA_START_CS_REQ)
    public void onGachaStart(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        byte[] payload = packet.getPayload();
        GachaSystemProto.GachaStartCsReq req = GachaSystemProto.GachaStartCsReq.parseFrom(
                payload == null ? new byte[0] : payload);
        GachaSystemProto.GachaStartScRsp rsp = gachaNettyService.handleGachaStart(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.GACHA_START_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.GACHA_RESULT_ACK_CS_REQ)
    public void onGachaResultAck(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        byte[] payload = packet.getPayload();
        GachaSystemProto.GachaResultAckCsReq req = GachaSystemProto.GachaResultAckCsReq.parseFrom(
                payload == null ? new byte[0] : payload);
        GachaSystemProto.GachaResultAckScRsp rsp = gachaNettyService.handleGachaResultAck(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.GACHA_RESULT_ACK_SC_RSP, rsp.toByteArray()));
    }

    private static String resolveIp(ChannelHandlerContext ctx) {
        try {
            if (ctx.channel().remoteAddress() instanceof java.net.InetSocketAddress isa) {
                return isa.getAddress().getHostAddress();
            }
        } catch (Exception ignored) {
        }
        return "unknown";
    }
}
