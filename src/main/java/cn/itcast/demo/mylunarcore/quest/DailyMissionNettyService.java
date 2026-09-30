package cn.itcast.demo.mylunarcore.quest;

import cn.itcast.demo.mylunarcore.player.PlayerContextResolver;
import cn.itcast.demo.mylunarcore.protocol.DailyLoopSystemProto;
import io.netty.channel.Channel;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * 每日任务协议适配（CmdId 1001–1005）。
 */
@Service
public class DailyMissionNettyService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final DailyMissionService dailyMissionService;
    private final PlayerContextResolver playerContextResolver;

    public DailyMissionNettyService(DailyMissionService dailyMissionService,
                                    PlayerContextResolver playerContextResolver) {
        this.dailyMissionService = dailyMissionService;
        this.playerContextResolver = playerContextResolver;
    }

    public DailyLoopSystemProto.GetDailyMissionScRsp handleGet(Channel channel) {
        int playerId = playerContextResolver.resolvePlayerId(channel);
        if (playerId <= 0) {
            return DailyLoopSystemProto.GetDailyMissionScRsp.newBuilder().setRetcode(1).build();
        }
        List<DailyMissionService.MissionView> views = dailyMissionService.listToday(playerId);
        DailyLoopSystemProto.GetDailyMissionScRsp.Builder rsp =
                DailyLoopSystemProto.GetDailyMissionScRsp.newBuilder()
                        .setRetcode(0)
                        .setMissionDay(LocalDate.now(ZONE).toString());
        for (DailyMissionService.MissionView v : views) {
            rsp.addMissions(toEntry(v));
        }
        return rsp.build();
    }

    public DailyLoopSystemProto.ClaimDailyMissionScRsp handleClaim(
            DailyLoopSystemProto.ClaimDailyMissionCsReq req, Channel channel) {
        int playerId = playerContextResolver.resolvePlayerId(channel);
        if (playerId <= 0) {
            return DailyLoopSystemProto.ClaimDailyMissionScRsp.newBuilder().setRetcode(1).build();
        }
        DailyMissionService.ClaimResult result = dailyMissionService.claim(playerId, req.getMissionId());
        return DailyLoopSystemProto.ClaimDailyMissionScRsp.newBuilder()
                .setRetcode(result.ok() ? 0 : result.retcode())
                .setMissionId(req.getMissionId())
                .build();
    }

    public DailyLoopSystemProto.DailyMissionUpdateScNotify buildNotify(
            DailyMissionService.MissionView view) {
        return DailyLoopSystemProto.DailyMissionUpdateScNotify.newBuilder()
                .setMissionDay(LocalDate.now(ZONE).toString())
                .setMission(toEntry(view))
                .build();
    }

    private static DailyLoopSystemProto.DailyMissionEntry toEntry(DailyMissionService.MissionView v) {
        return DailyLoopSystemProto.DailyMissionEntry.newBuilder()
                .setMissionId(v.missionId())
                .setTitle(v.title() == null ? "" : v.title())
                .setDescription(v.description() == null ? "" : v.description())
                .setProgress(v.progress())
                .setRequired(v.required())
                .setCompleted(v.completed())
                .setClaimed(v.claimed())
                .build();
    }
}
