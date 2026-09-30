package cn.itcast.demo.mylunarcore.guild;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Raid 职责分配：TANK / HEALER / DPS，支持推荐与手动指定。
 */
@Service
public class RaidRoleAssignmentService {

    public enum RaidRole { TANK, HEALER, DPS }

    public record Assignment(int playerId, RaidRole role, boolean manual) {}

    /** instanceId → playerId → assignment */
    private final Map<String, Map<Integer, Assignment>> byInstance = new ConcurrentHashMap<>();

    public List<Assignment> recommend(String instanceId, List<Integer> playerIds) {
        List<Assignment> out = new ArrayList<>();
        if (playerIds == null || playerIds.isEmpty()) {
            return out;
        }
        int n = playerIds.size();
        int tanks = Math.max(1, n / 5);
        int healers = Math.max(1, n / 5);
        for (int i = 0; i < n; i++) {
            RaidRole role;
            if (i < tanks) {
                role = RaidRole.TANK;
            } else if (i < tanks + healers) {
                role = RaidRole.HEALER;
            } else {
                role = RaidRole.DPS;
            }
            Assignment a = new Assignment(playerIds.get(i), role, false);
            out.add(a);
            put(instanceId, a);
        }
        return out;
    }

    public Assignment assign(String instanceId, int playerId, RaidRole role) {
        if (instanceId == null || playerId <= 0 || role == null) {
            return null;
        }
        Assignment a = new Assignment(playerId, role, true);
        put(instanceId, a);
        return a;
    }

    public Assignment assign(String instanceId, int playerId, String roleName) {
        RaidRole role = parse(roleName);
        return assign(instanceId, playerId, role);
    }

    public RaidRole roleOf(String instanceId, int playerId) {
        Map<Integer, Assignment> map = byInstance.get(instanceId);
        if (map == null) {
            return RaidRole.DPS;
        }
        Assignment a = map.get(playerId);
        return a == null ? RaidRole.DPS : a.role();
    }

    public List<Assignment> list(String instanceId) {
        Map<Integer, Assignment> map = byInstance.get(instanceId);
        if (map == null) {
            return List.of();
        }
        return List.copyOf(map.values());
    }

    public Map<RaidRole, Integer> counts(String instanceId) {
        EnumMap<RaidRole, Integer> counts = new EnumMap<>(RaidRole.class);
        for (RaidRole r : RaidRole.values()) {
            counts.put(r, 0);
        }
        for (Assignment a : list(instanceId)) {
            counts.merge(a.role(), 1, Integer::sum);
        }
        return counts;
    }

    private void put(String instanceId, Assignment a) {
        byInstance.computeIfAbsent(instanceId, k -> new ConcurrentHashMap<>()).put(a.playerId(), a);
    }

    private static RaidRole parse(String name) {
        if (name == null || name.isBlank()) {
            return RaidRole.DPS;
        }
        try {
            return RaidRole.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return RaidRole.DPS;
        }
    }
}
