// 角色面板属性计算：将 level/promotion/rank 折算为 hp/atk/def/spd 战斗数值
package cn.itcast.demo.mylunarcore.character;

import cn.itcast.demo.mylunarcore.equipment.EquipmentBonus;
import cn.itcast.demo.mylunarcore.model.AvatarEntity;
import org.springframework.stereotype.Component;

/**
 * 角色面板属性计算器。
 * 将数据库离散成长字段统一折算为四维战斗面板，并可叠加光锥/遗器 {@link EquipmentBonus}。
 */
@Component
public class AttributeCalculator {

    /** 计算后的四维战斗属性快照，可直接嵌入 GetAvatarAttributes 协议响应。 */
    public record AvatarAttributes(int avatarId, int hp, int atk, int def, int spd,
                                   double critRate, double critDmg) {
        public AvatarAttributes(int avatarId, int hp, int atk, int def, int spd) {
            this(avatarId, hp, atk, def, spd, 5.0, 50.0);
        }
    }

    /**
     * 根据角色成长状态推导最终战斗属性（不含装备）。
     */
    public AvatarAttributes calculate(AvatarEntity avatar) {
        return calculate(avatar, EquipmentBonus.zero());
    }

    /**
     * 根据角色成长 + 光锥/遗器加成推导最终战斗属性。
     */
    public AvatarAttributes calculate(AvatarEntity avatar, EquipmentBonus gear) {
        if (avatar == null) {
            return new AvatarAttributes(0, 0, 0, 0, 0, 0, 0);
        }
        EquipmentBonus g = gear == null ? EquipmentBonus.zero() : gear;
        int level = Math.max(1, avatar.getLevel());
        int promotion = Math.max(0, avatar.getPromotion());
        int rank = Math.max(0, avatar.getRank());
        int baseHp = 1000 + level * 100 + promotion * 200 + rank * 50 + g.hpFlat();
        int baseAtk = 100 + level * 10 + promotion * 20 + rank * 5 + g.atkFlat();
        int baseDef = 50 + level * 5 + promotion * 10 + rank * 3 + g.defFlat();
        int baseSpd = 100 + level + promotion * 2 + g.spdFlat();
        int hp = (int) Math.round(baseHp * (1.0 + g.hpPct() / 100.0));
        int atk = (int) Math.round(baseAtk * (1.0 + g.atkPct() / 100.0));
        int def = (int) Math.round(baseDef * (1.0 + g.defPct() / 100.0));
        return new AvatarAttributes(
                avatar.getAvatarId(), hp, atk, def, baseSpd,
                5.0 + g.critRate(), 50.0 + g.critDmg());
    }
}
