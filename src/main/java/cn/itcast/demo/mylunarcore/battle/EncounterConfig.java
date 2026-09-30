package cn.itcast.demo.mylunarcore.battle;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 场景遭遇配置：monsterId → battleStageId / 掉落 / 刷新 / 仇恨。
 */
public record EncounterConfig(
        int version,
        List<EncounterEntry> encounters,
        List<DropEntry> defaultDrops,
        int defaultPlayerExp,
        int propPickupItemId,
        int propPickupCount,
        int healingSpringHp,
        double defaultAggroRadius
) {
    public EncounterConfig {
        encounters = encounters == null ? List.of() : List.copyOf(encounters);
        defaultDrops = defaultDrops == null ? List.of() : List.copyOf(defaultDrops);
        if (defaultAggroRadius <= 0) {
            defaultAggroRadius = 5.0;
        }
    }

    public static EncounterConfig empty() {
        return new EncounterConfig(0, List.of(), List.of(), 0, 0, 0, 0, 5.0);
    }

    public Map<Integer, EncounterEntry> byMonsterId() {
        if (encounters.isEmpty()) {
            return Map.of();
        }
        return encounters.stream()
                .filter(e -> e != null && e.monsterId() > 0)
                .collect(Collectors.toMap(EncounterEntry::monsterId, Function.identity(), (a, b) -> a));
    }

    public EncounterEntry find(int monsterId) {
        return byMonsterId().get(monsterId);
    }

    public List<DropEntry> resolveDrops(int monsterId) {
        EncounterEntry entry = find(monsterId);
        if (entry != null && entry.drops() != null && !entry.drops().isEmpty()) {
            return entry.drops();
        }
        return defaultDrops;
    }

    public int resolvePlayerExp(int monsterId) {
        EncounterEntry entry = find(monsterId);
        if (entry != null && entry.playerExp() > 0) {
            return entry.playerExp();
        }
        return Math.max(0, defaultPlayerExp);
    }

    public int resolveStageId(int monsterId, int clientStageId) {
        EncounterEntry entry = find(monsterId);
        if (entry != null && entry.battleStageId() > 0) {
            return entry.battleStageId();
        }
        return clientStageId;
    }

    public record EncounterEntry(
            int monsterId,
            int battleStageId,
            int playerExp,
            int refreshSeconds,
            List<DropEntry> drops,
            Boolean aggressive,
            Double aggroRadius
    ) {
        public EncounterEntry {
            drops = drops == null ? List.of() : List.copyOf(drops);
        }
    }

    public record DropEntry(Integer itemId, Integer count, Integer currencyId, Integer amount) {
        public boolean hasItem() {
            return itemId != null && itemId > 0 && count != null && count > 0;
        }

        public boolean hasCurrency() {
            return currencyId != null && currencyId > 0 && amount != null && amount > 0;
        }
    }
}
