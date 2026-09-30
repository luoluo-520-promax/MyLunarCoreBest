package cn.itcast.demo.mylunarcore.battle;

import cn.itcast.demo.mylunarcore.guild.RaidRoleAssignmentService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 治疗结算：单体回复 + 范围内队友溅射。
 */
@Service
public class BattleHealService {

    public record HealTarget(int entityId, float x, float y, float z) {}

    public record HealResult(int primaryEntityId, int primaryHeal, List<SplashHeal> splashes) {}

    public record SplashHeal(int entityId, int amount) {}

    private final RoleBuffProvider roleBuffProvider;

    public BattleHealService(RoleBuffProvider roleBuffProvider) {
        this.roleBuffProvider = roleBuffProvider;
    }

    public HealResult heal(int primaryEntityId, int rawHeal,
                           RaidRoleAssignmentService.RaidRole healerRole,
                           float centerX, float centerY, float centerZ,
                           float splashRadius, double splashRatio,
                           List<HealTarget> allies) {
        int primary = roleBuffProvider.applyHeal(rawHeal, healerRole);
        List<SplashHeal> splashes = new ArrayList<>();
        if (allies != null && splashRadius > 0 && splashRatio > 0) {
            int splashBase = Math.max(1, (int) Math.round(primary * splashRatio));
            float r2 = splashRadius * splashRadius;
            for (HealTarget t : allies) {
                if (t == null || t.entityId() == primaryEntityId) {
                    continue;
                }
                float dx = t.x() - centerX;
                float dy = t.y() - centerY;
                float dz = t.z() - centerZ;
                if (dx * dx + dy * dy + dz * dz <= r2) {
                    splashes.add(new SplashHeal(t.entityId(), splashBase));
                }
            }
        }
        return new HealResult(primaryEntityId, primary, List.copyOf(splashes));
    }
}
