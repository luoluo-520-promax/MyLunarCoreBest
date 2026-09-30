package cn.itcast.demo.mylunarcore.common;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.model.ActivityConfig;
import cn.itcast.demo.mylunarcore.net.mapper.ActivityProtoMapper;
import cn.itcast.demo.mylunarcore.protocol.ActivitySystemProto;
import cn.itcast.demo.mylunarcore.protocol.VersionUpdateProto;
import cn.itcast.demo.mylunarcore.repo.GameDataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 端到端验证：活动/版本配置导入后能否被识别并投入运行时使用。
 */
@DisplayName("更新机制端到端验证")
class UpdateMechanismIntegrationTest {

    @TempDir
    Path tempDir;

    private ConfigFileService configFileService;
    private ActivityConfigService activityConfigService;
    private HotfixDataService hotfixDataService;
    private ActivityNettyService activityNettyService;
    private VersionNettyService versionNettyService;
    private ActivityImportService activityImportService;

    @BeforeEach
    void setUp() {
        LunarCoreProperties properties = new LunarCoreProperties();
        properties.setDataDir(tempDir.toString());
        properties.setActivityConfigsResource("file:" + tempDir.resolve("ActivityConfigs.json"));
        properties.setActivityDetailDir("activities");
        properties.getHotfix().setResource("file:" + tempDir.resolve("hotfix.json"));

        DefaultResourceLoader resourceLoader = new DefaultResourceLoader();
        configFileService = new ConfigFileService(properties);
        activityConfigService = new ActivityConfigService(properties, resourceLoader, configFileService,
                new cn.itcast.demo.mylunarcore.ops.ServerClockService());
        hotfixDataService = new HotfixDataService(properties, resourceLoader);
        ActivityProtoMapper activityProtoMapper = new ActivityProtoMapper();
        VersionUpdateMapper versionUpdateMapper = new VersionUpdateMapper();
        var clock = new cn.itcast.demo.mylunarcore.ops.ServerClockService();
        var visibility = new cn.itcast.demo.mylunarcore.activity.ActivityVisibilityService(clock, properties);
        activityNettyService = new ActivityNettyService(
                new ActivityQueryService(activityConfigService, visibility, clock), activityProtoMapper);
        versionNettyService = new VersionNettyService(hotfixDataService, versionUpdateMapper);
        activityImportService = new ActivityImportService(configFileService, mock(GameDataRepository.class));
    }

    @Test
    @DisplayName("JSON 导入活动全字段后应被识别并用于查询与开放判断")
    void jsonActivityImportShouldBeRecognizedAndUsed() throws Exception {
        ActivityConfig config = buildFullActivityConfig();
        activityImportService.importUnified(config);
        activityConfigService.reload();

        ActivityConfig loaded = activityConfigService.findById(5000701).orElseThrow();
        assertAllActivityFields(loaded);

        assertTrue(activityConfigService.isActivityActive(5000701, 25));
        assertFalse(activityConfigService.isActivityActive(5000701, 10));

        ActivitySystemProto.GetActivityInfoScRsp rsp = activityNettyService.handleGetActivityInfo(5000701);
        assertEquals(0, rsp.getRetcode());
        assertEquals(1, rsp.getActivitiesCount());
        ActivitySystemProto.ActivityConfigEntry proto = rsp.getActivities(0);
        assertEquals("seasonal", proto.getActivityType());
        assertEquals("参与夏日庆典，收集代币兑换限定奖励。", proto.getDescription());
        assertEquals(1, proto.getStagesCount());
        assertEquals(7001, proto.getShopId());
        assertEquals(1, proto.getShopProductsCount());
        assertEquals("ui/activity/summer_banner.png", proto.getUiResources().getBannerImage());
        assertEquals("限时 UP", proto.getDisplayText().getBannerText());
        assertEquals("milestone", proto.getRewardMethod());
        assertEquals(1, proto.getPointsTokensCount());
        assertEquals(5, proto.getCostAndLimits().getDailyLimit());
    }

    @Test
    @DisplayName("JSON 导入版本全字段后应被识别并用于版本查询推送")
    void jsonVersionImportShouldBeRecognizedAndUsed() throws Exception {
        String hotfixJson = Files.readString(Path.of("data/hotfix.json"));
        configFileService.writeText("hotfix.json", hotfixJson);
        assertTrue(hotfixDataService.reload());

        HotfixData data = hotfixDataService.current();
        assertAllVersionFields(data);

        VersionUpdateProto.GetVersionInfoScRsp rsp = versionNettyService.handleGetVersionInfo();
        assertEquals(0, rsp.getRetcode());
        VersionUpdateProto.VersionUpdateScNotify version = rsp.getVersion();
        assertEquals("1.1.0", version.getHotfixVersion());
        assertEquals("1.1.0", version.getGameResourcePack().getVersion());
        assertEquals(2, version.getAudioLanguagePacksCount());
        assertEquals("zh-CN", version.getAudioLanguagePacks(0).getLocale());
        assertEquals(2, version.getVersionInfo().getFilesCount());
        assertEquals(2, version.getDeletedFilesCount());
        assertEquals(2, version.getConfigFilesCount());
        assertEquals("sha256", version.getPatchTool().getAlgorithm());
        assertTrue(version.getPatchTool().getVerifyToolUrl().contains("patch_verify"));
    }

