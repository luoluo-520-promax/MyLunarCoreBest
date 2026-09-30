package cn.itcast.demo.mylunarcore.home;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 家园 Avatar 表现同步：复用「同家园房间」语义，只同步位置/皮肤/动作，不同步战斗属性。
 * 非好友以虚影（silhouette）显示，降低开销并保留「有人在家」的温暖感。
 */
@Service
public class HomePresenceService {

    public record Presence(long playerUid, float x, float y, float z, float rotY,
                           int skinId, String idleAnim, int interactFurnitureId, boolean silhouette) {
        public Presence {
            idleAnim = idleAnim == null || idleAnim.isBlank() ? "stand" : idleAnim;
        }

        /** 兼容旧 8 参构造（默认非虚影）。 */
        public Presence(long playerUid, float x, float y, float z, float rotY,
                        int skinId, String idleAnim, int interactFurnitureId) {
            this(playerUid, x, y, z, rotY, skinId, idleAnim, interactFurnitureId, false);
        }

        public Presence asSilhouette(boolean sil) {
            return new Presence(playerUid, x, y, z, rotY, skinId, idleAnim, interactFurnitureId, sil);
        }
    }

    /** hostPlayerId -> (visitorOrHostUid -> presence) */
    private final Map<Integer, Map<Long, Presence>> rooms = new ConcurrentHashMap<>();

    public void enter(int hostPlayerId, Presence presence) {
        if (hostPlayerId <= 0 || presence == null || presence.playerUid() <= 0) {
            return;
        }
        rooms.computeIfAbsent(hostPlayerId, h -> new ConcurrentHashMap<>())
                .put(presence.playerUid(), presence);
    }

    public void leave(int hostPlayerId, long playerUid) {
        Map<Long, Presence> room = rooms.get(hostPlayerId);
        if (room == null) {
            return;
        }
        room.remove(playerUid);
        if (room.isEmpty()) {
            rooms.remove(hostPlayerId, room);
        }
    }

    public Presence update(int hostPlayerId, Presence presence) {
        if (hostPlayerId <= 0 || presence == null) {
            return null;
        }
        Map<Long, Presence> room = rooms.computeIfAbsent(hostPlayerId, h -> new ConcurrentHashMap<>());
        room.put(presence.playerUid(), presence);
        return presence;
    }

    public List<Presence> list(int hostPlayerId) {
        Map<Long, Presence> room = rooms.get(hostPlayerId);
        if (room == null || room.isEmpty()) {
            return List.of();
        }
        return List.copyOf(room.values());
    }

    public List<Presence> listOthers(int hostPlayerId, long excludeUid) {
        List<Presence> all = list(hostPlayerId);
        if (all.isEmpty()) {
            return all;
        }
        List<Presence> out = new ArrayList<>(all.size());
        for (Presence p : all) {
            if (p.playerUid() != excludeUid) {
                out.add(p);
            }
        }
        return out;
    }

    private static final Set<String> ALLOWED_ACTIONS =
            Set.of("sit", "lie", "play_piano", "photo", "leave", "stand");

    public static boolean isAllowedAction(String action) {
        if (action == null || action.isBlank()) {
            return false;
        }
        return ALLOWED_ACTIONS.contains(action.trim().toLowerCase(Locale.ROOT));
    }

    public static String toIdleAnim(String action) {
        if (action == null) {
            return "stand";
        }
        String a = action.trim().toLowerCase(Locale.ROOT);
        return switch (a) {
            case "sit" -> "sit";
            case "lie" -> "lie";
            case "play_piano" -> "play_piano";
            case "photo" -> "photo";
            case "leave", "stand" -> "stand";
            default -> "stand";
        };
    }
}
