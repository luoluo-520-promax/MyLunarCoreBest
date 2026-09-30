package cn.itcast.demo.mylunarcore.gacha;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GachaDrawEngine 抽卡概率引擎测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code GachaDrawEngineTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("GachaDrawEngine 抽卡概率引擎测试")
class GachaDrawEngineTest {

    /**
     * 验证点：90 抽硬保底应必出 5 星且 pity5 归零。
     * <p>测试方法 {@code hardPity90ShouldForceFiveStar}：
     * <ul>
     *   <li>{@code assertEquals(10086, result.itemId());}</li>
     *   <li>{@code assertEquals(0, result.pity5After());}</li>
     *   <li>{@code assertTrue(result.pity4After() >= 1);}</li>
     * </ul>
     */
    @Test
    @DisplayName("90 抽硬保底应必出 5 星且 pity5 归零")
    void hardPity90ShouldForceFiveStar() {
        GachaDrawEngine engine = new GachaDrawEngine();
        GachaBannerConfig banner = new GachaBannerConfig();
        banner.setRateUpItems5(List.of(10086));
        banner.setRateUpItems4(List.of(20001));

        GachaDrawEngine.DrawResult result = engine.doOneDraw(
                GachaBannerType.NORMAL, banner, null, 89, 0, 0);

        assertEquals(10086, result.itemId());
        assertEquals(0, result.pity5After());
        assertTrue(result.pity4After() >= 1);
    }
}
