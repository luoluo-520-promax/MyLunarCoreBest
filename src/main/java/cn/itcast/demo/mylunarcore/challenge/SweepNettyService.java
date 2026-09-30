package cn.itcast.demo.mylunarcore.challenge;

import cn.itcast.demo.mylunarcore.player.PlayerContextResolver;
import cn.itcast.demo.mylunarcore.protocol.DailyLoopSystemProto;
import io.netty.channel.Channel;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 扫荡协议适配（CmdId 1010–1013）：历史通关解锁 + 多倍体力。
 */
@Service
public class SweepNettyService {

    private final SweepService sweepService;
    private final PlayerContextResolver playerContextResolver;

    public SweepNettyService(SweepService sweepService, PlayerContextResolver playerContextResolver) {
        this.sweepService = sweepService;
        this.playerContextResolver = playerContextResolver;
    }

    public DailyLoopSystemProto.GetSweepInfoScRsp handleGetInfo(DailyLoopSystemProto.GetSweepInfoCsReq req,
                                                                Channel channel) {
        int playerId = playerContextResolver.resolvePlayerId(channel);
        if (playerId <= 0) {
            return DailyLoopSystemProto.GetSweepInfoScRsp.newBuilder().setRetcode(1).build();
        }
        int stageId = req.getBattleStageId();
        SweepService.SweepInfo info = sweepService.info(playerId, stageId);
        return DailyLoopSystemProto.GetSweepInfoScRsp.newBuilder()
                .setRetcode(0)
                .setBattleStageId(stageId)
                .setUnlocked(info.unlocked())
                .setBestTurnCount(Math.max(0, info.bestTurnCount()))
                .setMaxMultiplier(info.maxMultiplier())
                .setUnitStaminaCost(info.unitStaminaCost())
                .build();
    }

    public DailyLoopSystemProto.SweepStageScRsp handleSweep(DailyLoopSystemProto.SweepStageCsReq req,
                                                            Channel channel) {
        int playerId = playerContextResolver.resolvePlayerId(channel);
        if (playerId <= 0) {
            return DailyLoopSystemProto.SweepStageScRsp.newBuilder().setRetcode(1).build();
        }
        SweepService.SweepResult r = sweepService.sweep(playerId, req.getBattleStageId(), req.getMultiplier());
        DailyLoopSystemProto.SweepStageScRsp.Builder b = DailyLoopSystemProto.SweepStageScRsp.newBuilder()
                .setRetcode(r.retcode())
                .setBattleStageId(r.stageId())
                .setMultiplier(r.multiplier())
                .setStaminaCost(r.staminaCost())
                .setStaminaRemaining(r.staminaRemaining())
                .setBestTurnCount(r.bestTurnCount())
                .setPlayerExp(r.playerExp());
        if (r.rewards() != null) {
            for (SweepService.SweepReward item : r.rewards()) {
                b.addRewards(DailyLoopSystemProto.SweepRewardItem.newBuilder()
                        .setItemId(item.itemId())
                        .setCount(item.count())
                        .build());
            }
        }
        return b.build();
    }

    public DailyLoopSystemProto.BatchSweepScRsp handleBatch(DailyLoopSystemProto.BatchSweepCsReq req, Channel channel) {
        int playerId = playerContextResolver.resolvePlayerId(channel);
        if (playerId <= 0) {
            return DailyLoopSystemProto.BatchSweepScRsp.newBuilder().setRetcode(1).build();
        }
        List<SweepService.BatchSweepStep> steps = new ArrayList<>();
        for (DailyLoopSystemProto.BatchSweepStep s : req.getStepsList()) {
            steps.add(new SweepService.BatchSweepStep(s.getBattleStageId(), s.getTimes()));
        }
        SweepService.BatchSweepResult r = sweepService.batchSweep(playerId, steps, req.getConfirmedStaminaCost());
        DailyLoopSystemProto.BatchSweepScRsp.Builder b = DailyLoopSystemProto.BatchSweepScRsp.newBuilder()
                .setRetcode(r.retcode())
                .setStaminaCost(r.staminaCost())
                .setStaminaRemaining(r.staminaRemaining())
                .setCompletedTimes(r.completedTimes())
                .setPlayerExp(r.playerExp());
        if (r.rewards() != null) {
            for (SweepService.SweepReward item : r.rewards()) {
                b.addRewards(DailyLoopSystemProto.SweepRewardItem.newBuilder()
                        .setItemId(item.itemId())
                        .setCount(item.count())
                        .build());
            }
        }
        if (channel != null && channel.isActive()) {
            DailyLoopSystemProto.BatchSweepProgressNotify progress =
                    DailyLoopSystemProto.BatchSweepProgressNotify.newBuilder()
                            .setCompletedTimes(r.completedTimes())
                            .setTotalTimes(r.completedTimes())
                            .setFinished(true)
                            .build();
            channel.writeAndFlush(new cn.itcast.demo.mylunarcore.net.GamePacket(
                    cn.itcast.demo.mylunarcore.net.CmdIds.BATCH_SWEEP_PROGRESS_SC_NOTIFY, progress.toByteArray()));

            DailyLoopSystemProto.BatchSweepRewardSummary.Builder summary =
                    DailyLoopSystemProto.BatchSweepRewardSummary.newBuilder()
                            .setDeltaPlayerExp(r.playerExp())
                            .setDeltaStamina(-Math.max(0, r.staminaCost()))
                            .setSkipLootBoxAnim(true);
            StringBuilder text = new StringBuilder();
            if (r.rewards() != null) {
                for (SweepService.SweepReward item : r.rewards()) {
                    summary.addDeltaRewards(DailyLoopSystemProto.SweepRewardItem.newBuilder()
                            .setItemId(item.itemId())
                            .setCount(item.count())
                            .build());
                    if (text.length() > 0) {
                        text.append('，');
                    }
                    text.append("道具").append(item.itemId()).append(" +").append(item.count());
                }
            }
            if (r.playerExp() > 0) {
                if (text.length() > 0) {
                    text.append('，');
                }
                text.append("经验 +").append(r.playerExp());
            }
            summary.setSummaryText(text.length() == 0 ? "扫荡完成" : text.toString());
            DailyLoopSystemProto.BatchSweepRewardSummaryScNotify summaryNotify =
                    DailyLoopSystemProto.BatchSweepRewardSummaryScNotify.newBuilder()
                            .setSummary(summary.build())
                            .setCompletedTimes(r.completedTimes())
                            .build();
            channel.writeAndFlush(new cn.itcast.demo.mylunarcore.net.GamePacket(
                    cn.itcast.demo.mylunarcore.net.CmdIds.BATCH_SWEEP_REWARD_SUMMARY_SC_NOTIFY,
                    summaryNotify.toByteArray()));
        }
        return b.build();
    }
}
