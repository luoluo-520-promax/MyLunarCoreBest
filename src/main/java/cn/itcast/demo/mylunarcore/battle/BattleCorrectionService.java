package cn.itcast.demo.mylunarcore.battle;

import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 战斗客户端预测修正：以 client_estimated_time 做确定性结算，偏差超阈值则下发权威修正。
 */
@Service
public class BattleCorrectionService {

    /** 允许的客户端估计时间偏差（毫秒）。 */
    public static final long MAX_SKEW_MS = 120L;
    /** HP 预测偏差超过该绝对值则强制修正。 */
    public static final int HP_DELTA_THRESHOLD = 1;

    public record Correction(long battleId, int entityId, int authoritativeHp, boolean dead,
                             long serverTimeMs, String reason) {}

    private final GameSessionManager sessionManager;

    public BattleCorrectionService(ObjectProvider<GameSessionManager> sessionProvider) {
        this.sessionManager = sessionProvider == null ? null : sessionProvider.getIfAvailable();
    }

    /**
     * 对齐客户端估计时间：超阈值则钳制到服务端 now，并标记需修正。
     *
     * @return 用于确定性计算的权威时间戳
     */
    public long alignClientEstimatedTime(long clientEstimatedTimeMs, long serverNowMs) {
        if (clientEstimatedTimeMs <= 0) {
            return serverNowMs;
        }
        long skew = Math.abs(clientEstimatedTimeMs - serverNowMs);
        if (skew > MAX_SKEW_MS) {
            return serverNowMs;
        }
        return clientEstimatedTimeMs;
    }

    public boolean needsTimeCorrection(long clientEstimatedTimeMs, long serverNowMs) {
        if (clientEstimatedTimeMs <= 0) {
            return false;
        }
        return Math.abs(clientEstimatedTimeMs - serverNowMs) > MAX_SKEW_MS;
    }

    /**
     * 对比客户端预测 HP 与权威状态，生成修正列表并推送。
     */
    public List<Correction> correctPredictedHp(BattleContext ctx, int playerId,
                                               Map<Integer, Integer> clientPredictedHp,
                                               String reason) {
        List<Correction> out = new ArrayList<>();
        if (ctx == null || clientPredictedHp == null || clientPredictedHp.isEmpty()) {
            return out;
        }
        long now = System.currentTimeMillis();
        for (Map.Entry<Integer, Integer> e : clientPredictedHp.entrySet()) {
            EntityState entity = ctx.getEntity(e.getKey());
            if (entity == null) {
                continue;
            }
            int predicted = e.getValue() == null ? entity.getHp() : e.getValue();
            if (Math.abs(predicted - entity.getHp()) < HP_DELTA_THRESHOLD
                    && (entity.isDead() == (predicted <= 0))) {
                continue;
            }
            Correction c = new Correction(ctx.getBattleId(), e.getKey(), entity.getHp(),
                    entity.isDead(), now, reason == null ? "hp_mismatch" : reason);
            out.add(c);
            push(playerId, c);
        }
        return out;
    }

    public void push(int playerId, Correction c) {
        if (sessionManager == null || playerId <= 0 || c == null) {
            return;
        }
        String json = "{\"battleId\":" + c.battleId() + ",\"entityId\":" + c.entityId()
                + ",\"hp\":" + c.authoritativeHp() + ",\"dead\":" + c.dead()
                + ",\"serverTimeMs\":" + c.serverTimeMs()
                + ",\"reason\":\"" + escape(c.reason()) + "\"}";
        GameSession s = sessionManager.getOrNull(playerId);
        if (s != null) {
            s.send(new GamePacket(CmdIds.BATTLE_CORRECTION_SC_NOTIFY, json.getBytes(StandardCharsets.UTF_8)));
        }
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
