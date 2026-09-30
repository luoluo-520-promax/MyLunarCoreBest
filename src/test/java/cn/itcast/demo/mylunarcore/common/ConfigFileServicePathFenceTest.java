package cn.itcast.demo.mylunarcore.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ConfigFileService 路径围栏。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code ConfigFileServicePathFenceTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("ConfigFileService 路径围栏")
class ConfigFileServicePathFenceTest {

    /**
     * 验证点：../ 逃逸应被拒绝。
     * <p>测试方法 {@code pathEscapeShouldBeRejected}：
     * <ul>
     *   <li>{@code assertThrows(SecurityException.class, () -> ConfigFileService.assertUnderDataRoot(root, escaped));}</li>
     * </ul>
     */
    @Test
    @DisplayName("../ 逃逸应被拒绝")
    void pathEscapeShouldBeRejected() {
        Path root = Path.of("data").toAbsolutePath().normalize();
        Path escaped = root.resolve("../outside.json").normalize();
        assertThrows(SecurityException.class, () -> ConfigFileService.assertUnderDataRoot(root, escaped));
    }

    /**
     * 验证点：dataRoot 内相对路径应通过。
     * <p>测试方法 {@code pathInsideRootShouldPass}：
     * <ul>
     *   <li>{@code assertDoesNotThrow(() -> ConfigFileService.assertUnderDataRoot(root, ok));}</li>
     * </ul>
     */
    @Test
    @DisplayName("dataRoot 内相对路径应通过")
    void pathInsideRootShouldPass() {
        Path root = Path.of("data").toAbsolutePath().normalize();
        Path ok = root.resolve("Banners.json").normalize();
        assertDoesNotThrow(() -> ConfigFileService.assertUnderDataRoot(root, ok));
    }
}
