package cn.itcast.demo.mylunarcore.common;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ConfigGrayRelease 灰度判定。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code ConfigGrayReleaseTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("ConfigGrayRelease 灰度判定")
class ConfigGrayReleaseTest {

    /**
     * 验证点：关闭灰度时全员视为生效。
     * <p>测试方法 {@code disabledMeansEveryone}：
     * <ul>
     *   <li>{@code assertTrue(gray.inGray(10001L, "s1"));}</li>
     * </ul>
     */
    @Test
    @DisplayName("关闭灰度时全员视为生效")
    void disabledMeansEveryone() {
        LunarCoreProperties props = new LunarCoreProperties();
        props.getConfigGray().setEnabled(false);
        ConfigGrayRelease gray = new ConfigGrayRelease(props);
        assertTrue(gray.inGray(10001L, "s1"));
    }

    /**
     * 验证点：按 UID 尾号命中。
     * <p>测试方法 {@code uidTailShouldMatch}：
     * <ul>
     *   <li>{@code assertTrue(gray.inGray(10001L, "s1"));}</li>
     *   <li>{@code assertFalse(gray.inGray(10002L, "s1"));}</li>
     * </ul>
     */
    @Test
    @DisplayName("按 UID 尾号命中")
    void uidTailShouldMatch() {
        LunarCoreProperties props = new LunarCoreProperties();
        props.getConfigGray().setEnabled(true);
        props.getConfigGray().setUidTails("1,3");
        ConfigGrayRelease gray = new ConfigGrayRelease(props);
        assertTrue(gray.inGray(10001L, "s1"));
        assertFalse(gray.inGray(10002L, "s1"));
    }
}
