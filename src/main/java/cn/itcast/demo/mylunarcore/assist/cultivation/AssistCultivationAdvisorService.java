package cn.itcast.demo.mylunarcore.assist.cultivation;

import cn.itcast.demo.mylunarcore.equipment.EquipmentAffixService;
import cn.itcast.demo.mylunarcore.equipment.EquipmentBonus;
import cn.itcast.demo.mylunarcore.model.AvatarEntity;
import cn.itcast.demo.mylunarcore.model.GameItemEntity;
import cn.itcast.demo.mylunarcore.model.PlayerData;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 养成优先级顾问：结合背包遗器词条、深渊弱点环境，对比两名角色并给出预期伤害提升。
 */
@Component
public class AssistCultivationAdvisorService {

    private static final Pattern COMPARE = Pattern.compile(
            "先拉(.+?)还是(.+?)[？?]?$|(.+?)和(.+?)先养谁");
    /** 深渊第 12 层常见弱点（可配置化扩展） */
    private static final List<String> ABYSS_F12_WEAKS = List.of("imaginary", "quantum", "ice");

    private final EquipmentAffixService equipmentAffixService;

    public AssistCultivationAdvisorService(EquipmentAffixService equipmentAffixService) {
        this.equipmentAffixService = equipmentAffixService;
    }

    public record CultivationAdvice(int recommendedAvatarId, String recommendedName,
                                    int expectedDmgIncreasePercent, String reason) {
        public boolean present() {
            return recommendedAvatarId > 0;
        }
    }

    public boolean looksLikeCompareQuestion(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String q = question.toLowerCase(Locale.ROOT);
        return q.contains("先拉") || q.contains("先养") || q.contains("谁优先")
                || COMPARE.matcher(question).find();
    }

    public CultivationAdvice advise(PlayerData playerData, String question) {
        if (playerData == null || playerData.getAvatars() == null || playerData.getAvatars().isEmpty()) {
            return empty();
        }
        List<AvatarCandidate> candidates = new ArrayList<>();
        for (AvatarEntity a : playerData.getAvatars()) {
            if (a == null || a.getAvatarId() <= 0) {
                continue;
            }
            List<GameItemEntity> equipped = listEquipped(playerData, a.getAvatarId());
            EquipmentBonus bonus = equipmentAffixService.sumEquippedBonus(equipped);
            double score = scoreAvatar(a, bonus, equipped);
            candidates.add(new AvatarCandidate(a.getAvatarId(), nameFor(a.getAvatarId()), score, bonus, equipped));
        }
        if (candidates.isEmpty()) {
            return empty();
        }

        AvatarCandidate a = pickByQuestion(candidates, question);
        AvatarCandidate b = pickSecond(candidates, a);
        if (b == null) {
            b = candidates.get(0);
        }
        if (a == null) {
            a = candidates.stream().max(java.util.Comparator.comparingDouble(AvatarCandidate::score)).orElse(candidates.get(0));
        }
        double delta = a.score() - b.score();
        int pct = (int) Math.round(Math.max(5, Math.min(35, delta * 100)));
        String relicHint = bestSubStat(a);
        String reason = "对比背包遗器：你有" + relicHint + "，建议先拉「" + a.name()
                + "」，相对「" + b.name() + "」伤害预期高约 " + pct + "%；"
                + "当前深渊弱点偏向 " + String.join("/", ABYSS_F12_WEAKS) + "。";
        return new CultivationAdvice(a.avatarId(), a.name(), pct, reason);
    }

    private static AvatarCandidate pickByQuestion(List<AvatarCandidate> candidates, String question) {
        if (question == null) {
            return null;
        }
        Matcher m = COMPARE.matcher(question);
        if (m.find()) {
            String left = m.group(1) != null ? m.group(1) : m.group(3);
            String right = m.group(2) != null ? m.group(2) : m.group(4);
            AvatarCandidate lc = matchName(candidates, left);
            AvatarCandidate rc = matchName(candidates, right);
            if (lc != null && rc != null) {
                return lc.score() >= rc.score() ? lc : rc;
            }
        }
        for (AvatarCandidate c : candidates) {
            if (question.contains(c.name()) || question.contains(String.valueOf(c.avatarId()))) {
                return c;
            }
        }
        return null;
    }

    private static AvatarCandidate pickSecond(List<AvatarCandidate> candidates, AvatarCandidate first) {
        if (first == null) {
            return candidates.size() > 1 ? candidates.get(1) : null;
        }
        for (AvatarCandidate c : candidates) {
            if (c.avatarId() != first.avatarId()) {
                return c;
            }
        }
        return null;
    }

    private static AvatarCandidate matchName(List<AvatarCandidate> candidates, String fragment) {
        if (fragment == null || fragment.isBlank()) {
            return null;
        }
        String f = fragment.trim();
        for (AvatarCandidate c : candidates) {
            if (c.name().contains(f) || f.contains(c.name()) || f.contains(String.valueOf(c.avatarId()))) {
                return c;
            }
        }
        return null;
    }

    private double scoreAvatar(AvatarEntity a, EquipmentBonus bonus, List<GameItemEntity> equipped) {
        double base = a.getLevel() * 2 + a.getRank() * 15 + bonus.atkFlat() + bonus.atkPct() * 10;
        base += bonus.critDmg() * 50 + bonus.critRate() * 30;
        int elementBonus = elementMatchScore(a.getAvatarId());
        return base * (1.0 + elementBonus * 0.05);
    }

    private int elementMatchScore(int avatarId) {
        String el = avatarId % 4 == 1 ? "imaginary" : avatarId % 4 == 0 ? "quantum" : "ice";
        for (String w : ABYSS_F12_WEAKS) {
            if (w.equals(el)) {
                return 2;
            }
        }
        return 0;
    }

    private static String bestSubStat(AvatarCandidate c) {
        if (c.bonus().critDmg() >= 20) {
            return "极品暴击伤害词条（约 " + (int) c.bonus().critDmg() + "%）";
        }
        if (c.bonus().critRate() >= 10) {
            return "高暴击率词条";
        }
        if (c.bonus().atkPct() >= 10) {
            return "高攻击力百分比词条";
        }
        return "可用遗器套装";
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

    private static String nameFor(int avatarId) {
        return switch (avatarId % 5) {
            case 0 -> "真理医生";
            case 1 -> "托帕";
            case 2 -> "黄泉";
            case 3 -> "花火";
            default -> "角色" + avatarId;
        };
    }

    private static CultivationAdvice empty() {
        return new CultivationAdvice(0, "", 0, "");
    }

    private record AvatarCandidate(int avatarId, String name, double score,
                                 EquipmentBonus bonus, List<GameItemEntity> equipped) {
    }
}
