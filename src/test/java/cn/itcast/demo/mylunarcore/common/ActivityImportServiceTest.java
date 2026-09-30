package cn.itcast.demo.mylunarcore.common;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.model.ActivityConfig;
import cn.itcast.demo.mylunarcore.repo.GameDataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * ActivityImportService 统一活动导入测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code ActivityImportServiceTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("ActivityImportService 统一活动导入测试")
class ActivityImportServiceTest {

    @TempDir
    Path tempDir;

    private ActivityImportService service;
    private GameDataRepository gameDataRepository;

    @BeforeEach
    void setUp() {
        LunarCoreProperties properties = new LunarCoreProperties();
        properties.setDataDir(tempDir.toString());
        ConfigFileService configFileService = new ConfigFileService(properties);
        gameDataRepository = mock(GameDataRepository.class);
        service = new ActivityImportService(configFileService, gameDataRepository);
    }

    /**
     * 验证点：importUnified 应拆分写入排期、卡池、道具、详情与 game_data。
     * <p>测试方法 {@code importUnifiedShouldSplitToAllTargets}：
     * <ul>
     *   <li>{@code assertEquals(5000701, result.activityId());}</li>
     *   <li>{@code assertEquals("activities/activity.5000701.json", result.detailFile());}</li>
     *   <li>{@code assertEquals("activity.5000701", result.gameDataKey());}</li>
     *   <li>{@code verify(gameDataRepository).upsert(eq("activity.5000701"), anyString());}</li>
     *   <li>{@code assertEquals(1, files.readScheduleEntries().size());}</li>
     *   <li>{@code assertEquals(1, files.readBannerEntries().size());}</li>
     * </ul>
     */
    @Test
    @DisplayName("importUnified 应拆分写入排期、卡池、道具、详情与 game_data")
    void importUnifiedShouldSplitToAllTargets() throws Exception {
        ActivityConfig config = new ActivityConfig();
        config.setActivityId(5000701);
        config.setName("夏日庆典");
        config.setActivityType("seasonal");
        config.setModuleId(50007);
        config.setBeginTime(1_750_000_000L);
        config.setEndTime(1_752_592_000L);
        config.setUnlockLevel(20);

        ActivityConfig.ActivityBannerRef banner = new ActivityConfig.ActivityBannerRef();
        banner.setId(9001);
        banner.setGachaType("AvatarUp");
        banner.setRateUpItems5(List.of(1102));
        banner.setRateUpItems4(List.of(1105));
        config.setBanners(List.of(banner));

        ActivityConfig.ActivityItemRef item = new ActivityConfig.ActivityItemRef();
        item.setId(90001);
        item.setName("夏日代币");
        item.setStack(999);
        config.setItems(List.of(item));

        ActivityImportService.ImportResult result = service.importUnified(config);

        assertEquals(5000701, result.activityId());
        assertEquals("activities/activity.5000701.json", result.detailFile());
        assertEquals("activity.5000701", result.gameDataKey());
        verify(gameDataRepository).upsert(eq("activity.5000701"), anyString());

        LunarCoreProperties properties = new LunarCoreProperties();
        properties.setDataDir(tempDir.toString());
        ConfigFileService files = new ConfigFileService(properties);
        assertEquals(1, files.readScheduleEntries().size());
        assertEquals(1, files.readBannerEntries().size());
        assertTrue(files.readCsv("items_config.csv").size() >= 2);
        assertEquals(1, files.readActivityConfigs().size());
    }
}
