package cn.itcast.demo.mylunarcore.scene;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AoiGrid 九宫格 AOI 测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code AoiGridTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("AoiGrid 九宫格 AOI 测试")
class AoiGridTest {

    /**
     * 验证点：相邻格子玩家应被 AOI 感知。
     * <p>测试方法 {@code nearbyShouldIncludeAdjacentPlayers}：
     * <ul>
     *   <li>{@code assertTrue(nearby.contains(2L));}</li>
     *   <li>{@code assertFalse(nearby.contains(3L));}</li>
     * </ul>
     */
    @Test
    @DisplayName("相邻格子玩家应被 AOI 感知")
    void nearbyShouldIncludeAdjacentPlayers() {
        AoiGrid grid = new AoiGrid(10f);
        grid.update(1, 0, 0);
        grid.update(2, 12, 0);
        grid.update(3, 100, 100);

        Set<Long> nearby = grid.nearby(1, 0, 0);
        assertTrue(nearby.contains(2L));
        assertFalse(nearby.contains(3L));
    }
}
