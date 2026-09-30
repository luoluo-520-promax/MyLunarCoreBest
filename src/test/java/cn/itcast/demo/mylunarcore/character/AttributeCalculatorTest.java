package cn.itcast.demo.mylunarcore.character;

import cn.itcast.demo.mylunarcore.model.AvatarEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * AttributeCalculator 属性计算测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code AttributeCalculatorTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("AttributeCalculator 属性计算测试")
class AttributeCalculatorTest {

    private static final Logger log = LoggerFactory.getLogger(AttributeCalculatorTest.class);

    private AttributeCalculator calculator;

    @BeforeEach
    void setUp() {
        calculator = new AttributeCalculator();
        log.info("属性计算器初始化: formula=hp/atk/def/spd");
    }

    /**
     * 验证点：等级、突破与命座应按公式折算四维。
     * <p>测试方法 {@code calculateShouldScaleWithLevelPromotionAndRank}：
     * <ul>
     *   <li>{@code assertEquals(1001, attrs.avatarId());}</li>
     *   <li>{@code assertEquals(3450, attrs.hp());}</li>
     *   <li>{@code assertEquals(345, attrs.atk());}</li>
     *   <li>{@code assertEquals(173, attrs.def());}</li>
     *   <li>{@code assertEquals(124, attrs.spd());}</li>
     * </ul>
     */
    @Test
    @DisplayName("等级、突破与命座应按公式折算四维")
    void calculateShouldScaleWithLevelPromotionAndRank() {
        AvatarEntity avatar = CharacterTestFixtures.avatar(1001, 20, 0, 2, 1);

        AttributeCalculator.AvatarAttributes attrs = calculator.calculate(avatar);

        // hp=1000+20*100+2*200+1*50=3450, atk=345, def=173, spd=124
        log.info("属性折算校验: avatarId={}, level={}, promotion={}, rank={}, hp={}, atk={}, def={}, spd={}",
                attrs.avatarId(), avatar.getLevel(), avatar.getPromotion(), avatar.getRank(),
                attrs.hp(), attrs.atk(), attrs.def(), attrs.spd());
        assertEquals(1001, attrs.avatarId());
        assertEquals(3450, attrs.hp());
        assertEquals(345, attrs.atk());
        assertEquals(173, attrs.def());
        assertEquals(124, attrs.spd());
    }

    /**
     * 验证点：null 角色应返回全 0 属性快照。
     * <p>测试方法 {@code calculateNullShouldReturnZeros}：
     * <ul>
     *   <li>{@code assertEquals(0, attrs.avatarId());}</li>
     *   <li>{@code assertEquals(0, attrs.hp());}</li>
     *   <li>{@code assertEquals(0, attrs.atk());}</li>
     *   <li>{@code assertEquals(0, attrs.def());}</li>
     *   <li>{@code assertEquals(0, attrs.spd());}</li>
     * </ul>
     */
    @Test
    @DisplayName("null 角色应返回全 0 属性快照")
    void calculateNullShouldReturnZeros() {
        AttributeCalculator.AvatarAttributes attrs = calculator.calculate(null);

        log.info("空角色校验: avatarId={}, hp={}, atk={}, def={}, spd={}",
                attrs.avatarId(), attrs.hp(), attrs.atk(), attrs.def(), attrs.spd());
        assertEquals(0, attrs.avatarId());
        assertEquals(0, attrs.hp());
        assertEquals(0, attrs.atk());
        assertEquals(0, attrs.def());
        assertEquals(0, attrs.spd());
    }

    /**
     * 验证点：非法成长字段应钳制后计算。
     * <p>测试方法 {@code calculateShouldClampInvalidGrowthFields}：
     * <ul>
     *   <li>{@code assertEquals(2002, attrs.avatarId());}</li>
     *   <li>{@code assertEquals(1100, attrs.hp());}</li>
     *   <li>{@code assertEquals(110, attrs.atk());}</li>
     *   <li>{@code assertEquals(55, attrs.def());}</li>
     *   <li>{@code assertEquals(101, attrs.spd());}</li>
     * </ul>
     */
    @Test
    @DisplayName("非法成长字段应钳制后计算")
    void calculateShouldClampInvalidGrowthFields() {
        AvatarEntity avatar = CharacterTestFixtures.avatar(2002, 0, 0, -1, -3);

        AttributeCalculator.AvatarAttributes attrs = calculator.calculate(avatar);

        // level 钳到 1，promotion/rank 钳到 0 → hp=1100, atk=110, def=55, spd=101
        log.info("负值钳制校验: rawLevel={}, rawPromotion={}, rawRank={}, hp={}, atk={}, def={}, spd={}",
                avatar.getLevel(), avatar.getPromotion(), avatar.getRank(),
                attrs.hp(), attrs.atk(), attrs.def(), attrs.spd());
        assertEquals(2002, attrs.avatarId());
        assertEquals(1100, attrs.hp());
        assertEquals(110, attrs.atk());
        assertEquals(55, attrs.def());
        assertEquals(101, attrs.spd());
    }

    /**
     * 验证点：1 级无突破角色应得到基础成长面板。
     * <p>测试方法 {@code calculateLevelOneShouldReturnBasePanel}：
     * <ul>
     *   <li>{@code assertEquals(1100, attrs.hp());}</li>
     *   <li>{@code assertEquals(110, attrs.atk());}</li>
     *   <li>{@code assertEquals(55, attrs.def());}</li>
     *   <li>{@code assertEquals(101, attrs.spd());}</li>
     * </ul>
     */
    @Test
    @DisplayName("1 级无突破角色应得到基础成长面板")
    void calculateLevelOneShouldReturnBasePanel() {
        AvatarEntity avatar = CharacterTestFixtures.avatar(1001, 1, 0, 0, 0);

        AttributeCalculator.AvatarAttributes attrs = calculator.calculate(avatar);

        log.info("1级基础面板校验: avatarId={}, level=1, hp={}, atk={}, def={}, spd={}",
                attrs.avatarId(), attrs.hp(), attrs.atk(), attrs.def(), attrs.spd());
        assertEquals(1100, attrs.hp());
        assertEquals(110, attrs.atk());
        assertEquals(55, attrs.def());
        assertEquals(101, attrs.spd());
    }
}
