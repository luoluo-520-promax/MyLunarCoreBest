package cn.itcast.demo.mylunarcore.rogue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Rogue 程序化地图生成。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code RogueMapGeneratorTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("Rogue 程序化地图生成")
class RogueMapGeneratorTest {

    private RogueMapGenerator gen;

    @BeforeEach
    void setUp() {
        gen = new RogueMapGenerator(new ObjectMapper());
        gen.load();
    }

    /**
     * 验证点：相同种子应可复现。
     * <p>测试方法 {@code sameSeedReproducible}：
     * <ul>
     *   <li>{@code assertEquals(a.layers().size(), b.layers().size());}</li>
     *   <li>{@code assertEquals(a.layers().get(0).get(0).roomId(), b.layers().get(0).get(0).roomId());}</li>
     *   <li>{@code assertEquals(a.layers().get(0).get(0).type(), b.layers().get(0).get(0).type());}</li>
     * </ul>
     */
    @Test
    @DisplayName("相同种子应可复现")
    void sameSeedReproducible() {
        RogueMapGenerator.GeneratedMap a = gen.generate(42L, 4, 4);
        RogueMapGenerator.GeneratedMap b = gen.generate(42L, 4, 4);
        assertEquals(a.layers().size(), b.layers().size());
        assertEquals(a.layers().get(0).get(0).roomId(), b.layers().get(0).get(0).roomId());
        assertEquals(a.layers().get(0).get(0).type(), b.layers().get(0).get(0).type());
    }

    /**
     * 验证点：非末层房间应有后继。
     * <p>测试方法 {@code nonFinalLayerHasNext}：
     * <ul>
     *   <li>{@code assertFalse(map.layers().get(0).get(0).nextRoomIds().isEmpty());}</li>
     * </ul>
     */
    @Test
    @DisplayName("非末层房间应有后继")
    void nonFinalLayerHasNext() {
        RogueMapGenerator.GeneratedMap map = gen.generate(7L, 3, 4);
        assertFalse(map.layers().get(0).get(0).nextRoomIds().isEmpty());
    }
}
