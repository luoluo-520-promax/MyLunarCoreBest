package cn.itcast.demo.mylunarcore.common;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.model.ActivityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ActivityConfigService 统一活动配置服务测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code ActivityConfigServiceTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("ActivityConfigService 统一活动配置服务测试")
class ActivityConfigServiceTest {

    @TempDir
    Path tempDir;

    private ActivityConfigService service;

    @BeforeEach
    void setUp() throws Exception {
        LunarCoreProperties properties = new LunarCoreProperties();
        properties.setDataDir(tempDir.toString());
        properties.setActivityDetailDir("activities");
        properties.setActivityConfigsResource("file:" + tempDir.resolve("ActivityConfigs.json"));

        ConfigFileService configFileService = new ConfigFileService(properties);
        ActivityConfig config = new ActivityConfig();
        config.setActivityId(5000701);
        config.setName("夏日庆典");
        config.setModuleId(50007);
        config.setBeginTime(1_000L);
        config.setEndTime(9_999_999_999L);
        config.setUnlockLevel(20);
        configFileService.writeJson("ActivityConfigs.json", java.util.List.of(config));

        service = new ActivityConfigService(properties, new DefaultResourceLoader(), configFileService,
                new cn.itcast.demo.mylunarcore.ops.ServerClockService());
        service.reload();
    }

    /**
     * 验证点：reload 应加载 ActivityConfigs.json。
     * <p>测试方法 {@code reloadShouldLoadConfigs}：
     * <ul>
     *   <li>{@code assertEquals(1, service.snapshot().size());}</li>
     *   <li>{@code assertTrue(service.findById(5000701).isPresent());}</li>
     * </ul>
     */
    @Test
    @DisplayName("reload 应加载 ActivityConfigs.json")
    void reloadShouldLoadConfigs() {
        assertEquals(1, service.snapshot().size());
        assertTrue(service.findById(5000701).isPresent());
    }

    /**
     * 验证点：isActivityActive 应校验解锁等级。
     * <p>测试方法 {@code isActivityActiveShouldCheckUnlockLevel}：
     * <ul>
     *   <li>{@code assertTrue(service.isActivityActive(5000701, 20));}</li>
     *   <li>{@code assertFalse(service.isActivityActive(5000701, 10));}</li>
     * </ul>
     */
    @Test
    @DisplayName("isActivityActive 应校验解锁等级")
    void isActivityActiveShouldCheckUnlockLevel() {
        assertTrue(service.isActivityActive(5000701, 20));
        assertFalse(service.isActivityActive(5000701, 10));
    }
}
