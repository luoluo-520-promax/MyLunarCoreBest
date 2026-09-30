package cn.itcast.demo.mylunarcore.battle;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 仇恨表：按伤害量与嘲讽实时更新，供怪物 AI 优先攻击 TANK。
 */
@Component
public class HateTable {

    public record HateEntry(int entityId, long hate, boolean taunting) {}

    private final Map<Long, Map<Integer, AtomicLong>> byBattle = new ConcurrentHashMap<>();
    private final Map<Long, Map<Integer, Boolean>> tauntFlags = new ConcurrentHashMap<>();

    public void addDamageHate(long battleId, int entityId, long damage) {
        if (battleId <= 0 || entityId <= 0 || damage <= 0) {
            return;
        }
        byBattle.computeIfAbsent(battleId, k -> new ConcurrentHashMap<>())
                .computeIfAbsent(entityId, k -> new AtomicLong())
                .addAndGet(damage);
    }

    public void applyTaunt(long battleId, int entityId, long bonusHate) {
        if (battleId <= 0 || entityId <= 0) {
            return;
        }
        tauntFlags.computeIfAbsent(battleId, k -> new ConcurrentHashMap<>()).put(entityId, true);
        addDamageHate(battleId, entityId, Math.max(1, bonusHate));
    }

    public void clearTaunt(long battleId, int entityId) {
        Map<Integer, Boolean> flags = tauntFlags.get(battleId);
        if (flags != null) {
            flags.remove(entityId);
        }
    }

    public int topTarget(long battleId) {
        Map<Integer, AtomicLong> table = byBattle.get(battleId);
        if (table == null || table.isEmpty()) {
            return 0;
        }
        Map<Integer, Boolean> flags = tauntFlags.getOrDefault(battleId, Map.of());
        return table.entrySet().stream()
                .max(Comparator
                        .<Map.Entry<Integer, AtomicLong>>comparingInt(e -> flags.getOrDefault(e.getKey(), false) ? 1 : 0)
                        .thenComparingLong(e -> e.getValue().get()))
                .map(Map.Entry::getKey)
                .orElse(0);
    }

    public List<HateEntry> snapshot(long battleId) {
        Map<Integer, AtomicLong> table = byBattle.get(battleId);
        if (table == null) {
            return List.of();
        }
        Map<Integer, Boolean> flags = tauntFlags.getOrDefault(battleId, Map.of());
        List<HateEntry> out = new ArrayList<>();
        for (Map.Entry<Integer, AtomicLong> e : table.entrySet()) {
            out.add(new HateEntry(e.getKey(), e.getValue().get(), flags.getOrDefault(e.getKey(), false)));
        }
        out.sort(Comparator.comparingLong(HateEntry::hate).reversed());
        return out;
    }

    public void clearBattle(long battleId) {
        byBattle.remove(battleId);
        tauntFlags.remove(battleId);
    }
}
