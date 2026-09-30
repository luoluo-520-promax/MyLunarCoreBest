package cn.itcast.demo.mylunarcore.assist;

import cn.itcast.demo.mylunarcore.equipment.EquipmentAffixSnapshotService;
import cn.itcast.demo.mylunarcore.model.AvatarEntity;
import cn.itcast.demo.mylunarcore.model.PlayerData;
import cn.itcast.demo.mylunarcore.model.PlayerEntity;
import cn.itcast.demo.mylunarcore.model.QuestProgressEntity;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 玩家画像投影：常用角色、装备词条快照、未完成任务等，供个性化策略推荐。
 */
@Component
public class AssistPlayerProfileService {

    private final EquipmentAffixSnapshotService equipmentAffixSnapshotService;

    public AssistPlayerProfileService(EquipmentAffixSnapshotService equipmentAffixSnapshotService) {
        this.equipmentAffixSnapshotService = equipmentAffixSnapshotService;
    }

    public Map<String, Object> buildProfile(PlayerData playerData, List<QuestProgressEntity> quests) {
        Map<String, Object> profile = new LinkedHashMap<>();
        if (playerData != null && playerData.getPlayer() != null) {
            PlayerEntity p = playerData.getPlayer();
            profile.put("level", p.getLevel());
            profile.put("worldLevel", p.getWorldLevel());
            profile.put("stamina", p.getStamina());
            profile.put("quotaTier", p.getLevel() >= 40 ? "vip" : "free");
        } else {
            profile.put("quotaTier", "free");
        }
        List<Map<String, Object>> frequent = topAvatars(playerData, 5);
        profile.put("frequentAvatars", frequent);
        profile.put("incompleteQuests", incompleteQuests(quests, 5));
        if (equipmentAffixSnapshotService != null && playerData != null) {
            Map<String, Object> equip = equipmentAffixSnapshotService.buildSnapshot(playerData, 8);
            profile.put("equipmentSnapshot", equip);
            Object bestCrit = equip.get("bestCritDmg");
            if (bestCrit instanceof Number n && n.doubleValue() >= 20) {
                profile.put("topSubStatHint", "最高暴击伤害约 " + n.intValue() + "%，养成决策可优先利用");
            }
        }
        profile.put("personalizationHint", buildHint(frequent, quests, profile));
        return Map.copyOf(profile);
    }

    private static List<Map<String, Object>> topAvatars(PlayerData playerData, int limit) {
        if (playerData == null || playerData.getAvatars() == null || playerData.getAvatars().isEmpty()) {
            return List.of();
        }
        List<AvatarEntity> sorted = new ArrayList<>(playerData.getAvatars());
        sorted.sort(Comparator
                .comparingInt(AvatarEntity::getLevel).reversed()
                .thenComparingInt(AvatarEntity::getRank).reversed());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AvatarEntity a : sorted) {
            if (a == null || a.getAvatarId() <= 0) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("avatarId", a.getAvatarId());
            row.put("level", a.getLevel());
            row.put("rank", a.getRank());
            rows.add(row);
            if (rows.size() >= limit) {
                break;
            }
        }
        return List.copyOf(rows);
    }

    private static List<Map<String, Object>> incompleteQuests(List<QuestProgressEntity> quests, int limit) {
        if (quests == null || quests.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (QuestProgressEntity q : quests) {
            if (q == null) {
                continue;
            }
            if (q.getStatus() != CoachRuleEngine.QUEST_STATUS_IN_PROGRESS
                    && q.getStatus() != CoachRuleEngine.QUEST_STATUS_READY_SUBMIT) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("questId", q.getQuestId());
            row.put("status", q.getStatus());
            rows.add(row);
            if (rows.size() >= limit) {
                break;
            }
        }
        return List.copyOf(rows);
    }

    private static String buildHint(List<Map<String, Object>> frequent, List<QuestProgressEntity> quests,
                                    Map<String, Object> profile) {
        StringBuilder sb = new StringBuilder();
        if (!frequent.isEmpty()) {
            Object id = frequent.get(0).get("avatarId");
            Object lv = frequent.get(0).get("level");
            sb.append("常用角色 avatarId=").append(id).append(" Lv.").append(lv);
            sb.append("，配队/养成建议可优先围绕该角色展开");
        }
        long stuck = quests == null ? 0 : quests.stream()
                .filter(q -> q != null && q.getStatus() == CoachRuleEngine.QUEST_STATUS_IN_PROGRESS)
                .count();
        if (stuck > 0) {
            if (!sb.isEmpty()) {
                sb.append('；');
            }
            sb.append("当前有 ").append(stuck).append(" 个进行中任务，可优先给卡关引导");
        }
        Object subHint = profile.get("topSubStatHint");
        if (subHint instanceof String s && !s.isBlank()) {
            if (!sb.isEmpty()) {
                sb.append('；');
            }
            sb.append(s);
        }
        return sb.toString();
    }
}
