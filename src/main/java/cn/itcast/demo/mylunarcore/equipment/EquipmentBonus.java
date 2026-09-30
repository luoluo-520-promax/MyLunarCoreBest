package cn.itcast.demo.mylunarcore.equipment;

/**
 * 光锥/遗器折算后的面板增量（平铺数值，供 AttributeCalculator 叠加）。
 */
public record EquipmentBonus(
        int hpFlat,
        int atkFlat,
        int defFlat,
        int spdFlat,
        double hpPct,
        double atkPct,
        double defPct,
        double critRate,
        double critDmg
) {
    public static EquipmentBonus zero() {
        return new EquipmentBonus(0, 0, 0, 0, 0, 0, 0, 0, 0);
    }

    public EquipmentBonus plus(EquipmentBonus other) {
        if (other == null) {
            return this;
        }
        return new EquipmentBonus(
                hpFlat + other.hpFlat,
                atkFlat + other.atkFlat,
                defFlat + other.defFlat,
                spdFlat + other.spdFlat,
                hpPct + other.hpPct,
                atkPct + other.atkPct,
                defPct + other.defPct,
                critRate + other.critRate,
                critDmg + other.critDmg
        );
    }
}
