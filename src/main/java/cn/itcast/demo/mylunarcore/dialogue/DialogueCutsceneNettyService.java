package cn.itcast.demo.mylunarcore.dialogue;

import cn.itcast.demo.mylunarcore.cutscene.CutsceneTriggerService;
import cn.itcast.demo.mylunarcore.player.PlayerContextResolver;
import cn.itcast.demo.mylunarcore.protocol.DialogueCutsceneProto;
import io.netty.channel.Channel;
import org.springframework.stereotype.Service;

/**
 * 对话树 / 过场协议适配（CmdId 860–868）。
 */
@Service
public class DialogueCutsceneNettyService {

    private final DialogueTriggerEngine dialogueTriggerEngine;
    private final CutsceneTriggerService cutsceneTriggerService;
    private final PlayerContextResolver playerContextResolver;

    public DialogueCutsceneNettyService(DialogueTriggerEngine dialogueTriggerEngine,
                                        CutsceneTriggerService cutsceneTriggerService,
                                        PlayerContextResolver playerContextResolver) {
        this.dialogueTriggerEngine = dialogueTriggerEngine;
        this.cutsceneTriggerService = cutsceneTriggerService;
        this.playerContextResolver = playerContextResolver;
    }

    public DialogueCutsceneProto.DialogueChooseScRsp handleChoose(DialogueCutsceneProto.DialogueChooseCsReq req,
                                                                  Channel channel) {
        int uid = playerContextResolver.resolvePlayerId(channel);
        if (uid <= 0) {
            return DialogueCutsceneProto.DialogueChooseScRsp.newBuilder().setRetcode(1).build();
        }
        DialogueTriggerEngine.DialogueStepResult r =
                dialogueTriggerEngine.choose(uid, req.getTreeId(), req.getChoiceId());
        DialogueCutsceneProto.DialogueChooseScRsp.Builder b =
                DialogueCutsceneProto.DialogueChooseScRsp.newBuilder().setRetcode(r.retcode());
        if (r.ok() && r.node() != null) {
            b.setNode(toNodeView(req.getTreeId(), r.node()))
                    .setCutsceneId(r.cutsceneId() == null ? "" : r.cutsceneId())
                    .setUnlockCgId(Math.max(0, r.unlockedCgId()));
        }
        return b.build();
    }

    public DialogueCutsceneProto.DialogueSkipScRsp handleDialogueSkip(DialogueCutsceneProto.DialogueSkipCsReq req,
                                                                      Channel channel) {
        int uid = playerContextResolver.resolvePlayerId(channel);
        if (uid <= 0) {
            return DialogueCutsceneProto.DialogueSkipScRsp.newBuilder().setRetcode(1).build();
        }
        return DialogueCutsceneProto.DialogueSkipScRsp.newBuilder()
                .setRetcode(dialogueTriggerEngine.skip(uid, req.getTreeId()).retcode())
                .build();
    }

    public DialogueCutsceneProto.CutsceneSkipScRsp handleCutsceneSkip(DialogueCutsceneProto.CutsceneSkipCsReq req,
                                                                      Channel channel) {
        int uid = playerContextResolver.resolvePlayerId(channel);
        if (uid <= 0) {
            return DialogueCutsceneProto.CutsceneSkipScRsp.newBuilder().setRetcode(1).build();
        }
        CutsceneTriggerService.TriggerResult r = cutsceneTriggerService.skip(uid, req.getCutsceneId());
        return DialogueCutsceneProto.CutsceneSkipScRsp.newBuilder()
                .setRetcode(r.retcode())
                .setSkipped(r.autoSkipped())
                .build();
    }

    public DialogueCutsceneProto.CutsceneCompleteScRsp handleCutsceneComplete(
            DialogueCutsceneProto.CutsceneCompleteCsReq req, Channel channel) {
        int uid = playerContextResolver.resolvePlayerId(channel);
        if (uid <= 0) {
            return DialogueCutsceneProto.CutsceneCompleteScRsp.newBuilder().setRetcode(1).build();
        }
        return DialogueCutsceneProto.CutsceneCompleteScRsp.newBuilder()
                .setRetcode(cutsceneTriggerService.complete(uid, req.getCutsceneId()).retcode())
                .build();
    }

