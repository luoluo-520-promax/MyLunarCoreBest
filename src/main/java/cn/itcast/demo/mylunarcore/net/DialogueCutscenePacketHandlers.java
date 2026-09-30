package cn.itcast.demo.mylunarcore.net;

import cn.itcast.demo.mylunarcore.dialogue.DialogueCutsceneNettyService;
import cn.itcast.demo.mylunarcore.protocol.DialogueCutsceneProto;
import io.netty.channel.ChannelHandlerContext;
import org.springframework.stereotype.Component;

/**
 * 对话树 / 过场协议入口（CmdId 860–868）。
 */
@Component
public class DialogueCutscenePacketHandlers {

    private final DialogueCutsceneNettyService dialogueCutsceneNettyService;

    public DialogueCutscenePacketHandlers(DialogueCutsceneNettyService dialogueCutsceneNettyService) {
        this.dialogueCutsceneNettyService = dialogueCutsceneNettyService;
    }

    @PacketCmd(CmdIds.DIALOGUE_CHOOSE_CS_REQ)
    public void onChoose(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        DialogueCutsceneProto.DialogueChooseCsReq req =
                DialogueCutsceneProto.DialogueChooseCsReq.parseFrom(packet.getPayload());
        DialogueCutsceneProto.DialogueChooseScRsp rsp =
                dialogueCutsceneNettyService.handleChoose(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.DIALOGUE_CHOOSE_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.DIALOGUE_SKIP_CS_REQ)
    public void onDialogueSkip(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        DialogueCutsceneProto.DialogueSkipCsReq req =
                DialogueCutsceneProto.DialogueSkipCsReq.parseFrom(packet.getPayload());
        DialogueCutsceneProto.DialogueSkipScRsp rsp =
                dialogueCutsceneNettyService.handleDialogueSkip(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.DIALOGUE_SKIP_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.CUTSCENE_SKIP_CS_REQ)
    public void onCutsceneSkip(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        DialogueCutsceneProto.CutsceneSkipCsReq req =
                DialogueCutsceneProto.CutsceneSkipCsReq.parseFrom(packet.getPayload());
        DialogueCutsceneProto.CutsceneSkipScRsp rsp =
                dialogueCutsceneNettyService.handleCutsceneSkip(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.CUTSCENE_SKIP_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.CUTSCENE_COMPLETE_CS_REQ)
    public void onCutsceneComplete(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        DialogueCutsceneProto.CutsceneCompleteCsReq req =
                DialogueCutsceneProto.CutsceneCompleteCsReq.parseFrom(packet.getPayload());
        DialogueCutsceneProto.CutsceneCompleteScRsp rsp =
                dialogueCutsceneNettyService.handleCutsceneComplete(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.CUTSCENE_COMPLETE_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.RESUME_FROM_BRANCH_CS_REQ)
    public void onResumeFromBranch(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        DialogueCutsceneProto.ResumeFromBranchCsReq req =
                DialogueCutsceneProto.ResumeFromBranchCsReq.parseFrom(
                        packet.getPayload() == null ? new byte[0] : packet.getPayload());
        DialogueCutsceneProto.ResumeFromBranchScRsp rsp =
                dialogueCutsceneNettyService.handleResumeFromBranch(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.RESUME_FROM_BRANCH_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.REPLAY_CUTSCENE_CS_REQ)
    public void onReplayCutscene(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        DialogueCutsceneProto.ReplayCutsceneCsReq req =
                DialogueCutsceneProto.ReplayCutsceneCsReq.parseFrom(
                        packet.getPayload() == null ? new byte[0] : packet.getPayload());
        DialogueCutsceneProto.ReplayCutsceneScRsp rsp =
                dialogueCutsceneNettyService.handleReplayCutscene(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.REPLAY_CUTSCENE_SC_RSP, rsp.toByteArray()));
    }

    @PacketCmd(CmdIds.GET_STORY_TREE_SNAPSHOT_CS_REQ)
    public void onStoryTreeSnapshot(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        DialogueCutsceneProto.GetStoryTreeSnapshotCsReq req =
                DialogueCutsceneProto.GetStoryTreeSnapshotCsReq.parseFrom(
                        packet.getPayload() == null ? new byte[0] : packet.getPayload());
        DialogueCutsceneProto.GetStoryTreeSnapshotScRsp rsp =
                dialogueCutsceneNettyService.handleStoryTreeSnapshot(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.GET_STORY_TREE_SNAPSHOT_SC_RSP, rsp.toByteArray()));
    }
}
