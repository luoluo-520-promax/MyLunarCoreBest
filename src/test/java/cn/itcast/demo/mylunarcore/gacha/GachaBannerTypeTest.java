package cn.itcast.demo.mylunarcore.gacha;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
