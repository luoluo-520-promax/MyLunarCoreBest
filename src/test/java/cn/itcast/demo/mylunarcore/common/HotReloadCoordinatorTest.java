package cn.itcast.demo.mylunarcore.common;

import cn.itcast.demo.mylunarcore.assist.AssistAnswerCache;
import cn.itcast.demo.mylunarcore.assist.AssistFeatureContent;
import cn.itcast.demo.mylunarcore.assist.AssistFeatureContentRepository;
import cn.itcast.demo.mylunarcore.assist.AssistSafetyRulesConfig;
import cn.itcast.demo.mylunarcore.assist.AssistSafetyRulesRepository;
import cn.itcast.demo.mylunarcore.assist.CoachTipsConfig;
import cn.itcast.demo.mylunarcore.assist.CoachTipsRepository;
import cn.itcast.demo.mylunarcore.assist.ExternalGuideCatalogConfig;
import cn.itcast.demo.mylunarcore.assist.ExternalGuideCatalogRepository;
import cn.itcast.demo.mylunarcore.assist.GuidePackConfig;
import cn.itcast.demo.mylunarcore.assist.GuidePackRepository;
import cn.itcast.demo.mylunarcore.assist.RagKnowledgeService;
import cn.itcast.demo.mylunarcore.battle.EncounterConfig;
import cn.itcast.demo.mylunarcore.battle.EncounterConfigRepository;
import cn.itcast.demo.mylunarcore.economy.ShopConfigRepository;
import cn.itcast.demo.mylunarcore.gacha.GachaConfigService;
import cn.itcast.demo.mylunarcore.model.ActivityConfig;
import cn.itcast.demo.mylunarcore.skin.SkinConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * HotReloadCoordinator 热重载协调器测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code HotReloadCoordinatorTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("HotReloadCoordinator 热重载协调器测试")
class HotReloadCoordinatorTest {

    private static final Logger log = LoggerFactory.getLogger(HotReloadCoordinatorTest.class);

    private HotfixDataService hotfixDataService;
    private ActivityScheduleService activityScheduleService;
    private ActivityConfigService activityConfigService;
    private GachaConfigService gachaConfigService;
    private StaticResourceRegistry staticResourceRegistry;
    private UpdateNotifyBroadcaster updateNotifyBroadcaster;
    private ConfigPublishAuditService auditService;
    private CoachTipsRepository coachTipsRepository;
    private GuidePackRepository guidePackRepository;
    private ExternalGuideCatalogRepository externalGuideCatalogRepository;
    private RagKnowledgeService ragKnowledgeService;
    private AssistSafetyRulesRepository assistSafetyRulesRepository;
    private AssistAnswerCache assistAnswerCache;
    private AssistFeatureContentRepository assistFeatureContentRepository;
    private EncounterConfigRepository encounterConfigRepository;
    private ShopConfigRepository shopConfigRepository;
    private SkinConfigRepository skinConfigRepository;
    private HotReloadCoordinator coordinator;

    @BeforeEach
    void setUp() {
        hotfixDataService = mock(HotfixDataService.class);
        activityScheduleService = mock(ActivityScheduleService.class);
        activityConfigService = mock(ActivityConfigService.class);
        gachaConfigService = mock(GachaConfigService.class);
        staticResourceRegistry = mock(StaticResourceRegistry.class);
        updateNotifyBroadcaster = mock(UpdateNotifyBroadcaster.class);
        auditService = mock(ConfigPublishAuditService.class);
        coachTipsRepository = mock(CoachTipsRepository.class);
        guidePackRepository = mock(GuidePackRepository.class);
        externalGuideCatalogRepository = mock(ExternalGuideCatalogRepository.class);
        ragKnowledgeService = mock(RagKnowledgeService.class);
        assistSafetyRulesRepository = mock(AssistSafetyRulesRepository.class);
        assistAnswerCache = mock(AssistAnswerCache.class);
        assistFeatureContentRepository = mock(AssistFeatureContentRepository.class);
        encounterConfigRepository = mock(EncounterConfigRepository.class);
        shopConfigRepository = mock(ShopConfigRepository.class);
        skinConfigRepository = mock(SkinConfigRepository.class);
        when(hotfixDataService.current()).thenReturn(HotfixData.empty());
        when(activityScheduleService.getWindows()).thenReturn(List.of());
        when(activityConfigService.snapshot()).thenReturn(Map.of());
        when(gachaConfigService.snapshot()).thenReturn(Map.of());
        when(coachTipsRepository.current()).thenReturn(CoachTipsConfig.empty());
        when(guidePackRepository.current()).thenReturn(GuidePackConfig.empty());
        when(externalGuideCatalogRepository.current()).thenReturn(ExternalGuideCatalogConfig.empty());
        when(assistSafetyRulesRepository.current()).thenReturn(AssistSafetyRulesConfig.defaults());
        when(assistFeatureContentRepository.current()).thenReturn(AssistFeatureContent.empty());
        when(encounterConfigRepository.current()).thenReturn(EncounterConfig.empty());
        when(shopConfigRepository.snapshot()).thenReturn(Map.of());
        when(skinConfigRepository.current()).thenReturn(SkinConfigRepository.SkinConfigsFile.empty());
        when(hotfixDataService.reload()).thenReturn(true);
        when(gachaConfigService.reload()).thenReturn(true);
        when(coachTipsRepository.reload()).thenReturn(true);
        when(guidePackRepository.reload()).thenReturn(true);
        when(externalGuideCatalogRepository.reload()).thenReturn(true);
        when(assistSafetyRulesRepository.reload()).thenReturn(true);
        when(assistFeatureContentRepository.reload()).thenReturn(true);
        when(encounterConfigRepository.reload()).thenReturn(true);
        when(shopConfigRepository.reload()).thenReturn(true);
        when(skinConfigRepository.reload()).thenReturn(true);
        when(updateNotifyBroadcaster.broadcastAll()).thenReturn(1);
        when(auditService.record(anyString(), anyString(), anyList(), anyBoolean(), anyBoolean(), anyString()))
                .thenReturn(new ConfigPublishAuditService.PublishRecord(
                        1L, "ops", "reloadAll", List.of(), false, true, "ok", java.time.Instant.now()));
        ConfigGrayRelease gray = mock(ConfigGrayRelease.class);
        when(gray.current()).thenReturn(ConfigGrayRelease.GrayPolicy.disabled());
        coordinator = new HotReloadCoordinator(
                hotfixDataService, activityScheduleService, activityConfigService,
                gachaConfigService, staticResourceRegistry, updateNotifyBroadcaster, auditService,
                coachTipsRepository, guidePackRepository, externalGuideCatalogRepository,
                ragKnowledgeService, assistSafetyRulesRepository, assistAnswerCache,
                assistFeatureContentRepository, encounterConfigRepository, shopConfigRepository,
                skinConfigRepository, gray, new ConfigCacheVersion(),
                mock(cn.itcast.demo.mylunarcore.assist.AssistPersonaRepository.class),
                null);
        log.info("热重载协调器初始化: coordinatorClass={}", coordinator.getClass().getSimpleName());
    }

