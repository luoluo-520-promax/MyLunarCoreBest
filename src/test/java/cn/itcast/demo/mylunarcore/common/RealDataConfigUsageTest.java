package cn.itcast.demo.mylunarcore.common;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.model.ActivityConfig;
import cn.itcast.demo.mylunarcore.net.mapper.ActivityProtoMapper;
import cn.itcast.demo.mylunarcore.protocol.ActivitySystemProto;
import cn.itcast.demo.mylunarcore.protocol.VersionUpdateProto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 使用项目 data/ 目录真实配置验证导入后可投入运行。
 */
@DisplayName("真实 data/ 配置投入使用验证")
class RealDataConfigUsageTest {

    private ActivityConfigService activityConfigService;
    private HotfixDataService hotfixDataService;
    private ActivityNettyService activityNettyService;
    private VersionNettyService versionNettyService;

    @BeforeEach
    void setUp() {
        Path projectRoot = Path.of("").toAbsolutePath();
        LunarCoreProperties properties = new LunarCoreProperties();
        properties.setDataDir(projectRoot.resolve("data").toString());
        properties.setActivityConfigsResource("file:" + projectRoot.resolve("data/ActivityConfigs.json"));
        properties.setActivityDetailDir("activities");
        properties.getHotfix().setResource("file:" + projectRoot.resolve("data/hotfix.json"));

        DefaultResourceLoader resourceLoader = new DefaultResourceLoader();
        ConfigFileService configFileService = new ConfigFileService(properties);
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

        activityConfigService.reload();
        hotfixDataService.reload();
    }

    @Test
    @DisplayName("data/ 活动配置全字段应可加载并用于开放判断与协议查询")
    void realActivityConfigShouldBeUsed() {
        ActivityConfig config = activityConfigService.findById(5000701).orElseThrow();
        assertEquals("seasonal", config.getActivityType());
        assertFalse(config.getDescription().isBlank());
        assertFalse(config.getGameplay().isBlank());
        assertFalse(config.getRules().isBlank());
        assertFalse(config.getStages().isEmpty());
        assertEquals(5, config.getCostAndLimits().getDailyLimit());
        assertEquals("milestone", config.getRewardMethod());
        assertFalse(config.getPointsTokens().isEmpty());
        assertEquals(7001, config.getShopId());
        assertFalse(config.getShopProducts().isEmpty());
        assertFalse(config.getUiResources().getBannerImage().isBlank());
        assertFalse(config.getDisplayText().getBannerText().isBlank());

        assertTrue(activityConfigService.isActivityActive(5000701, 25));
        assertFalse(activityConfigService.isActivityActive(5000701, 10));

        ActivitySystemProto.GetActivityInfoScRsp rsp = activityNettyService.handleGetActivityInfo(5000701);
        assertEquals(1, rsp.getActivitiesCount());
        assertEquals("夏日庆典", rsp.getActivities(0).getDisplayText().getTitle());
    }

    @Test
    @DisplayName("data/hotfix.json 版本全字段应可加载并用于版本协议查询")
    void realHotfixConfigShouldBeUsed() {
        HotfixData data = hotfixDataService.current();
        assertEquals("1.1.0", data.getHotfixVersion());
        assertEquals("1.1.0", data.getGameResourcePack().getVersion());
        assertEquals(2, data.getAudioLanguagePacks().size());
        assertEquals(2, data.getVersionInfo().getFiles().size());
        assertEquals(2, data.getDeletedFiles().size());
        assertEquals(2, data.getConfigFiles().size());
        assertEquals("sha256", data.getPatchTool().getAlgorithm());

        VersionUpdateProto.GetVersionInfoScRsp rsp = versionNettyService.handleGetVersionInfo();
        assertEquals(0, rsp.getRetcode());
        assertEquals("1.1.0", rsp.getVersion().getHotfixVersion());
        assertEquals(2, rsp.getVersion().getAudioLanguagePacksCount());
        assertTrue(rsp.getVersion().getPatchTool().getVerifyToolUrl().contains("patch_verify"));
    }
}
