package cn.itcast.demo.mylunarcore.character;

import cn.itcast.demo.mylunarcore.battle.BuffModifierCatalog;
import cn.itcast.demo.mylunarcore.battle.CombatAttributeSheet;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 将已解锁命座层转换为战斗 BuffModifier / 开局状态。
 */
@Component
public class ConstellationEffectApplier {

    public record AppliedEffect(String effectType, String stat, double value, int skillSlot,
                                int targetSkillId, String desc) {}

    public record ApplyResult(List<AppliedEffect> effects, int startShieldPct, double atkPctBonus,
                              double skillDmgBonus, double ultDmgBonus, double basicDmgBonus) {}

    private final CharacterConstellationConfigRepository configRepository;
    private final BuffModifierCatalog buffModifierCatalog;

    public ConstellationEffectApplier(CharacterConstellationConfigRepository configRepository,
                                      BuffModifierCatalog buffModifierCatalog) {
        this.configRepository = configRepository;
        this.buffModifierCatalog = buffModifierCatalog;
    }

    public ApplyResult apply(int characterId, int unlockedLayer) {
        List<CharacterConstellationConfigRepository.Layer> layers =
                configRepository.unlockedLayers(characterId, unlockedLayer);
        List<AppliedEffect> effects = new ArrayList<>();
        int startShield = 0;
        double atkPct = 0;
        double skillDmg = 0;
        double ultDmg = 0;
        double basicDmg = 0;
        for (CharacterConstellationConfigRepository.Layer layer : layers) {
            for (CharacterConstellationConfigRepository.Modifier m : layer.modifiers()) {
                effects.add(new AppliedEffect(layer.effectType(), m.stat(), m.value(),
                        m.skillSlot(), m.targetSkillId(), layer.desc()));
                switch (m.stat()) {
                    case "START_SHIELD" -> startShield += (int) m.value();
                    case "ATK_PCT" -> atkPct += m.value();
                    case "SKILL_DMG" -> skillDmg += m.value();
                    case "ULT_DMG" -> ultDmg += m.value();
                    case "BASIC_DMG" -> basicDmg += m.value();
                    default -> { /* MECHANIC / TALENT 由技能侧读取 effects 列表 */ }
                }
            }
        }
        return new ApplyResult(List.copyOf(effects), startShield, atkPct, skillDmg, ultDmg, basicDmg);
    }

    /**
     * 将 ATK_PCT 类命座写入 CombatAttributeSheet（buffId = 90_000 + characterId）。
     */
    public void applyToSheet(CombatAttributeSheet sheet, int characterId, int unlockedLayer) {
        if (sheet == null) {
            return;
        }
        ApplyResult result = apply(characterId, unlockedLayer);
        if (result.atkPctBonus() <= 0) {
            return;
        }
        int buffId = 90_000 + Math.max(0, characterId);
        CombatAttributeSheet.BuffDelta delta = new CombatAttributeSheet.BuffDelta(
                0, 0, 0, result.atkPctBonus(), 0, 0, 0);
        buffModifierCatalog.register(buffId, delta);
        sheet.onBuffStacksChanged(buffId, 1, delta);
    }
}
