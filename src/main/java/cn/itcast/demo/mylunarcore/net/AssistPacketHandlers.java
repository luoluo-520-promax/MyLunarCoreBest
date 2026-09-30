package cn.itcast.demo.mylunarcore.net;

import cn.itcast.demo.mylunarcore.assist.AssistNettyService;
import cn.itcast.demo.mylunarcore.protocol.AssistSystemProto;
import io.netty.channel.ChannelHandlerContext;
import org.springframework.stereotype.Component;

/**
 * AI 辅助协议入口：AskCoachHint / AskAiAssist / Feedback / LineupRecommend。
 */
@Component
public class AssistPacketHandlers {

    private final AssistNettyService assistNettyService;

    public AssistPacketHandlers(AssistNettyService assistNettyService) {
        this.assistNettyService = assistNettyService;
    }

    @PacketCmd(CmdIds.ASK_COACH_HINT_CS_REQ)
    public void onAskCoachHint(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        AssistSystemProto.AskCoachHintCsReq req = AssistSystemProto.AskCoachHintCsReq.parseFrom(packet.getPayload());
        AssistSystemProto.AskCoachHintScRsp rsp = assistNettyService.handleAskCoachHint(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.ASK_COACH_HINT_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.ASK_AI_ASSIST_CS_REQ)
    public void onAskAiAssist(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        AssistSystemProto.AskAiAssistCsReq req = AssistSystemProto.AskAiAssistCsReq.parseFrom(packet.getPayload());
        AssistSystemProto.AskAiAssistScRsp rsp = assistNettyService.handleAskAiAssist(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.ASK_AI_ASSIST_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.GET_GUIDE_PACK_CS_REQ)
    public void onGetGuidePack(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        AssistSystemProto.GetGuidePackCsReq req = AssistSystemProto.GetGuidePackCsReq.parseFrom(packet.getPayload());
        AssistSystemProto.GetGuidePackScRsp rsp = assistNettyService.handleGetGuidePack(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.GET_GUIDE_PACK_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.ASSIST_FEEDBACK_CS_REQ)
    public void onAssistFeedback(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        AssistSystemProto.AssistFeedbackCsReq req = AssistSystemProto.AssistFeedbackCsReq.parseFrom(packet.getPayload());
        AssistSystemProto.AssistFeedbackScRsp rsp = assistNettyService.handleAssistFeedback(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.ASSIST_FEEDBACK_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.ASK_LINEUP_RECOMMEND_CS_REQ)
    public void onAskLineupRecommend(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        AssistSystemProto.AskLineupRecommendCsReq req =
                AssistSystemProto.AskLineupRecommendCsReq.parseFrom(packet.getPayload());
        AssistSystemProto.AskLineupRecommendScRsp rsp =
                assistNettyService.handleAskLineupRecommend(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.ASK_LINEUP_RECOMMEND_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.ASK_EXPLORE_PATH_CS_REQ)
    public void onAskExplorePath(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        AssistSystemProto.AskExplorePathCsReq req =
                AssistSystemProto.AskExplorePathCsReq.parseFrom(packet.getPayload());
        AssistSystemProto.AskExplorePathScRsp rsp =
                assistNettyService.handleAskExplorePath(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.ASK_EXPLORE_PATH_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.ASK_QUEST_GUIDANCE_CS_REQ)
    public void onAskQuestGuidance(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        AssistSystemProto.AskQuestGuidanceCsReq req =
                AssistSystemProto.AskQuestGuidanceCsReq.parseFrom(packet.getPayload());
        AssistSystemProto.AskQuestGuidanceScRsp rsp =
                assistNettyService.handleAskQuestGuidance(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.ASK_QUEST_GUIDANCE_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.UPLOAD_SCREENSHOT_CS_REQ)
    public void onUploadScreenshot(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        AssistSystemProto.UploadScreenshotCsReq req =
                AssistSystemProto.UploadScreenshotCsReq.parseFrom(
                        packet.getPayload() == null ? new byte[0] : packet.getPayload());
        AssistSystemProto.UploadScreenshotScRsp rsp =
                assistNettyService.handleUploadScreenshot(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.UPLOAD_SCREENSHOT_SC_RSP, rsp.toByteArray()));
    }
}
