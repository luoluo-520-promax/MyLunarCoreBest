package cn.itcast.demo.mylunarcore.player;

import cn.itcast.demo.mylunarcore.protocol.DailyLoopSystemProto;
import io.netty.channel.Channel;
import org.springframework.stereotype.Service;

/**
 * 体力协议适配：Channel → {@link StaminaService} → Protobuf（CmdId 996–1000）。
 */
@Service
public class StaminaNettyService {

    private final StaminaService staminaService;
    private final PlayerContextResolver playerContextResolver;

    public StaminaNettyService(StaminaService staminaService,
                               PlayerContextResolver playerContextResolver) {
        this.staminaService = staminaService;
        this.playerContextResolver = playerContextResolver;
    }

    public DailyLoopSystemProto.GetStaminaScRsp handleGet(Channel channel) {
        int playerId = playerContextResolver.resolvePlayerId(channel);
        if (playerId <= 0) {
            return DailyLoopSystemProto.GetStaminaScRsp.newBuilder().setRetcode(1).build();
        }
        StaminaService.StaminaSnapshot snap = staminaService.snapshot(playerId);
        return DailyLoopSystemProto.GetStaminaScRsp.newBuilder()
                .setRetcode(0)
                .setCurrent(snap.current())
                .setMax(snap.max())
                .setDailyBuyCount(snap.dailyBuyCount())
                .setDailyBuyLimit(snap.dailyBuyLimit())
                .setNextRegenAtMs(snap.nextRegenAtMs())
                .setReserve(snap.reserve())
                .setReserveCap(snap.reserveCap())
                .build();
    }

    public DailyLoopSystemProto.BuyStaminaScRsp handleBuy(Channel channel) {
        int playerId = playerContextResolver.resolvePlayerId(channel);
        if (playerId <= 0) {
            return DailyLoopSystemProto.BuyStaminaScRsp.newBuilder().setRetcode(1).build();
        }
        StaminaService.ConsumeResult result = staminaService.buyStamina(playerId);
        StaminaService.StaminaSnapshot snap = staminaService.snapshot(playerId);
        return DailyLoopSystemProto.BuyStaminaScRsp.newBuilder()
                .setRetcode(result.ok() ? 0 : result.retcode())
                .setCurrent(result.ok() ? result.remaining() : snap.current())
                .setDailyBuyCount(snap.dailyBuyCount())
                .build();
    }

    public DailyLoopSystemProto.ClaimReserveStaminaScRsp handleClaimReserve(int amount, Channel channel) {
        int playerId = playerContextResolver.resolvePlayerId(channel);
        if (playerId <= 0) {
            return DailyLoopSystemProto.ClaimReserveStaminaScRsp.newBuilder().setRetcode(1).build();
        }
        StaminaService.ConsumeResult result = staminaService.claimReserve(playerId, amount);
        StaminaService.StaminaSnapshot snap = staminaService.snapshot(playerId);
        return DailyLoopSystemProto.ClaimReserveStaminaScRsp.newBuilder()
                .setRetcode(result.ok() ? 0 : result.retcode())
                .setCurrent(snap.current())
                .setReserve(snap.reserve())
                .build();
    }

    public DailyLoopSystemProto.StaminaUpdateScNotify buildNotify(int playerId, String reason) {
        StaminaService.StaminaSnapshot snap = staminaService.snapshot(playerId);
        return DailyLoopSystemProto.StaminaUpdateScNotify.newBuilder()
                .setCurrent(snap.current())
                .setMax(snap.max())
                .setDailyBuyCount(snap.dailyBuyCount())
                .setDailyBuyLimit(snap.dailyBuyLimit())
                .setNextRegenAtMs(snap.nextRegenAtMs())
                .setReason(reason == null ? "" : reason)
                .setReserve(snap.reserve())
                .setReserveCap(snap.reserveCap())
                .build();
    }
}
