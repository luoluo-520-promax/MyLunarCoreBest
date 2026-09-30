package cn.itcast.demo.mylunarcore.scene;

import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 战术标记（Ping）：地面集合点 / 集火 / 打断 / 撤退，经 AOI 广播并附带倒计时。
 */
@Service
public class ScenePingService {

    public enum PingType { RALLY, FOCUS_FIRE, DANGER, HELP, INTERRUPT, RETREAT }

    public record Ping(String pingId, long sceneId, int fromPlayerId, PingType type,
                       float x, float y, float z, int targetEntityId, long createdAtMs,
                       String subType, int countdownMs) {}

    public record PingResult(boolean ok, int retcode, Ping ping) {}

    private final Map<String, Ping> recent = new ConcurrentHashMap<>();
    private final Map<Long, Set<Integer>> sceneMembers = new ConcurrentHashMap<>();
    private final Map<String, Set<Integer>> ackByPing = new ConcurrentHashMap<>();
    private final GameSessionManager sessionManager;

    public ScenePingService(ObjectProvider<GameSessionManager> sessionProvider) {
        this.sessionManager = sessionProvider == null ? null : sessionProvider.getIfAvailable();
    }

    public void registerMember(long sceneId, int playerId) {
        sceneMembers.computeIfAbsent(sceneId, k -> ConcurrentHashMap.newKeySet()).add(playerId);
    }

    public void unregisterMember(long sceneId, int playerId) {
        Set<Integer> set = sceneMembers.get(sceneId);
        if (set != null) {
            set.remove(playerId);
        }
    }

    public PingResult ping(long sceneId, int fromPlayerId, PingType type,
                           float x, float y, float z, int targetEntityId) {
        return ping(sceneId, fromPlayerId, type, x, y, z, targetEntityId, null, defaultCountdown(type));
    }

    public PingResult ping(long sceneId, int fromPlayerId, PingType type,
                           float x, float y, float z, int targetEntityId,
                           String subType, int countdownMs) {
        if (fromPlayerId <= 0 || type == null) {
            return new PingResult(false, 1, null);
        }
        PingType resolved = type;
        if (subType != null && !subType.isBlank()) {
            try {
                resolved = PingType.valueOf(subType.trim().toUpperCase(Locale.ROOT));
            } catch (Exception ignored) {
                // keep original type
            }
        }
        String id = "ping-" + UUID.randomUUID().toString().substring(0, 8);
        int cd = countdownMs > 0 ? countdownMs : defaultCountdown(resolved);
        Ping p = new Ping(id, sceneId, fromPlayerId, resolved, x, y, z, targetEntityId,
                System.currentTimeMillis(),
                subType == null || subType.isBlank() ? resolved.name() : subType,
                cd);
        recent.put(id, p);
        ackByPing.put(id, ConcurrentHashMap.newKeySet());
        broadcast(p);
        return new PingResult(true, 0, p);
    }

    /** 队友确认响应；未确认则客户端可显示「未就绪」。 */
    public boolean ack(String pingId, int playerId) {
        Set<Integer> set = ackByPing.get(pingId);
        if (set == null || playerId <= 0) {
            return false;
        }
        set.add(playerId);
        return true;
    }

    public boolean isReady(String pingId, int playerId) {
        Set<Integer> set = ackByPing.get(pingId);
        return set != null && set.contains(playerId);
    }

    public List<Integer> pendingMembers(String pingId, long sceneId) {
        Set<Integer> members = sceneMembers.getOrDefault(sceneId, Set.of());
        Set<Integer> acked = ackByPing.getOrDefault(pingId, Set.of());
        List<Integer> pending = new ArrayList<>();
        for (Integer m : members) {
            if (!acked.contains(m)) {
                pending.add(m);
            }
        }
        return pending;
    }

    public List<Ping> listRecent(long sceneId, long sinceMs) {
        List<Ping> out = new ArrayList<>();
        for (Ping p : recent.values()) {
            if (p.sceneId() == sceneId && p.createdAtMs() >= sinceMs) {
                out.add(p);
            }
        }
        return out;
    }

    private void broadcast(Ping p) {
        if (sessionManager == null) {
            return;
        }
        Set<Integer> members = sceneMembers.getOrDefault(p.sceneId(), Set.of());
        String json = "{\"pingId\":\"" + p.pingId() + "\",\"sceneId\":" + p.sceneId()
                + ",\"fromPlayerId\":" + p.fromPlayerId() + ",\"type\":\"" + p.type()
                + "\",\"subType\":\"" + p.subType() + "\",\"countdownMs\":" + p.countdownMs()
                + ",\"x\":" + p.x() + ",\"y\":" + p.y() + ",\"z\":" + p.z()
                + ",\"targetEntityId\":" + p.targetEntityId() + "}";
        byte[] payload = json.getBytes(StandardCharsets.UTF_8);
        for (Integer pid : members) {
            GameSession s = sessionManager.getOrNull(pid);
            if (s != null) {
                s.send(new GamePacket(CmdIds.SCENE_PING_SC_NOTIFY, payload));
            }
        }
    }

    private static int defaultCountdown(PingType type) {
        if (type == null) {
            return 0;
        }
        return switch (type) {
            case FOCUS_FIRE -> 5000;
            case INTERRUPT -> 3000;
            case RETREAT -> 4000;
            default -> 0;
        };
    }
}
