package cn.itcast.demo.mylunarcore.battle;

import cn.itcast.demo.mylunarcore.guild.RaidRoleAssignmentService;
import org.springframework.stereotype.Component;

/**
 * 职责加成：TANK 减伤、HEALER 治疗、DPS 暴击伤害。
 */
@Component
public class RoleBuffProvider {

    public record RoleModifiers(double damageTakenMul, double healMul, double critDmgBonusPct) {
        public static RoleModifiers identity() {
            return new RoleModifiers(1.0, 1.0, 0);
        }
    }

    public RoleModifiers forRole(RaidRoleAssignmentService.RaidRole role) {
        if (role == null) {
            return RoleModifiers.identity();
        }
        return switch (role) {
            case TANK -> new RoleModifiers(0.80, 1.0, 0);
            case HEALER -> new RoleModifiers(1.0, 1.25, 0);
            case DPS -> new RoleModifiers(1.0, 1.0, 10);
        };
    }

    public int applyIncomingDamage(int rawDamage, RaidRoleAssignmentService.RaidRole role) {
        RoleModifiers m = forRole(role);
        return Math.max(0, (int) Math.round(rawDamage * m.damageTakenMul()));
    }

    public int applyHeal(int rawHeal, RaidRoleAssignmentService.RaidRole role) {
        RoleModifiers m = forRole(role);
        return Math.max(0, (int) Math.round(rawHeal * m.healMul()));
    }
}
