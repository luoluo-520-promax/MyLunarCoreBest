package cn.itcast.demo.mylunarcore.battlepass;

import cn.itcast.demo.mylunarcore.player.PlayerContextResolver;
import cn.itcast.demo.mylunarcore.protocol.BattlePassSystemProto;
import io.netty.channel.Channel;
import org.springframework.stereotype.Service;

/**
 * 战令协议适配：Channel → {@link BattlePassService} → Protobuf（CmdId 990–995）。
 */
@Service
public class BattlePassNettyService {

    private final BattlePassService battlePassService;
    private final PlayerContextResolver playerContextResolver;

    public BattlePassNettyService(BattlePassService battlePassService,
                                  PlayerContextResolver playerContextResolver) {
        this.battlePassService = battlePassService;
        this.playerContextResolver = playerContextResolver;
    }

    public BattlePassSystemProto.GetBattlePassScRsp handleGet(
            BattlePassSystemProto.GetBattlePassCsReq req, Channel channel) {
        int playerId = playerContextResolver.resolvePlayerId(channel);
        if (playerId <= 0) {
            return BattlePassSystemProto.GetBattlePassScRsp.newBuilder().setRetcode(1).build();
        }
        BattlePassService.ProgressView view = battlePassService.getProgress(playerId);
        if (req.getSeasonId() != 0 && req.getSeasonId() != view.seasonId()) {
            return BattlePassSystemProto.GetBattlePassScRsp.newBuilder().setRetcode(2).build();
        }
        BattlePassSystemProto.GetBattlePassScRsp.Builder rsp = BattlePassSystemProto.GetBattlePassScRsp.newBuilder()
                .setRetcode(0)
                .setSeasonId(view.seasonId())
                .setXp(view.xp())
                .setLevel(view.level())
                .setMaxLevel(view.maxLevel())
                .setPremium(view.premium());
        view.claimedFree().forEach(rsp::addClaimedFree);
        view.claimedPremium().forEach(rsp::addClaimedPremium);
        return rsp.build();
    }

    public BattlePassSystemProto.ClaimBattlePassScRsp handleClaim(
            BattlePassSystemProto.ClaimBattlePassCsReq req, Channel channel) {
        int playerId = playerContextResolver.resolvePlayerId(channel);
        if (playerId <= 0) {
            return BattlePassSystemProto.ClaimBattlePassScRsp.newBuilder().setRetcode(1).build();
        }
        BattlePassService.ClaimResult result = battlePassService.claimLevel(
                playerId, req.getLevel(), req.getPremiumTrack());
        return BattlePassSystemProto.ClaimBattlePassScRsp.newBuilder()
                .setRetcode(result.ok() ? 0 : result.retcode())
                .setLevel(req.getLevel())
                .setPremiumTrack(req.getPremiumTrack())
                .build();
    }

    public BattlePassSystemProto.BuyBattlePassPremiumScRsp handleBuyPremium(
            BattlePassSystemProto.BuyBattlePassPremiumCsReq req, Channel channel) {
        int playerId = playerContextResolver.resolvePlayerId(channel);
        if (playerId <= 0) {
            return BattlePassSystemProto.BuyBattlePassPremiumScRsp.newBuilder().setRetcode(1).build();
        }
        BattlePassService.ClaimResult result = battlePassService.unlockPremium(playerId);
        return BattlePassSystemProto.BuyBattlePassPremiumScRsp.newBuilder()
                .setRetcode(result.ok() ? 0 : result.retcode())
                .setPremium(result.ok())
                .build();
    }

    /** 构建战令进度推送（CmdId 1009）。 */
    public BattlePassSystemProto.BattlePassUpdateScNotify buildNotify(int playerId, String reason) {
        BattlePassService.ProgressView view = battlePassService.getProgress(playerId);
        return BattlePassSystemProto.BattlePassUpdateScNotify.newBuilder()
                .setSeasonId(view.seasonId())
                .setXp(view.xp())
                .setLevel(view.level())
                .setMaxLevel(view.maxLevel())
                .setPremium(view.premium())
                .setReason(reason == null ? "" : reason)
                .build();
    }
}
