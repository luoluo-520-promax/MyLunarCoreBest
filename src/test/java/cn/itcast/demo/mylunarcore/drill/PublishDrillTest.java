package cn.itcast.demo.mylunarcore.drill;

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
import cn.itcast.demo.mylunarcore.center.InMemoryMultiNodeRoutingClient;
import cn.itcast.demo.mylunarcore.center.MigrationTicketService;
import cn.itcast.demo.mylunarcore.center.MigrationWriteFreezeService;
import cn.itcast.demo.mylunarcore.center.PlayerMigrationService;
import cn.itcast.demo.mylunarcore.center.RemoteCenterServer;
import cn.itcast.demo.mylunarcore.center.SceneRegistry;
import cn.itcast.demo.mylunarcore.common.ActivityConfigService;
import cn.itcast.demo.mylunarcore.common.ActivityScheduleService;
import cn.itcast.demo.mylunarcore.common.ConfigCacheVersion;
import cn.itcast.demo.mylunarcore.common.ConfigGrayRelease;
import cn.itcast.demo.mylunarcore.common.ConfigPublishAuditService;
import cn.itcast.demo.mylunarcore.common.HotReloadCoordinator;
import cn.itcast.demo.mylunarcore.common.HotfixData;
import cn.itcast.demo.mylunarcore.common.HotfixDataService;
import cn.itcast.demo.mylunarcore.common.StaticResourceRegistry;
import cn.itcast.demo.mylunarcore.common.UpdateNotifyBroadcaster;
import cn.itcast.demo.mylunarcore.economy.ShopConfigRepository;
import cn.itcast.demo.mylunarcore.gacha.GachaConfigService;
import cn.itcast.demo.mylunarcore.matchmaking.MatchQueue;
import cn.itcast.demo.mylunarcore.scene.SceneManager;
import cn.itcast.demo.mylunarcore.scene.ZoneManager;
import cn.itcast.demo.mylunarcore.skin.SkinConfigRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 发布演练：热更失败回滚、跨节点迁移拒绝、匹配队列排水（扩容/滚更前置检查）。
 */
@DisplayName("发布演练 PublishDrill")
class PublishDrillTest {

