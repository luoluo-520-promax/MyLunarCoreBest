package cn.itcast.demo.mylunarcore.equipment;

import cn.itcast.demo.mylunarcore.model.GameItemEntity;
import cn.itcast.demo.mylunarcore.model.PlayerData;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 为 AI 助手提供玩家装备/遗器词条快照投影。
 */
@Component
public class EquipmentAffixSnapshotService {

    private final EquipmentAffixService equipmentAffixService;

    public EquipmentAffixSnapshotService(EquipmentAffixService equipmentAffixService) {
        this.equipmentAffixService = equipmentAffixService;
    }

    public Map<String, Object> buildSnapshot(PlayerData playerData, int maxAvatars) {
        Map<String, Object> snap = new LinkedHashMap<>();
        if (playerData == null || playerData.getAvatars() == null) {
            return snap;
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        int limit = Math.max(1, maxAvatars);
        for (var avatar : playerData.getAvatars()) {
            if (avatar == null || avatar.getAvatarId() <= 0) {
                continue;
            }
            if (rows.size() >= limit) {
                break;
            }
            List<GameItemEntity> equipped = listEquipped(playerData, avatar.getAvatarId());
            EquipmentBonus bonus = equipmentAffixService.sumEquippedBonus(equipped);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("avatarId", avatar.getAvatarId());
            row.put("critDmg", bonus.critDmg());
            row.put("critRate", bonus.critRate());
            row.put("atkPct", bonus.atkPct());
            row.put("atkFlat", bonus.atkFlat());
            row.put("equippedCount", equipped.size());
            rows.add(row);
        }
        snap.put("equipmentByAvatar", rows);
        if (!rows.isEmpty()) {
            Map<String, Object> best = rows.stream()
                    .max(java.util.Comparator.comparingDouble(r ->
                            ((Number) r.getOrDefault("critDmg", 0)).doubleValue()))
                    .orElse(rows.get(0));
            snap.put("bestCritDmgAvatarId", best.get("avatarId"));
            snap.put("bestCritDmg", best.get("critDmg"));
        }
        return snap;
    }

    private static List<GameItemEntity> listEquipped(PlayerData data, int avatarId) {
        List<GameItemEntity> out = new ArrayList<>();
        if (data.getItems() == null) {
            return out;
        }
        for (GameItemEntity item : data.getItems()) {
            if (item == null || item.isDiscarded()) {
                continue;
            }
            if (item.getEquipAvatarId() != null && item.getEquipAvatarId() == avatarId) {
                out.add(item);
            }
        }
        return out;
    }
}
