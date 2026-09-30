package cn.itcast.demo.mylunarcore.battle;

import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Auto 弱网补偿：客户端超时确认重试 + 动态延长行动窗 + Tick 进度推送。
 */
@Service
public class BattleAutoWeakNetService {

    public static final long CLIENT_CONFIRM_TIMEOUT_MS = 300L;
    public static final long WEAK_NET_AUTO_WAIT_MS = 1_800L;
    public static final long NORMAL_AUTO_WAIT_MS = BattleAutoService.BASE_AUTO_WAIT_MS;

    public record BandwidthHint(boolean weakNet, long suggestedWaitMs, int rttMs) {}

    private final BattleManager battleManager;
    private final BattleAutoService battleAutoService;
    private final GameSessionManager sessionManager;
    /** battleId → 最近一次行动推送时间 */
    private final Map<Long, Long> lastActionPushAt = new ConcurrentHashMap<>();
    /** playerId → 最近探测 RTT */
    private final Map<Integer, Integer> rttByPlayer = new ConcurrentHashMap<>();

    public BattleAutoWeakNetService(BattleManager battleManager,
                                    BattleAutoService battleAutoService,
                                    ObjectProvider<GameSessionManager> sessionProvider) {
        this.battleManager = battleManager;
        this.battleAutoService = battleAutoService;
        this.sessionManager = sessionProvider == null ? null : sessionProvider.getIfAvailable();
    }

    public void reportRtt(int playerId, int rttMs) {
        if (playerId > 0 && rttMs > 0) {
            rttByPlayer.put(playerId, Math.min(rttMs, 5_000));
        }
    }

    public BandwidthHint bandwidthHint(int playerId) {
        int rtt = rttByPlayer.getOrDefault(playerId, 0);
        boolean weak = rtt >= 180;
        long wait = weak ? WEAK_NET_AUTO_WAIT_MS : NORMAL_AUTO_WAIT_MS;
        return new BandwidthHint(weak, wait, rtt);
    }

    public long resolveAutoWaitMs(BattleContext ctx) {
        if (ctx == null) {
            return NORMAL_AUTO_WAIT_MS;
        }
        BandwidthHint hint = bandwidthHint(ctx.getPlayerId());
        return ctx.compressedWaitMs(hint.suggestedWaitMs());
    }

    /** 标记刚下发过行动，供客户端超时检测对齐。 */
    public void markActionPushed(long battleId) {
        lastActionPushAt.put(battleId, System.currentTimeMillis());
    }

    /**
     * 客户端超时未收到行动后主动确认：若服务端已过期则立即补执行一回合。
     */
    public boolean confirmOrRetry(long battleId, int playerId) {
        BattleContext ctx = battleManager.get(battleId);
        if (ctx == null || ctx.isEnded() || !ctx.isParticipant(playerId) || !ctx.isAutoBattle()) {
            return false;
        }
        long now = System.currentTimeMillis();
        Long last = lastActionPushAt.get(battleId);
        if (last != null && now - last < CLIENT_CONFIRM_TIMEOUT_MS) {
            pushTickProgress(ctx, playerId, "syncing");
            return true;
        }
        if (now < ctx.getNextActionAtMs()) {
            pushTickProgress(ctx, playerId, "waiting");
            return true;
        }
        BattleAutoService.AutoAction action = battleAutoService.executeOneTurn(ctx);
        if (action != null) {
            markActionPushed(battleId);
            pushTickProgress(ctx, playerId, "retried");
            return true;
        }
        pushTickProgress(ctx, playerId, "empty");
        return false;
    }

    public void pushTickProgress(BattleContext ctx, int playerId, String phase) {
        if (sessionManager == null || ctx == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long next = ctx.getNextActionAtMs();
        long wait = Math.max(0, next - now);
        long total = Math.max(1, resolveAutoWaitMs(ctx));
        int percent = (int) Math.min(100, Math.max(0, 100 - (wait * 100 / total)));
        String json = "{\"battleId\":" + ctx.getBattleId() + ",\"percent\":" + percent
                + ",\"remainMs\":" + wait + ",\"phase\":\"" + phase
                + "\",\"hint\":\"网络同步中\"}";
        GameSession s = sessionManager.getOrNull(playerId);
        if (s != null) {
            s.send(new GamePacket(CmdIds.BATTLE_TICK_PROGRESS_SC_NOTIFY, json.getBytes(StandardCharsets.UTF_8)));
        }
    }
}
