package cn.itcast.demo.mylunarcore.gacha;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * GachaBannerType 卡池类型转换测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code GachaBannerTypeTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("GachaBannerType 卡池类型转换测试")
class GachaBannerTypeTest {

    private static final Logger log = LoggerFactory.getLogger(GachaBannerTypeTest.class);

    @ParameterizedTest(name = "gachaType={0} -> bannerType={1}")
    @CsvSource({
            "Newbie, 1",
            "Normal, 2",
            "AvatarUp, 11",
            "WeaponUp, 12"
    })
    @DisplayName("fromGachaTypeString 应正确映射已知类型")
    void fromGachaTypeStringShouldMapKnownTypes(String gachaType, int expectedType) {
        int actual = GachaBannerType.fromGachaTypeString(gachaType);
        log.info("类型映射校验: gachaType={}, expectedType={}, actualType={}", gachaType, expectedType, actual);
        assertEquals(expectedType, actual);
    }

    /**
     * 验证点：null 或未知字符串应返回 0。
     * <p>测试方法 {@code unknownOrNullShouldReturnZero}：
     * <ul>
     *   <li>{@code assertEquals(0, nullType);}</li>
     *   <li>{@code assertEquals(0, unknownType);}</li>
     * </ul>
     */
    @Test
    @DisplayName("null 或未知字符串应返回 0")
    void unknownOrNullShouldReturnZero() {
        int nullType = GachaBannerType.fromGachaTypeString(null);
        int unknownType = GachaBannerType.fromGachaTypeString("InvalidPool");
        log.info("非法类型校验: nullType={}, unknownType={}, unknownInput=InvalidPool", nullType, unknownType);
        assertEquals(0, nullType);
        assertEquals(0, unknownType);
    }
}
