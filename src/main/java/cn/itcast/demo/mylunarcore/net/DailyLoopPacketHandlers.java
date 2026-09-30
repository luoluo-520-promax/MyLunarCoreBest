package cn.itcast.demo.mylunarcore.net;

import cn.itcast.demo.mylunarcore.player.PlayerContextResolver;
import cn.itcast.demo.mylunarcore.player.StaminaNettyService;
import cn.itcast.demo.mylunarcore.protocol.DailyLoopSystemProto;
import cn.itcast.demo.mylunarcore.quest.DailyMissionNettyService;
import cn.itcast.demo.mylunarcore.story.StoryChapterNettyService;
import cn.itcast.demo.mylunarcore.challenge.SweepNettyService;
import io.netty.channel.ChannelHandlerContext;
import org.springframework.stereotype.Component;

/**
 * 日常循环协议入口：体力 / 每日任务 / 章节（CmdId 996–1008）。
 */
@Component
public class DailyLoopPacketHandlers {

    private final StaminaNettyService staminaNettyService;
    private final DailyMissionNettyService dailyMissionNettyService;
    private final StoryChapterNettyService storyChapterNettyService;
    private final SweepNettyService sweepNettyService;
    private final PlayerContextResolver playerContextResolver;
    private final SensitiveApiRateLimiter sensitiveApiRateLimiter;

    public DailyLoopPacketHandlers(StaminaNettyService staminaNettyService,
                                   DailyMissionNettyService dailyMissionNettyService,
                                   StoryChapterNettyService storyChapterNettyService,
                                   SweepNettyService sweepNettyService,
                                   PlayerContextResolver playerContextResolver,
                                   SensitiveApiRateLimiter sensitiveApiRateLimiter) {
        this.staminaNettyService = staminaNettyService;
        this.dailyMissionNettyService = dailyMissionNettyService;
        this.storyChapterNettyService = storyChapterNettyService;
        this.sweepNettyService = sweepNettyService;
        this.playerContextResolver = playerContextResolver;
        this.sensitiveApiRateLimiter = sensitiveApiRateLimiter;
    }

    @PacketCmd(CmdIds.GET_STAMINA_CS_REQ)
    public void onGetStamina(ChannelHandlerContext ctx, GamePacket packet) {
        DailyLoopSystemProto.GetStaminaScRsp rsp = staminaNettyService.handleGet(ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.GET_STAMINA_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.CLAIM_RESERVE_STAMINA_CS_REQ)
    public void onClaimReserveStamina(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        DailyLoopSystemProto.ClaimReserveStaminaCsReq req =
                DailyLoopSystemProto.ClaimReserveStaminaCsReq.parseFrom(
                        packet.getPayload() == null ? new byte[0] : packet.getPayload());
        DailyLoopSystemProto.ClaimReserveStaminaScRsp rsp =
                staminaNettyService.handleClaimReserve(req.getAmount(), ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.CLAIM_RESERVE_STAMINA_SC_RSP, rsp.toByteArray()));
        if (rsp.getRetcode() == 0) {
            int playerId = playerContextResolver.resolvePlayerId(ctx.channel());
            if (playerId > 0) {
                ctx.writeAndFlush(new GamePacket(CmdIds.STAMINA_UPDATE_SC_NOTIFY,
                        staminaNettyService.buildNotify(playerId, "claim_reserve").toByteArray()));
            }
        }
    }

