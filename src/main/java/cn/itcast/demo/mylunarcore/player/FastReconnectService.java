package cn.itcast.demo.mylunarcore.player;

import cn.itcast.demo.mylunarcore.battle.BattleContext;
import cn.itcast.demo.mylunarcore.battle.BattleManager;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.scene.SceneManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 快速重连：客户端保留 session_id，经 {@link CmdIds#RECONNECT_CS_REQ} 恢复场景/战斗，
 * 无需重新加载整包资源。缓存近 30 秒 Entity 快照；战斗断线进入 AWAITING_RECONNECT 暂停。
 */
@Service
public class FastReconnectService {

    public static final long SNAPSHOT_TTL_MS = 30_000L;
    public static final long BATTLE_PAUSE_MAX_MS = 30_000L;

    public enum BattlePauseState { RUNNING, AWAITING_RECONNECT }

    public record SessionSnapshot(String sessionId, int playerId, long sceneId, int planeId, int floorId,
                                  float x, float y, float z, Long battleId, String entityJson,
                                  long savedAtMs) {}

    public record ReconnectResult(boolean ok, int retcode, SessionSnapshot snapshot,
                                  boolean battlePaused, long pauseRemainMs) {}

    private final Map<String, SessionSnapshot> bySession = new ConcurrentHashMap<>();
    private final Map<Integer, String> playerSession = new ConcurrentHashMap<>();
    private final Map<Long, BattlePauseState> battlePause = new ConcurrentHashMap<>();
    private final Map<Long, Long> battlePauseUntil = new ConcurrentHashMap<>();
    private final BattleManager battleManager;

    public FastReconnectService(ObjectProvider<BattleManager> battleProvider,
                                ObjectProvider<SceneManager> sceneProvider) {
        this.battleManager = battleProvider == null ? null : battleProvider.getIfAvailable();
        // sceneProvider 预留：后续可 hydrate SceneManager 快照
        if (sceneProvider != null) {
            sceneProvider.getIfAvailable();
        }
    }

    public String cacheSnapshot(int playerId, long sceneId, int planeId, int floorId,
                                float x, float y, float z, Long battleId, String entityJson) {
        String sid = playerSession.computeIfAbsent(playerId, k -> "sess-" + UUID.randomUUID());
        SessionSnapshot snap = new SessionSnapshot(sid, playerId, sceneId, planeId, floorId,
                x, y, z, battleId, entityJson == null ? "{}" : entityJson, System.currentTimeMillis());
        bySession.put(sid, snap);
        return sid;
    }

    /** 玩家断线：战斗暂停倒计时（最长 30s）。 */
    public void onDisconnect(int playerId) {
        String sid = playerSession.get(playerId);
        if (sid == null) {
            return;
        }
        SessionSnapshot snap = bySession.get(sid);
        if (snap != null && snap.battleId() != null) {
            long until = System.currentTimeMillis() + BATTLE_PAUSE_MAX_MS;
            battlePause.put(snap.battleId(), BattlePauseState.AWAITING_RECONNECT);
            battlePauseUntil.put(snap.battleId(), until);
        }
    }

    public boolean isBattlePaused(long battleId) {
        BattlePauseState st = battlePause.get(battleId);
        if (st != BattlePauseState.AWAITING_RECONNECT) {
            return false;
        }
        Long until = battlePauseUntil.get(battleId);
        if (until == null || System.currentTimeMillis() > until) {
            battlePause.put(battleId, BattlePauseState.RUNNING);
            return false;
        }
        return true;
    }

    public ReconnectResult reconnect(String sessionId, int playerId) {
        SessionSnapshot snap = bySession.get(sessionId);
        if (snap == null || snap.playerId() != playerId) {
            return new ReconnectResult(false, 1, null, false, 0);
        }
        if (System.currentTimeMillis() - snap.savedAtMs() > SNAPSHOT_TTL_MS) {
            return new ReconnectResult(false, 2, null, false, 0);
        }
        boolean paused = false;
        long remain = 0;
        if (snap.battleId() != null) {
            Long until = battlePauseUntil.get(snap.battleId());
            if (until != null && System.currentTimeMillis() <= until) {
                paused = true;
                remain = until - System.currentTimeMillis();
            }
            battlePause.put(snap.battleId(), BattlePauseState.RUNNING);
            battlePauseUntil.remove(snap.battleId());
            if (battleManager != null) {
                BattleContext ctx = battleManager.get(snap.battleId());
                if (ctx != null) {
                    battleManager.checkpoint(ctx);
                }
            }
        }
        // 刷新快照时间，延长 TTL
        SessionSnapshot refreshed = new SessionSnapshot(snap.sessionId(), snap.playerId(), snap.sceneId(),
                snap.planeId(), snap.floorId(), snap.x(), snap.y(), snap.z(), snap.battleId(),
                snap.entityJson(), System.currentTimeMillis());
        bySession.put(sessionId, refreshed);
        return new ReconnectResult(true, 0, refreshed, paused, remain);
    }

    /** App 回前台预连接：轻量探测，返回是否仍持有有效快照。 */
    public boolean probe(String sessionId) {
        SessionSnapshot snap = bySession.get(sessionId);
        return snap != null && System.currentTimeMillis() - snap.savedAtMs() <= SNAPSHOT_TTL_MS;
    }

    public GamePacket buildRsp(ReconnectResult r) {
        if (!r.ok() || r.snapshot() == null) {
            String json = "{\"retcode\":" + r.retcode() + "}";
            return new GamePacket(CmdIds.RECONNECT_SC_RSP, json.getBytes(StandardCharsets.UTF_8));
        }
        SessionSnapshot s = r.snapshot();
        String json = "{\"retcode\":0,\"sessionId\":\"" + s.sessionId()
                + "\",\"sceneId\":" + s.sceneId()
                + ",\"planeId\":" + s.planeId() + ",\"floorId\":" + s.floorId()
                + ",\"x\":" + s.x() + ",\"y\":" + s.y() + ",\"z\":" + s.z()
                + ",\"battleId\":" + (s.battleId() == null ? "null" : s.battleId())
                + ",\"battlePaused\":" + r.battlePaused()
                + ",\"pauseRemainMs\":" + r.pauseRemainMs()
                + ",\"entityJson\":" + s.entityJson() + "}";
        return new GamePacket(CmdIds.RECONNECT_SC_RSP, json.getBytes(StandardCharsets.UTF_8));
    }

    public SessionSnapshot getByPlayer(int playerId) {
        String sid = playerSession.get(playerId);
        return sid == null ? null : bySession.get(sid);
    }
}