    @Test
    @DisplayName("ConfigImportService 应支持 JSON 文件批量导入版本配置")
    void configImportServiceShouldImportHotfixJson() throws Exception {
        String hotfixJson = Files.readString(Path.of("data/hotfix.json"));
        ConfigImportService importService = new ConfigImportService(
                configFileService,
                activityImportService,
                mock(GameDataRepository.class),
                mock(HotReloadCoordinator.class),
                new ConfigPublishAuditService(),
                mock(ConfigReleaseService.class));

        Map<String, Object> result = importService.importJsonFiles(
                Map.of("hotfix.json", hotfixJson), false);

        assertTrue((Boolean) result.get("ok"));
        assertTrue(hotfixDataService.reload());
        assertEquals("1.1.0", hotfixDataService.current().getGameResourcePack().getVersion());
    }

    private static ActivityConfig buildFullActivityConfig() {
        ActivityConfig config = new ActivityConfig();
        config.setActivityId(5000701);
        config.setName("夏日庆典");
        config.setActivityType("seasonal");
        config.setModuleId(50007);
        config.setBeginTime(1_000L);
        config.setEndTime(9_999_999_999L);
        config.setUnlockLevel(20);

        ActivityConfig.ActivityCondition condition = new ActivityConfig.ActivityCondition();
        condition.setType("level");
        condition.setIntValue(20);
        config.setConditions(List.of(condition));

        config.setDescription("参与夏日庆典，收集代币兑换限定奖励。");
        config.setGameplay("完成每日任务与关卡挑战，获取夏日代币并在活动商店兑换道具。");
        config.setRules("每日参与次数有限；代币活动结束清零。");

        ActivityConfig.ActivityStage stage = new ActivityConfig.ActivityStage();
        stage.setStageId(1);
        stage.setName("开幕阶段");
        stage.setUnlockScore(0);
        config.setStages(List.of(stage));

        ActivityConfig.ActivityCostLimit limit = new ActivityConfig.ActivityCostLimit();
        limit.setCostItemId(90001);
        limit.setCostCount(10);
        limit.setDailyLimit(5);
        limit.setTotalLimit(50);
        limit.setCooldownSeconds(3600);
        config.setCostAndLimits(limit);

        ActivityConfig.ActivityReward reward = new ActivityConfig.ActivityReward();
        reward.setItemId(90001);
        reward.setCount(100);
        reward.setType("token");
        reward.setGrantTiming("immediate");
        config.setRewards(List.of(reward));
        config.setRewardMethod("milestone");

        ActivityConfig.ActivityPointToken token = new ActivityConfig.ActivityPointToken();
        token.setTokenId(90001);
        token.setName("夏日代币");
        token.setMaxStack(9999);
        token.setIconPath("ui/activity/summer_token.png");
        config.setPointsTokens(List.of(token));

        config.setShopId(7001);
        ActivityConfig.ShopProduct product = new ActivityConfig.ShopProduct();
        product.setProductId(1);
        product.setItemId(90002);
        product.setPrice(200);
        product.setCurrencyId(90001);
        product.setDailyLimit(1);
        config.setShopProducts(List.of(product));

        ActivityConfig.ActivityUiResources ui = new ActivityConfig.ActivityUiResources();
        ui.setBannerImage("ui/activity/summer_banner.png");
        ui.setThemeColor("#FF8C42");
        config.setUiResources(ui);

        ActivityConfig.ActivityDisplayText text = new ActivityConfig.ActivityDisplayText();
        text.setTitle("夏日庆典");
        text.setBannerText("限时 UP");
        config.setDisplayText(text);

        ActivityConfig.ActivityItemRef item = new ActivityConfig.ActivityItemRef();
        item.setId(90001);
        item.setName("夏日代币");
        item.setStack(999);
        config.setItems(List.of(item));
        return config;
    }

    private static void assertAllActivityFields(ActivityConfig config) {
        assertEquals(5000701, config.getActivityId());
        assertEquals("seasonal", config.getActivityType());
        assertEquals("夏日庆典", config.getName());
        assertTrue(config.getBeginTime() > 0);
        assertTrue(config.getEndTime() > config.getBeginTime());
        assertEquals(1, config.getConditions().size());
        assertFalse(config.getDescription().isBlank());
        assertFalse(config.getGameplay().isBlank());
        assertFalse(config.getRules().isBlank());
        assertEquals(1, config.getStages().size());
        assertEquals(5, config.getCostAndLimits().getDailyLimit());
        assertEquals(1, config.getRewards().size());
        assertEquals("milestone", config.getRewardMethod());
        assertEquals(1, config.getPointsTokens().size());
        assertEquals(7001, config.getShopId());
        assertEquals(1, config.getShopProducts().size());
        assertNotNull(config.getUiResources());
        assertFalse(config.getUiResources().getBannerImage().isBlank());
        assertNotNull(config.getDisplayText());
        assertFalse(config.getDisplayText().getBannerText().isBlank());
    }

    private static void assertAllVersionFields(HotfixData data) {
        assertEquals("1.1.0", data.getHotfixVersion());
        assertNotNull(data.getGameResourcePack());
        assertEquals("1.1.0", data.getGameResourcePack().getVersion());
        assertFalse(data.getGameResourcePack().getHash().isBlank());
        assertEquals(2, data.getAudioLanguagePacks().size());
        assertNotNull(data.getVersionInfo());
        assertEquals(2, data.getVersionInfo().getFiles().size());
        assertEquals(2, data.getDeletedFiles().size());
        assertEquals(2, data.getConfigFiles().size());
        assertNotNull(data.getPatchTool());
        assertEquals("sha256", data.getPatchTool().getAlgorithm());
        assertFalse(data.getPatchTool().getVerifyToolUrl().isBlank());
    }
}