    @Test
    @DisplayName("热更坏配置应回滚且不广播")
    void hotReloadBadConfigShouldRollbackWithoutBroadcast() {
        HotfixDataService hotfix = mock(HotfixDataService.class);
        ActivityScheduleService schedule = mock(ActivityScheduleService.class);
        ActivityConfigService activity = mock(ActivityConfigService.class);
        GachaConfigService gacha = mock(GachaConfigService.class);
        StaticResourceRegistry resources = mock(StaticResourceRegistry.class);
        UpdateNotifyBroadcaster broadcaster = mock(UpdateNotifyBroadcaster.class);
        ConfigPublishAuditService audit = mock(ConfigPublishAuditService.class);
        CoachTipsRepository coachTips = mock(CoachTipsRepository.class);
        GuidePackRepository guidePack = mock(GuidePackRepository.class);
        ExternalGuideCatalogRepository externalGuides = mock(ExternalGuideCatalogRepository.class);
        RagKnowledgeService rag = mock(RagKnowledgeService.class);
        AssistSafetyRulesRepository safety = mock(AssistSafetyRulesRepository.class);
        AssistAnswerCache cache = mock(AssistAnswerCache.class);
        AssistFeatureContentRepository feature = mock(AssistFeatureContentRepository.class);
        EncounterConfigRepository encounter = mock(EncounterConfigRepository.class);
        ShopConfigRepository shop = mock(ShopConfigRepository.class);
        SkinConfigRepository skin = mock(SkinConfigRepository.class);

        HotfixData prev = HotfixData.empty();
        when(hotfix.current()).thenReturn(prev);
        when(schedule.getWindows()).thenReturn(List.of());
        when(activity.snapshot()).thenReturn(Map.of());
        when(gacha.snapshot()).thenReturn(Map.of());
        when(coachTips.current()).thenReturn(CoachTipsConfig.empty());
        when(guidePack.current()).thenReturn(GuidePackConfig.empty());
        when(externalGuides.current()).thenReturn(ExternalGuideCatalogConfig.empty());
        when(safety.current()).thenReturn(AssistSafetyRulesConfig.defaults());
        when(feature.current()).thenReturn(AssistFeatureContent.empty());
        when(encounter.current()).thenReturn(EncounterConfig.empty());
        when(shop.snapshot()).thenReturn(Map.of());
        when(skin.current()).thenReturn(SkinConfigRepository.SkinConfigsFile.empty());
        when(hotfix.reload()).thenReturn(true);
        when(gacha.reload()).thenReturn(false);
        when(audit.record(anyString(), anyString(), anyList(), anyBoolean(), anyBoolean(), anyString()))
                .thenReturn(new ConfigPublishAuditService.PublishRecord(
                        1L, "drill", "reloadAll", List.of(), false, false, "gacha failed",
                        java.time.Instant.now()));

        ConfigGrayRelease gray = mock(ConfigGrayRelease.class);
        when(gray.current()).thenReturn(ConfigGrayRelease.GrayPolicy.disabled());
        HotReloadCoordinator coordinator = new HotReloadCoordinator(
                hotfix, schedule, activity, gacha, resources, broadcaster, audit, coachTips, guidePack,
                externalGuides, rag, safety, cache, feature, encounter, shop, skin, gray,
                new ConfigCacheVersion(),
                mock(cn.itcast.demo.mylunarcore.assist.AssistPersonaRepository.class),
                null);

        HotReloadCoordinator.ReloadResult result = coordinator.reloadAllStaged("drill");

        assertFalse(result.success());
        verify(hotfix).restore(prev);
        verify(gacha).restore(any());
        verify(broadcaster, never()).broadcastAll();
    }

    @Test
    @DisplayName("滚更场景：目标落在他节点时应拒绝本地迁移")
    void rollingUpgradeRemoteNodeShouldRejectLocalMigrate() {
        RemoteCenterServer remote = new RemoteCenterServer(
                new InMemoryMultiNodeRoutingClient(List.of("node-a", "node-b")), "node-a");
        SceneManager sceneManager = mock(SceneManager.class);
        when(sceneManager.getByPlayerUid(anyLong())).thenReturn(null);
        PlayerMigrationService migration = new PlayerMigrationService(
                remote, mock(ZoneManager.class), sceneManager, new MigrationTicketService(), new SceneRegistry(),
                mock(cn.itcast.demo.mylunarcore.scene.SceneSnapshotService.class),
                mock(cn.itcast.demo.mylunarcore.battle.BattleSnapshotService.class),
                new MigrationWriteFreezeService(),
                10_000, 3);

        PlayerMigrationService.MigrationResult result = migration.migrate(1001L, 1, 1);
        assertFalse(result.success());
        assertEquals(3, result.retcode());
    }

    @Test
    @DisplayName("扩容前置：匹配队列排水应清空排队玩家")
    void scaleOutShouldDrainMatchQueues() {
        MatchQueue queue = new MatchQueue();
        queue.enqueue(new MatchQueue.QueueEntry(101, 1, 1, 100, System.currentTimeMillis()));
        queue.enqueue(new MatchQueue.QueueEntry(102, 1, 1, 100, System.currentTimeMillis()));
        queue.enqueue(new MatchQueue.QueueEntry(103, 2, 1, 100, System.currentTimeMillis()));

        List<Integer> drained = queue.drainAllPlayerIds();
        assertEquals(3, drained.size());
        assertTrue(drained.contains(101));
        assertTrue(drained.contains(103));
        assertTrue(queue.drainAllPlayerIds().isEmpty());
    }
}