    public DialogueCutsceneProto.ResumeFromBranchScRsp handleResumeFromBranch(
            DialogueCutsceneProto.ResumeFromBranchCsReq req, Channel channel) {
        int uid = playerContextResolver.resolvePlayerId(channel);
        if (uid <= 0) {
            return DialogueCutsceneProto.ResumeFromBranchScRsp.newBuilder().setRetcode(1).build();
        }
        DialogueTriggerEngine.DialogueStepResult r =
                dialogueTriggerEngine.resumeFromBranch(uid, req.getTreeId());
        DialogueCutsceneProto.ResumeFromBranchScRsp.Builder b =
                DialogueCutsceneProto.ResumeFromBranchScRsp.newBuilder().setRetcode(r.retcode());
        if (r.ok() && r.node() != null) {
            b.setNode(toNodeView(req.getTreeId(), r.node()))
                    .setSavepointNodeId(r.node().nodeId());
        }
        return b.build();
    }

    public DialogueCutsceneProto.ReplayCutsceneScRsp handleReplayCutscene(
            DialogueCutsceneProto.ReplayCutsceneCsReq req, Channel channel) {
        int uid = playerContextResolver.resolvePlayerId(channel);
        if (uid <= 0) {
            return DialogueCutsceneProto.ReplayCutsceneScRsp.newBuilder().setRetcode(1).build();
        }
        CutsceneTriggerService.ReplayResult r = cutsceneTriggerService.issueReplay(uid, req.getCutsceneId());
        return DialogueCutsceneProto.ReplayCutsceneScRsp.newBuilder()
                .setRetcode(r.retcode())
                .setCutsceneId(req.getCutsceneId() == null ? "" : req.getCutsceneId())
                .setReplayTicket(r.replayTicket() == null ? "" : r.replayTicket())
                .setTimelineAsset(r.timelineAsset() == null ? "" : r.timelineAsset())
                .build();
    }

    public DialogueCutsceneProto.GetStoryTreeSnapshotScRsp handleStoryTreeSnapshot(
            DialogueCutsceneProto.GetStoryTreeSnapshotCsReq req, Channel channel) {
        int uid = playerContextResolver.resolvePlayerId(channel);
        if (uid <= 0) {
            return DialogueCutsceneProto.GetStoryTreeSnapshotScRsp.newBuilder().setRetcode(1).build();
        }
        DialogueTriggerEngine.StoryTreeSnapshot snap = dialogueTriggerEngine.storyTreeSnapshot(uid, req.getTreeId());
        DialogueCutsceneProto.GetStoryTreeSnapshotScRsp.Builder b =
                DialogueCutsceneProto.GetStoryTreeSnapshotScRsp.newBuilder()
                        .setRetcode(snap.retcode())
                        .setTreeId(snap.treeId() == null ? "" : snap.treeId())
                        .setTitle(snap.title() == null ? "" : snap.title());
        if (snap.nodes() != null) {
            for (DialogueTriggerEngine.StoryNodeSnap n : snap.nodes()) {
                b.addNodes(DialogueCutsceneProto.StoryTreeNodeSnapshot.newBuilder()
                        .setNodeId(n.nodeId())
                        .setUnlocked(n.unlocked())
                        .setCurrent(n.current())
                        .setSavepoint(n.savepoint())
                        .addAllOutgoingChoiceIds(n.outgoingChoiceIds())
                        .build());
            }
        }
        return b.build();
    }

    private static DialogueCutsceneProto.DialogueNodeView toNodeView(String treeId, DialogueNode node) {
        DialogueCutsceneProto.DialogueNodeView.Builder b = DialogueCutsceneProto.DialogueNodeView.newBuilder()
                .setTreeId(treeId == null ? "" : treeId)
                .setNodeId(node.nodeId())
                .setSpeaker(node.speaker())
                .setText(node.text())
                .setCutsceneId(node.cutsceneId())
                .setUnlockCgId(Math.max(0, node.unlockCgId()));
        for (DialogueNode.Choice c : node.safeChoices()) {
            b.addChoices(DialogueCutsceneProto.DialogueChoice.newBuilder()
                    .setChoiceId(c.choiceId())
                    .setText(c.text())
                    .addAllImpactTags(c.impactTags() == null ? java.util.List.of() : c.impactTags())
                    .build());
        }
        return b.build();
    }
}
