package cn.itcast.demo.mylunarcore.anticheat;

import cn.itcast.demo.mylunarcore.battle.BattleContext;
import cn.itcast.demo.mylunarcore.battle.BuffModifierCatalog;
import cn.itcast.demo.mylunarcore.battle.CombatAttributeSheet;
import cn.itcast.demo.mylunarcore.battle.EntityState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("BattleDeterministicValidator 增量属性伤害")
class BattleDeterministicValidatorAttributeTest {

    @Test
    @DisplayName("Buff 攻击提升应提高权威伤害")
    void buffIncreasesServerDamage() {
        BattleAuditService audit = new BattleAuditService();
        BattleDeterministicValidator validator = new BattleDeterministicValidator(audit);
        BattleContext ctx = BattleContext.createNew(1L, 1, 1, 1, 0, List.of());
        EntityState atk = new EntityState(10, 1000, false);
        EntityState def = new EntityState(20, 1000, false);
        atk.setAttributeSheet(new CombatAttributeSheet(
                new CombatAttributeSheet.Snapshot(1000, 100, 50, 100, 5, 50)));
        def.setAttributeSheet(new CombatAttributeSheet(
                new CombatAttributeSheet.Snapshot(1000, 50, 50, 100, 5, 50)));
        ctx.putEntity(atk);
        ctx.putEntity(def);

        var base = validator.validateAndCompute(ctx,
                new BattleDeterministicValidator.DamageIntent(10, 20, 1, 100, 0));
        BuffModifierCatalog catalog = new BuffModifierCatalog();
        atk.addBuffStack(1001, 2, 5, catalog::perStack);
        var buffed = validator.validateAndCompute(ctx,
                new BattleDeterministicValidator.DamageIntent(10, 20, 1, 100, 0));
        assertTrue(buffed.accepted());
        assertTrue(buffed.serverDamage() > base.serverDamage());
    }
}
