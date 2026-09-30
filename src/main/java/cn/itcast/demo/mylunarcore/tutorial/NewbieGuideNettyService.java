package cn.itcast.demo.mylunarcore.tutorial;

import cn.itcast.demo.mylunarcore.player.PlayerContextResolver;
import cn.itcast.demo.mylunarcore.protocol.NewbieGuideSystemProto;
import io.netty.channel.Channel;
import org.springframework.stereotype.Service;

/**
 * 新手引导协议适配（CmdId 960–966）：检查点恢复 + 强制跳过。
 */
@Service
public class NewbieGuideNettyService {

    private final NewbieGuideService newbieGuideService;
    private final PlayerContextResolver playerContextResolver;

    public NewbieGuideNettyService(NewbieGuideService newbieGuideService,
                                   PlayerContextResolver playerContextResolver) {
        this.newbieGuideService = newbieGuideService;
        this.playerContextResolver = playerContextResolver;
    }

    public NewbieGuideSystemProto.GetNewbieGuideScRsp handleGet(Channel channel) {
        int playerId = playerContextResolver.resolvePlayerId(channel);
        if (playerId <= 0) {
            return NewbieGuideSystemProto.GetNewbieGuideScRsp.newBuilder().setRetcode(1).build();
        }
        NewbieGuideService.Progress p = newbieGuideService.progressDetail(playerId);
        NewbieGuideSystemProto.GetNewbieGuideScRsp.Builder b = NewbieGuideSystemProto.GetNewbieGuideScRsp.newBuilder()
                .setRetcode(0)
                .setCurrentStepId(nullToEmpty(p.currentStepId()))
                .setCheckpointStepId(nullToEmpty(p.checkpointStepId()))
                .setCompleted(p.completed())
                .setSkipped(p.skipped());
        for (NewbieGuideService.Step step : p.steps()) {
            b.addSteps(toProto(step, p.committedStepIds().contains(step.id())));
        }
        return b.build();
    }

    public NewbieGuideSystemProto.AdvanceNewbieGuideScRsp handleAdvance(
            NewbieGuideSystemProto.AdvanceNewbieGuideCsReq req, Channel channel) {
        int playerId = playerContextResolver.resolvePlayerId(channel);
        if (playerId <= 0) {
            return NewbieGuideSystemProto.AdvanceNewbieGuideScRsp.newBuilder().setRetcode(1).build();
        }
        boolean ok = newbieGuideService.advance(playerId, req.getStepId(), true);
        NewbieGuideService.Progress p = newbieGuideService.progressDetail(playerId);
        return NewbieGuideSystemProto.AdvanceNewbieGuideScRsp.newBuilder()
                .setRetcode(ok ? 0 : 2)
                .setCurrentStepId(nullToEmpty(p.currentStepId()))
                .setCheckpointStepId(nullToEmpty(p.checkpointStepId()))
                .setCompleted(p.completed())
                .build();
    }

    public NewbieGuideSystemProto.SkipNewbieGuideScRsp handleSkip(
            NewbieGuideSystemProto.SkipNewbieGuideCsReq req, Channel channel) {
        int playerId = playerContextResolver.resolvePlayerId(channel);
        if (playerId <= 0) {
            return NewbieGuideSystemProto.SkipNewbieGuideScRsp.newBuilder().setRetcode(1).build();
        }
        if (!req.getConfirm()) {
            return NewbieGuideSystemProto.SkipNewbieGuideScRsp.newBuilder().setRetcode(2).setSkipped(false).build();
        }
        boolean ok = newbieGuideService.skipAll(playerId);
        return NewbieGuideSystemProto.SkipNewbieGuideScRsp.newBuilder()
                .setRetcode(ok ? 0 : 2)
                .setSkipped(ok)
                .build();
    }

    public NewbieGuideSystemProto.NewbieGuideUpdateScNotify buildNotify(int playerId) {
        NewbieGuideService.Progress p = newbieGuideService.progressDetail(playerId);
        return NewbieGuideSystemProto.NewbieGuideUpdateScNotify.newBuilder()
                .setCurrentStepId(nullToEmpty(p.currentStepId()))
                .setCheckpointStepId(nullToEmpty(p.checkpointStepId()))
                .setCompleted(p.completed())
                .setSkipped(p.skipped())
                .build();
    }

    private static NewbieGuideSystemProto.NewbieGuideStep toProto(NewbieGuideService.Step step, boolean committed) {
        return NewbieGuideSystemProto.NewbieGuideStep.newBuilder()
                .setId(nullToEmpty(step.id()))
                .setTitle(nullToEmpty(step.title()))
                .setHint(nullToEmpty(step.hint()))
                .setCommitted(committed)
                .build();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