    @PacketCmd(CmdIds.BUY_STAMINA_CS_REQ)
    public void onBuyStamina(ChannelHandlerContext ctx, GamePacket packet) {
        int playerId = playerContextResolver.resolvePlayerId(ctx.channel());
        String ip = resolveIp(ctx);
        if (!sensitiveApiRateLimiter.tryAcquireStaminaBuy(ip, playerId)) {
            DailyLoopSystemProto.BuyStaminaScRsp limited =
                    DailyLoopSystemProto.BuyStaminaScRsp.newBuilder().setRetcode(8).build();
            ctx.writeAndFlush(new GamePacket(CmdIds.BUY_STAMINA_SC_RSP, limited.toByteArray()));
            return;
        }
        DailyLoopSystemProto.BuyStaminaScRsp rsp = staminaNettyService.handleBuy(ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.BUY_STAMINA_SC_RSP, rsp.toByteArray()));
        if (rsp.getRetcode() == 0 && playerId > 0) {
            DailyLoopSystemProto.StaminaUpdateScNotify notify =
                    staminaNettyService.buildNotify(playerId, "buy");
            ctx.writeAndFlush(new GamePacket(CmdIds.STAMINA_UPDATE_SC_NOTIFY, notify.toByteArray()));
        }
    }

    @PacketCmd(CmdIds.GET_DAILY_MISSION_CS_REQ)
    public void onGetDailyMission(ChannelHandlerContext ctx, GamePacket packet) {
        DailyLoopSystemProto.GetDailyMissionScRsp rsp = dailyMissionNettyService.handleGet(ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.GET_DAILY_MISSION_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.CLAIM_DAILY_MISSION_CS_REQ)
    public void onClaimDailyMission(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        DailyLoopSystemProto.ClaimDailyMissionCsReq req =
                DailyLoopSystemProto.ClaimDailyMissionCsReq.parseFrom(
                        packet.getPayload() == null ? new byte[0] : packet.getPayload());
        DailyLoopSystemProto.ClaimDailyMissionScRsp rsp =
                dailyMissionNettyService.handleClaim(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.CLAIM_DAILY_MISSION_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.GET_STORY_CHAPTER_CS_REQ)
    public void onGetStoryChapter(ChannelHandlerContext ctx, GamePacket packet) {
        DailyLoopSystemProto.GetStoryChapterScRsp rsp = storyChapterNettyService.handleGet(ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.GET_STORY_CHAPTER_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.GET_SWEEP_INFO_CS_REQ)
    public void onGetSweepInfo(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        DailyLoopSystemProto.GetSweepInfoCsReq req = DailyLoopSystemProto.GetSweepInfoCsReq.parseFrom(
                packet.getPayload() == null ? new byte[0] : packet.getPayload());
        DailyLoopSystemProto.GetSweepInfoScRsp rsp = sweepNettyService.handleGetInfo(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.GET_SWEEP_INFO_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.SWEEP_STAGE_CS_REQ)
    public void onSweepStage(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        DailyLoopSystemProto.SweepStageCsReq req = DailyLoopSystemProto.SweepStageCsReq.parseFrom(
                packet.getPayload() == null ? new byte[0] : packet.getPayload());
        DailyLoopSystemProto.SweepStageScRsp rsp = sweepNettyService.handleSweep(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.SWEEP_STAGE_SC_RSP, rsp.toByteArray()));
        if (rsp.getRetcode() == 0) {
            int playerId = playerContextResolver.resolvePlayerId(ctx.channel());
            if (playerId > 0) {
                ctx.writeAndFlush(new GamePacket(CmdIds.STAMINA_UPDATE_SC_NOTIFY,
                        staminaNettyService.buildNotify(playerId, "sweep").toByteArray()));
            }
        }
    }

    @PacketCmd(CmdIds.BATCH_SWEEP_CS_REQ)
    public void onBatchSweep(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        DailyLoopSystemProto.BatchSweepCsReq req = DailyLoopSystemProto.BatchSweepCsReq.parseFrom(
                packet.getPayload() == null ? new byte[0] : packet.getPayload());
        DailyLoopSystemProto.BatchSweepScRsp rsp = sweepNettyService.handleBatch(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.BATCH_SWEEP_SC_RSP, rsp.toByteArray()));
        if (rsp.getRetcode() == 0) {
            int playerId = playerContextResolver.resolvePlayerId(ctx.channel());
            if (playerId > 0) {
                ctx.writeAndFlush(new GamePacket(CmdIds.STAMINA_UPDATE_SC_NOTIFY,
                        staminaNettyService.buildNotify(playerId, "batch_sweep").toByteArray()));
            }
        }
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
