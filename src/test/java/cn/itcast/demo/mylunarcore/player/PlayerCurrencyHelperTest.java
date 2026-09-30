package cn.itcast.demo.mylunarcore.player;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PlayerCurrencyHelper 货币 JSON 解析测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code PlayerCurrencyHelperTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("PlayerCurrencyHelper 货币 JSON 解析测试")
class PlayerCurrencyHelperTest {

    private static final Logger log = LoggerFactory.getLogger(PlayerCurrencyHelperTest.class);

    /**
     * 验证点：标准 JSON 应解析为货币 Map。
     * <p>测试方法 {@code parseCurrencyShouldParseStandardJson}：
     * <ul>
     *   <li>{@code assertEquals(2, map.size());}</li>
     *   <li>{@code assertEquals(1000, map.get(1));}</li>
     *   <li>{@code assertEquals(50, map.get(2));}</li>
     * </ul>
     */
    @Test
    @DisplayName("标准 JSON 应解析为货币 Map")
    void parseCurrencyShouldParseStandardJson() {
        Map<Integer, Integer> map = PlayerCurrencyHelper.parseCurrency(PlayerTestFixtures.CURRENCY_JSON);

        log.info("标准 JSON 解析: json={}, size={}, type1={}, type2={}",
                PlayerTestFixtures.CURRENCY_JSON, map.size(), map.get(1), map.get(2));
        assertEquals(2, map.size());
        assertEquals(1000, map.get(1));
        assertEquals(50, map.get(2));
    }

    /**
     * 验证点：宽松格式应兼容无引号键值。
     * <p>测试方法 {@code parseCurrencyShouldParseLooseFormat}：
     * <ul>
     *   <li>{@code assertEquals(2, map.size());}</li>
     *   <li>{@code assertEquals(200, map.get(1));}</li>
     *   <li>{@code assertEquals(30, map.get(2));}</li>
     * </ul>
     */
    @Test
    @DisplayName("宽松格式应兼容无引号键值")
    void parseCurrencyShouldParseLooseFormat() {
        String json = "{1:200, 2:30}";
        Map<Integer, Integer> map = PlayerCurrencyHelper.parseCurrency(json);

        log.info("宽松格式解析: json={}, size={}, type1={}, type2={}", json, map.size(), map.get(1), map.get(2));
        assertEquals(2, map.size());
        assertEquals(200, map.get(1));
        assertEquals(30, map.get(2));
    }

    /**
     * 验证点：null 或空字符串应返回空 Map。
     * <p>测试方法 {@code parseCurrencyShouldReturnEmptyForBlank}：
     * <ul>
     *   <li>{@code assertTrue(nullMap.isEmpty());}</li>
     *   <li>{@code assertTrue(blankMap.isEmpty());}</li>
     * </ul>
     */
    @Test
    @DisplayName("null 或空字符串应返回空 Map")
    void parseCurrencyShouldReturnEmptyForBlank() {
        Map<Integer, Integer> nullMap = PlayerCurrencyHelper.parseCurrency(null);
        Map<Integer, Integer> blankMap = PlayerCurrencyHelper.parseCurrency("  ");

        log.info("空输入解析: nullSize={}, blankSize={}", nullMap.size(), blankMap.size());
        assertTrue(nullMap.isEmpty());
        assertTrue(blankMap.isEmpty());
    }

    /**
     * 验证点：非法 JSON 应降级为空 Map。
     * <p>测试方法 {@code parseCurrencyShouldReturnEmptyOnInvalidJson}：
     * <ul>
     *   <li>{@code assertTrue(map.isEmpty());}</li>
     * </ul>
     */
    @Test
    @DisplayName("非法 JSON 应降级为空 Map")
    void parseCurrencyShouldReturnEmptyOnInvalidJson() {
        String invalid = "not-json-at-all";
        Map<Integer, Integer> map = PlayerCurrencyHelper.parseCurrency(invalid);

        log.info("非法 JSON 容错: json={}, size={}", invalid, map.size());
        assertTrue(map.isEmpty());
    }
}
