package cn.itcast.demo.mylunarcore.battle;

import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 战斗 QTE：破盾/击杀等条件触发 {@link CmdIds#BATTLE_QTE_SC_NOTIFY}；
 * 玩家成功响应后给予行动点或伤害加成。
 */
@Service
public class BattleQteService {

    public static final long DEFAULT_DEADLINE_MS = 2_500L;

    public enum BonusType { ACTION_POINT, DAMAGE_BONUS_BP }

    public enum Trigger { SHIELD_BREAK, KILL }

    public record QteEvent(String qteId, long battleId, int playerId, Trigger trigger,
                           BonusType bonusType, int bonusValue, long deadlineMs, boolean resolved) {}

    public record RespondResult(boolean ok, int retcode, BonusType bonusType, int bonusValue) {}

    private final Map<String, QteEvent> events = new ConcurrentHashMap<>();
    private final Map<Long, Integer> pendingActionPoints = new ConcurrentHashMap<>();
    private final Map<Long, Integer> pendingDamageBonusBp = new ConcurrentHashMap<>();
    private final GameSessionManager sessionManager;

    public BattleQteService(ObjectProvider<GameSessionManager> sessionProvider) {
        this.sessionManager = sessionProvider == null ? null : sessionProvider.getIfAvailable();
    }

    public QteEvent maybeTrigger(long battleId, int playerId, boolean shieldBroken, boolean kill) {
        Trigger trigger = null;
        if (kill) {
            trigger = Trigger.KILL;
        } else if (shieldBroken) {
            trigger = Trigger.SHIELD_BREAK;
        }
        if (trigger == null || playerId <= 0) {
            return null;
        }
        BonusType bonus = trigger == Trigger.KILL ? BonusType.DAMAGE_BONUS_BP : BonusType.ACTION_POINT;
        int value = trigger == Trigger.KILL ? 1500 : 1; // 15% 或 +1 AP
        String id = "qte-" + UUID.randomUUID().toString().substring(0, 8);
        long deadline = System.currentTimeMillis() + DEFAULT_DEADLINE_MS;
        QteEvent ev = new QteEvent(id, battleId, playerId, trigger, bonus, value, deadline, false);
        events.put(id, ev);
        push(ev);
        return ev;
    }

    public RespondResult respond(String qteId, int playerId, long nowMs) {
        QteEvent ev = events.get(qteId);
        if (ev == null || ev.playerId() != playerId) {
            return new RespondResult(false, 1, null, 0);
        }
        if (ev.resolved()) {
            return new RespondResult(false, 2, null, 0);
        }
        if (nowMs > ev.deadlineMs()) {
            events.put(qteId, new QteEvent(ev.qteId(), ev.battleId(), ev.playerId(), ev.trigger(),
                    ev.bonusType(), ev.bonusValue(), ev.deadlineMs(), true));
            return new RespondResult(false, 3, null, 0);
        }
        events.put(qteId, new QteEvent(ev.qteId(), ev.battleId(), ev.playerId(), ev.trigger(),
                ev.bonusType(), ev.bonusValue(), ev.deadlineMs(), true));
        if (ev.bonusType() == BonusType.ACTION_POINT) {
            pendingActionPoints.merge(ev.battleId(), ev.bonusValue(), Integer::sum);
        } else {
            pendingDamageBonusBp.merge(ev.battleId(), ev.bonusValue(), Integer::sum);
        }
        return new RespondResult(true, 0, ev.bonusType(), ev.bonusValue());
    }

    public int consumeActionPoints(long battleId) {
        Integer v = pendingActionPoints.remove(battleId);
        return v == null ? 0 : v;
    }

    public int consumeDamageBonusBp(long battleId) {
        Integer v = pendingDamageBonusBp.remove(battleId);
        return v == null ? 0 : v;
    }

    public QteEvent get(String qteId) {
        return events.get(qteId);
    }

    private void push(QteEvent ev) {
        if (sessionManager == null) {
            return;
        }
        GameSession s = sessionManager.getOrNull(ev.playerId());
        if (s == null) {
            return;
        }
        String json = "{\"qteId\":\"" + ev.qteId() + "\",\"battleId\":" + ev.battleId()
                + ",\"trigger\":\"" + ev.trigger() + "\",\"bonusType\":\"" + ev.bonusType()
                + "\",\"bonusValue\":" + ev.bonusValue() + ",\"deadlineMs\":" + ev.deadlineMs() + "}";
        s.send(new GamePacket(CmdIds.BATTLE_QTE_SC_NOTIFY, json.getBytes(StandardCharsets.UTF_8)));
    }
}
