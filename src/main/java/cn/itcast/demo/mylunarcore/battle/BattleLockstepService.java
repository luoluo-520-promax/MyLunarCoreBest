package cn.itcast.demo.mylunarcore.battle;

import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 轻量帧同步（Lockstep）：关键技能/受击判定按全局帧号对齐；普通移动仍走状态同步。
 * 服务端作为权威时钟，经 BattleTickScNotify（1113）广播 frameNo。
 */
@Service
public class BattleLockstepService {

    public enum SyncKind {
        MOVE_STATE,
        SKILL_LOCKSTEP,
        HIT_LOCKSTEP
    }

    public record TickFrame(long battleId, long frameNo, long serverTimeMs, SyncKind kind, String payloadHint) {}

    private final GameSessionManager sessionManager;
    private final Map<Long, AtomicLong> frames = new ConcurrentHashMap<>();

    public BattleLockstepService(ObjectProvider<GameSessionManager> sessionProvider) {
        this.sessionManager = sessionProvider == null ? null : sessionProvider.getIfAvailable();
    }

    public long nextFrame(long battleId) {
        return frames.computeIfAbsent(battleId, id -> new AtomicLong(0)).incrementAndGet();
    }

    public long currentFrame(long battleId) {
        AtomicLong f = frames.get(battleId);
        return f == null ? 0L : f.get();
    }

    public TickFrame broadcastCritical(long battleId, Collection<Long> memberUids, SyncKind kind, String hint) {
        SyncKind k = kind == null ? SyncKind.SKILL_LOCKSTEP : kind;
        if (k == SyncKind.MOVE_STATE) {
            // 普通移动不走帧同步
            return new TickFrame(battleId, currentFrame(battleId), System.currentTimeMillis(), k, hint);
        }
        long frame = nextFrame(battleId);
        TickFrame tick = new TickFrame(battleId, frame, System.currentTimeMillis(), k,
                hint == null ? "" : hint);
        if (sessionManager == null || memberUids == null) {
            return tick;
        }
        String json = "{\"battleId\":" + battleId + ",\"frameNo\":" + frame
                + ",\"serverTimeMs\":" + tick.serverTimeMs()
                + ",\"kind\":\"" + k.name() + "\",\"hint\":\"" + escape(tick.payloadHint()) + "\"}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        for (Long uid : memberUids) {
            if (uid == null || uid <= 0) {
                continue;
            }
            GameSession s = sessionManager.getOrNull(uid);
            if (s != null) {
                s.send(new GamePacket(CmdIds.BATTLE_TICK_PROGRESS_SC_NOTIFY, bytes));
            }
        }
        return tick;
    }

    public void clear(long battleId) {
        frames.remove(battleId);
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
