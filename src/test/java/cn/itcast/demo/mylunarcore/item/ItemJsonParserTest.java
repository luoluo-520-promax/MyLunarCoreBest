package cn.itcast.demo.mylunarcore.item;

import cn.itcast.demo.mylunarcore.protocol.ItemSystemProto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ItemJsonParser 副词条 JSON 解析测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code ItemJsonParserTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("ItemJsonParser 副词条 JSON 解析测试")
class ItemJsonParserTest {

    private static final Logger log = LoggerFactory.getLogger(ItemJsonParserTest.class);

    private ItemJsonParser parser;

    @BeforeEach
    void setUp() {
        parser = new ItemJsonParser();
        log.info("副词条解析器初始化: parserClass={}", parser.getClass().getSimpleName());
    }

    /**
     * 验证点：标准 snake_case JSON 应解析为 SubAffix 列表。
     * <p>测试方法 {@code parseValidSnakeCaseJsonShouldReturnSubAffixes}：
     * <ul>
     *   <li>{@code assertEquals(2, result.size());}</li>
     *   <li>{@code assertEquals(101, result.get(0).getAffixId());}</li>
     *   <li>{@code assertEquals(2, result.get(0).getCount());}</li>
     *   <li>{@code assertEquals(3, result.get(0).getStep());}</li>
     *   <li>{@code assertEquals(202, result.get(1).getAffixId());}</li>
     * </ul>
     */
    @Test
    @DisplayName("标准 snake_case JSON 应解析为 SubAffix 列表")
    void parseValidSnakeCaseJsonShouldReturnSubAffixes() {
        String json = "[{\"affix_id\":101,\"count\":2,\"step\":3},{\"affix_id\":202,\"count\":1,\"step\":0}]";

        List<ItemSystemProto.SubAffix> result = parser.parseSubAffixes(json);

        assertEquals(2, result.size());
        log.info("snake_case 解析校验: inputSize=2, affix0Id={}, affix0Count={}, affix0Step={}, affix1Id={}, affix1Count={}",
                result.get(0).getAffixId(), result.get(0).getCount(), result.get(0).getStep(),
                result.get(1).getAffixId(), result.get(1).getCount());
        assertEquals(101, result.get(0).getAffixId());
        assertEquals(2, result.get(0).getCount());
        assertEquals(3, result.get(0).getStep());
        assertEquals(202, result.get(1).getAffixId());
    }

    /**
     * 验证点：camelCase affixId 与字符串数值应兼容解析。
     * <p>测试方法 {@code parseCamelCaseAndStringNumbersShouldWork}：
     * <ul>
     *   <li>{@code assertEquals(1, result.size());}</li>
     *   <li>{@code assertEquals(55, result.get(0).getAffixId());}</li>
     *   <li>{@code assertEquals(0, result.get(0).getCount());}</li>
     *   <li>{@code assertEquals(1, result.get(0).getStep());}</li>
     * </ul>
     */
    @Test
    @DisplayName("camelCase affixId 与字符串数值应兼容解析")
    void parseCamelCaseAndStringNumbersShouldWork() {
        String json = "[{\"affixId\":\"55\",\"count\":\"-2\",\"step\":\"1\"}]";

        List<ItemSystemProto.SubAffix> result = parser.parseSubAffixes(json);

        assertEquals(1, result.size());
        log.info("camelCase 兼容校验: affixId={}, count={}, step={}",
                result.get(0).getAffixId(), result.get(0).getCount(), result.get(0).getStep());
        assertEquals(55, result.get(0).getAffixId());
        assertEquals(0, result.get(0).getCount());
        assertEquals(1, result.get(0).getStep());
    }

    /**
     * 验证点：空输入或 null 应返回空列表。
     * <p>测试方法 {@code emptyOrNullInputShouldReturnEmptyList}：
     * <ul>
     *   <li>{@code assertTrue(nullResult.isEmpty());}</li>
     *   <li>{@code assertTrue(blankResult.isEmpty());}</li>
     * </ul>
     */
    @Test
    @DisplayName("空输入或 null 应返回空列表")
    void emptyOrNullInputShouldReturnEmptyList() {
        List<ItemSystemProto.SubAffix> nullResult = parser.parseSubAffixes(null);
        List<ItemSystemProto.SubAffix> blankResult = parser.parseSubAffixes("   ");

        log.info("空输入校验: nullSize={}, blankSize={}", nullResult.size(), blankResult.size());
        assertTrue(nullResult.isEmpty());
        assertTrue(blankResult.isEmpty());
    }

    /**
     * 验证点：非法 JSON 或非数组结构应返回空列表。
     * <p>测试方法 {@code invalidJsonOrNonArrayShouldReturnEmptyList}：
     * <ul>
     *   <li>{@code assertTrue(badJson.isEmpty());}</li>
     *   <li>{@code assertTrue(objectJson.isEmpty());}</li>
     * </ul>
     */
    @Test
    @DisplayName("非法 JSON 或非数组结构应返回空列表")
    void invalidJsonOrNonArrayShouldReturnEmptyList() {
        List<ItemSystemProto.SubAffix> badJson = parser.parseSubAffixes("not-json");
        List<ItemSystemProto.SubAffix> objectJson = parser.parseSubAffixes("{\"affix_id\":1}");

        log.info("非法结构校验: badJsonSize={}, objectJsonSize={}", badJson.size(), objectJson.size());
        assertTrue(badJson.isEmpty());
        assertTrue(objectJson.isEmpty());
    }

    /**
     * 验证点：无效 affixId 条目应被跳过。
     * <p>测试方法 {@code invalidAffixIdShouldBeSkipped}：
     * <ul>
     *   <li>{@code assertEquals(1, result.size());}</li>
     *   <li>{@code assertEquals(9, result.get(0).getAffixId());}</li>
     *   <li>{@code assertEquals(3, result.get(0).getCount());}</li>
     * </ul>
     */
    @Test
    @DisplayName("无效 affixId 条目应被跳过")
    void invalidAffixIdShouldBeSkipped() {
        String json = "[{\"affix_id\":0,\"count\":1},{\"affix_id\":-5,\"count\":2},{\"affix_id\":9,\"count\":3}]";

        List<ItemSystemProto.SubAffix> result = parser.parseSubAffixes(json);

        assertEquals(1, result.size());
        log.info("无效 affixId 过滤校验: rawCount=3, parsedCount={}, keptAffixId={}, keptCount={}",
                result.size(), result.get(0).getAffixId(), result.get(0).getCount());
        assertEquals(9, result.get(0).getAffixId());
        assertEquals(3, result.get(0).getCount());
    }
}
