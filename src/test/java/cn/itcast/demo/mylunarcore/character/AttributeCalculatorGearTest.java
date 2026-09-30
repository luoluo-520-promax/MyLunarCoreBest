package cn.itcast.demo.mylunarcore.character;

import cn.itcast.demo.mylunarcore.equipment.EquipmentBonus;
import cn.itcast.demo.mylunarcore.model.AvatarEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AttributeCalculator 装备加成。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code AttributeCalculatorGearTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("AttributeCalculator 装备加成")
class AttributeCalculatorGearTest {

    /**
     * 验证点：光锥/遗器百分比与平坦值应提升面板。
     * <p>测试方法 {@code gearBoostsPanel}：
     * <ul>
     *   <li>{@code assertTrue(withGear.hp() > base.hp());}</li>
     *   <li>{@code assertTrue(withGear.atk() > base.atk());}</li>
     *   <li>{@code assertTrue(withGear.spd() > base.spd());}</li>
     *   <li>{@code assertTrue(withGear.critRate() > base.critRate());}</li>
     * </ul>
     */
    @Test
    @DisplayName("光锥/遗器百分比与平坦值应提升面板")
    void gearBoostsPanel() {
        AttributeCalculator calc = new AttributeCalculator();
        AvatarEntity avatar = CharacterTestFixtures.avatar(1001, 20, 0, 2, 1);
        AttributeCalculator.AvatarAttributes base = calc.calculate(avatar);
        EquipmentBonus gear = new EquipmentBonus(100, 50, 20, 5, 10, 12, 0, 8, 16);
        AttributeCalculator.AvatarAttributes withGear = calc.calculate(avatar, gear);
        assertTrue(withGear.hp() > base.hp());
        assertTrue(withGear.atk() > base.atk());
        assertTrue(withGear.spd() > base.spd());
        assertTrue(withGear.critRate() > base.critRate());
    }
}
