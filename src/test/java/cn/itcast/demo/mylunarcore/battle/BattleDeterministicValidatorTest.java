package cn.itcast.demo.mylunarcore.battle;

import cn.itcast.demo.mylunarcore.anticheat.BattleAuditService;
import cn.itcast.demo.mylunarcore.anticheat.BattleDamageFormula;
import cn.itcast.demo.mylunarcore.anticheat.BattleDeterministicValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BattleDeterministicValidator} / {@link BattleDamageFormula}：
 * 服务端权威伤害不随客户端申报变化；击破状态提升伤害。
 */
@DisplayName("BattleDeterministicValidator 确定性伤害校验")
class BattleDeterministicValidatorTest {

    /**
     * 同一攻击意图下，clientDamage 填 999999 与 1 时 serverDamage 应相等且 &gt;0，
     * 证明结算不采信客户端伤害数字。
     */
    @Test
    @DisplayName("服务端公式应给出正伤害且不依赖客户端申报")
    void shouldComputeServerAuthoritativeDamage() {
        BattleAuditService audit = new BattleAuditService();
        BattleDeterministicValidator validator = new BattleDeterministicValidator(audit);
        BattleContext ctx = BattleTestFixtures.createContext(1L, 42, BattleTestFixtures.singleWaveStage());

        // 取非玩家实体作为目标
        int targetId = ctx.getEntities().keySet().stream()
                .filter(id -> id != 42)
                .findFirst()
                .orElseThrow();

        BattleDeterministicValidator.ValidationResult r1 = validator.validateAndCompute(ctx,
                new BattleDeterministicValidator.DamageIntent(42, targetId, 1001, 100, 999_999));
        BattleDeterministicValidator.ValidationResult r2 = validator.validateAndCompute(ctx,
                new BattleDeterministicValidator.DamageIntent(42, targetId, 1001, 100, 1));

        assertTrue(r1.accepted());
        assertTrue(r2.accepted());
        assertEquals(r1.serverDamage(), r2.serverDamage());
        assertTrue(r1.serverDamage() > 0);
    }

    /**
     * 相同攻防参数下，broken=true 的伤害应严格大于 broken=false。
     */
    @Test
    @DisplayName("伤害公式随击破状态提高")
    void brokenShouldAmplifyDamage() {
        int normal = BattleDamageFormula.compute(200, 50, 100, false);
        int broken = BattleDamageFormula.compute(200, 50, 100, true);
        assertTrue(broken > normal);
    }
}
