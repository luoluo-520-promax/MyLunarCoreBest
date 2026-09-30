package cn.itcast.demo.mylunarcore.common;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ConfigFileService 原子写入与回滚测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code ConfigFileServiceAtomicWriteTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("ConfigFileService 原子写入与回滚测试")
class ConfigFileServiceAtomicWriteTest {

    @TempDir
    Path tempDir;

    /**
     * 验证点：原子写入应生成备份并可回滚。
     * <p>测试方法 {@code atomicWriteShouldBackupAndRollback}：
     * <ul>
     *   <li>{@code assertEquals("{\"v\":2}", Files.readString(tempDir.resolve("hotfix.json")));}</li>
     *   <li>{@code assertTrue(Files.isRegularFile(tempDir.resolve("hotfix.json.bak")));}</li>
     *   <li>{@code assertTrue(service.rollbackFromBackup("hotfix.json"));}</li>
     *   <li>{@code assertEquals("{\"v\":1}", Files.readString(tempDir.resolve("hotfix.json")));}</li>
     * </ul>
     */
    @Test
    @DisplayName("原子写入应生成备份并可回滚")
    void atomicWriteShouldBackupAndRollback() throws Exception {
        LunarCoreProperties properties = new LunarCoreProperties();
        properties.setDataDir(tempDir.toString());
        ConfigFileService service = new ConfigFileService(properties);

        service.writeTextAtomic("hotfix.json", "{\"v\":1}", true);
        service.writeTextAtomic("hotfix.json", "{\"v\":2}", true);

        assertEquals("{\"v\":2}", Files.readString(tempDir.resolve("hotfix.json")));
        assertTrue(Files.isRegularFile(tempDir.resolve("hotfix.json.bak")));
        assertTrue(service.rollbackFromBackup("hotfix.json"));
        assertEquals("{\"v\":1}", Files.readString(tempDir.resolve("hotfix.json")));
    }
}