    /**
     * 验证点：reloadAll 应依次调用各子模块重载并广播。
     * <p>测试方法 {@code reloadAllShouldInvokeAllSubmodules}：
     * <ul>
     *   <li>{@code verify(hotfixDataService).reload();}</li>
     *   <li>{@code verify(activityScheduleService).reloadSchedule();}</li>
     *   <li>{@code verify(activityConfigService).reload();}</li>
     *   <li>{@code verify(gachaConfigService).reload();}</li>
     *   <li>{@code verify(coachTipsRepository).reload();}</li>
     *   <li>{@code verify(guidePackRepository).reload();}</li>
     * </ul>
     */
    @Test
    @DisplayName("reloadAll 应依次调用各子模块重载并广播")
    void reloadAllShouldInvokeAllSubmodules() {
        coordinator.reloadAll();

        verify(hotfixDataService).reload();
        verify(activityScheduleService).reloadSchedule();
        verify(activityConfigService).reload();
        verify(gachaConfigService).reload();
        verify(coachTipsRepository).reload();
        verify(guidePackRepository).reload();
        verify(externalGuideCatalogRepository).reload();
        verify(assistSafetyRulesRepository).reload();
        verify(assistFeatureContentRepository).reload();
        verify(encounterConfigRepository).reload();
        verify(shopConfigRepository).reload();
        verify(skinConfigRepository).reload();
        verify(ragKnowledgeService).reloadPreferIncremental();
        verify(staticResourceRegistry).reloadAll();
        verify(updateNotifyBroadcaster).broadcastAll();
    }

    /**
     * 验证点：子模块失败时应回滚且不广播。
     * <p>测试方法 {@code stagedReloadShouldRollbackOnFailure}：
     * <ul>
     *   <li>{@code when(hotfixDataService.current()).thenReturn(prev);}</li>
     *   <li>{@code when(activityConfigService.snapshot()).thenReturn(prevActs);}</li>
     *   <li>{@code when(gachaConfigService.reload()).thenReturn(false);}</li>
     *   <li>{@code assertFalse(result.success());}</li>
     *   <li>{@code verify(hotfixDataService).restore(prev);}</li>
     *   <li>{@code verify(activityConfigService).restore(prevActs);}</li>
     * </ul>
     */
    @Test
    @DisplayName("子模块失败时应回滚且不广播")
    void stagedReloadShouldRollbackOnFailure() {
        HotfixData prev = HotfixData.empty();
        Map<Integer, ActivityConfig> prevActs = Collections.emptyMap();
        when(hotfixDataService.current()).thenReturn(prev);
        when(activityConfigService.snapshot()).thenReturn(prevActs);
        when(gachaConfigService.reload()).thenReturn(false);

        HotReloadCoordinator.ReloadResult result = coordinator.reloadAllStaged("tester");

        assertFalse(result.success());
        verify(hotfixDataService).restore(prev);
        verify(activityConfigService).restore(prevActs);
        verify(gachaConfigService).restore(any());
        verify(shopConfigRepository).restore(any());
        verify(skinConfigRepository).restore(any());
        verify(updateNotifyBroadcaster, never()).broadcastAll();
        assertTrue(result.message().contains("gacha"));
    }
}
