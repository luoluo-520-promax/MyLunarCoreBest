package cn.itcast.demo.mylunarcore.common;

import cn.itcast.demo.mylunarcore.assist.AssistAnswerCache;
import cn.itcast.demo.mylunarcore.assist.AssistFeatureContent;
import cn.itcast.demo.mylunarcore.assist.AssistFeatureContentRepository;
import cn.itcast.demo.mylunarcore.assist.AssistPersonaConfig;
import cn.itcast.demo.mylunarcore.assist.AssistPersonaRepository;
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
import cn.itcast.demo.mylunarcore.gacha.GachaBannerConfig;
import cn.itcast.demo.mylunarcore.gacha.GachaConfigService;
import cn.itcast.demo.mylunarcore.model.ActivityConfig;
import cn.itcast.demo.mylunarcore.skin.SkinConfigRepository;
import org.slf4j.Logger;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 配置热更新总控：先 snapshot 各仓储当前内存指针，再分阶段 reload；
 * 任一步失败则 restore 全部快照，避免半新半旧；成功则 bump 配置版本并
 * {@link UpdateNotifyBroadcaster#broadcastAll} 通知在线会话，同时写发布审计。
 */
@Component
public class HotReloadCoordinator {

    private static final Logger log = AppLogger.logger(LogCategory.SYSTEM, HotReloadCoordinator.class);

    private final HotfixDataService hotfixDataService; // hotfix.json
    private final ActivityScheduleService activityScheduleService; // 活动排期窗
    private final ActivityConfigService activityConfigService; // 活动详情表
    private final GachaConfigService gachaConfigService; // 卡池 Banner
    private final StaticResourceRegistry staticResourceRegistry; // 静态 CSV/表缓存
    private final UpdateNotifyBroadcaster updateNotifyBroadcaster; // 推客户端更新通知
    private final ConfigPublishAuditService auditService; // 运维审计
    private final CoachTipsRepository coachTipsRepository;
    private final GuidePackRepository guidePackRepository;
    private final ExternalGuideCatalogRepository externalGuideCatalogRepository;
    private final RagKnowledgeService ragKnowledgeService; // RAG 索引
    private final AssistSafetyRulesRepository assistSafetyRulesRepository;
    private final AssistAnswerCache assistAnswerCache; // 热更后按 scene 失效缓存
    private final AssistFeatureContentRepository assistFeatureContentRepository;
    private final EncounterConfigRepository encounterConfigRepository;
    private final ShopConfigRepository shopConfigRepository;
    private final SkinConfigRepository skinConfigRepository;
    private final ConfigGrayRelease configGrayRelease; // 灰度 uid 尾号策略
    private final ConfigCacheVersion configCacheVersion; // 单调配置版本号
    private final AssistPersonaRepository assistPersonaRepository;
    private final org.springframework.beans.factory.ObjectProvider<cn.itcast.demo.mylunarcore.activity.ActivityScriptEngine> activityScriptEngineProvider;

    public HotReloadCoordinator(HotfixDataService hotfixDataService,
                                ActivityScheduleService activityScheduleService,
                                ActivityConfigService activityConfigService,
                                GachaConfigService gachaConfigService,
                                StaticResourceRegistry staticResourceRegistry,
                                UpdateNotifyBroadcaster updateNotifyBroadcaster,
                                ConfigPublishAuditService auditService,
                                CoachTipsRepository coachTipsRepository,
                                GuidePackRepository guidePackRepository,
                                ExternalGuideCatalogRepository externalGuideCatalogRepository,
                                RagKnowledgeService ragKnowledgeService,
                                AssistSafetyRulesRepository assistSafetyRulesRepository,
                                AssistAnswerCache assistAnswerCache,
                                AssistFeatureContentRepository assistFeatureContentRepository,
                                EncounterConfigRepository encounterConfigRepository,
                                ShopConfigRepository shopConfigRepository,
                                SkinConfigRepository skinConfigRepository,
                                ConfigGrayRelease configGrayRelease,
                                ConfigCacheVersion configCacheVersion,
                                AssistPersonaRepository assistPersonaRepository,
                                org.springframework.beans.factory.ObjectProvider<cn.itcast.demo.mylunarcore.activity.ActivityScriptEngine> activityScriptEngineProvider) {
        this.hotfixDataService = hotfixDataService;
        this.activityScheduleService = activityScheduleService;
        this.activityConfigService = activityConfigService;
        this.gachaConfigService = gachaConfigService;
        this.staticResourceRegistry = staticResourceRegistry;
        this.updateNotifyBroadcaster = updateNotifyBroadcaster;
        this.auditService = auditService;
        this.coachTipsRepository = coachTipsRepository;
        this.guidePackRepository = guidePackRepository;
        this.externalGuideCatalogRepository = externalGuideCatalogRepository;
        this.ragKnowledgeService = ragKnowledgeService;
        this.assistSafetyRulesRepository = assistSafetyRulesRepository;
        this.assistAnswerCache = assistAnswerCache;
        this.assistFeatureContentRepository = assistFeatureContentRepository;
        this.encounterConfigRepository = encounterConfigRepository;
        this.shopConfigRepository = shopConfigRepository;
        this.skinConfigRepository = skinConfigRepository;
        this.configGrayRelease = configGrayRelease;
        this.configCacheVersion = configCacheVersion;
        this.assistPersonaRepository = assistPersonaRepository;
        this.activityScriptEngineProvider = activityScriptEngineProvider;
    }

    /** 热更结果：success=false 时 message 为失败原因（已回滚）。 */
    public record ReloadResult(boolean success, String message) {
    }

    /**
     * 无操作者信息的便捷入口；失败抛 IllegalStateException（供内部系统调用）。
     */
    public void reloadAll() {
        ReloadResult result = reloadAllStaged("system");
        if (!result.success()) {
            throw new IllegalStateException(result.message());
        }
    }

    /**
     * 分阶段热更主流程。
     * <ol>
     *   <li>保存各模块 prev* 快照；</li>
     *   <li>依次 reload hotfix→排期→活动→抽卡→助手相关→遭遇→商店→皮肤→静态表→RAG；</li>
     *   <li>刷新灰度策略，失效助手 activity/gacha/lore 缓存；</li>
     *   <li>bump 配置版本并广播；写审计（灰度或全量）；</li>
     *   <li>异常则 restore 全部快照并记失败审计。</li>
     * </ol>
     *
     * @param operator 审计里的操作者标识（如 admin 用户名或 system）
     */
    public ReloadResult reloadAllStaged(String operator) {
        log.info("=== staged reload start: hotfix + schedule + activities + gacha + coach + guide + encounter + shop + skin + static ===");
        // ---- 快照：失败时原样 restore，保证内存配置原子性 ----
        HotfixData prevHotfix = hotfixDataService.current();
        List<ActivityScheduleService.ActivityWindow> prevSchedule = activityScheduleService.getWindows();
        Map<Integer, ActivityConfig> prevActivities = activityConfigService.snapshot();
        Map<Integer, List<GachaBannerConfig>> prevGacha = gachaConfigService.snapshot();
        CoachTipsConfig prevCoachTips = coachTipsRepository.current();
        GuidePackConfig prevGuide = guidePackRepository.current();
        ExternalGuideCatalogConfig prevExternalGuides = externalGuideCatalogRepository.current();
        AssistSafetyRulesConfig prevSafety = assistSafetyRulesRepository.current();
        AssistFeatureContent prevFeature = assistFeatureContentRepository.current();
        AssistPersonaConfig prevPersona = assistPersonaRepository == null ? null : assistPersonaRepository.current();
        EncounterConfig prevEncounter = encounterConfigRepository.current();
        Map<Integer, ShopConfigRepository.ShopConfig> prevShops = shopConfigRepository.snapshot();
        SkinConfigRepository.SkinConfigsFile prevSkins = skinConfigRepository.current();
        Map<String, List<String[]>> prevStaticRows = staticResourceRegistry.snapshotCachedRows();
        RagKnowledgeService.Snapshot prevRag = ragKnowledgeService.snapshot();
        try {
            // ---- 加载阶段：任一 false/异常立即失败进入 catch 回滚 ----
            if (!hotfixDataService.reload()) {
                throw new IllegalStateException("hotfix reload failed or resource missing");
            }
            activityScheduleService.reloadSchedule();
            activityConfigService.reload();
            if (!gachaConfigService.reload()) {
                throw new IllegalStateException("gacha reload failed");
            }
            if (!coachTipsRepository.reload()) {
                throw new IllegalStateException("coach tips reload failed");
            }
            if (!guidePackRepository.reload()) {
                throw new IllegalStateException("guide pack reload failed");
            }
            if (!externalGuideCatalogRepository.reload()) {
                throw new IllegalStateException("external guide catalog reload failed");
            }
            if (!assistSafetyRulesRepository.reload()) {
                throw new IllegalStateException("assist safety rules reload failed");
            }
            if (!assistFeatureContentRepository.reload()) {
                throw new IllegalStateException("assist feature content reload failed");
            }
            if (assistPersonaRepository != null) {
                assistPersonaRepository.reload(); // 人设缺省不阻断全量热更
            }
            if (!encounterConfigRepository.reload()) {
                throw new IllegalStateException("encounter configs reload failed");
            }
            if (!shopConfigRepository.reload()) {
                throw new IllegalStateException("shop configs reload failed");
            }
            if (!skinConfigRepository.reload()) {
                throw new IllegalStateException("skin configs reload failed");
            }
            staticResourceRegistry.reloadAll();
            ragKnowledgeService.reloadPreferIncremental(); // 增量优先，失败回退全量
            var scriptEngine = activityScriptEngineProvider == null
                    ? null : activityScriptEngineProvider.getIfAvailable();
            if (scriptEngine != null) {
                scriptEngine.reloadAll();
            }
            configGrayRelease.refreshFromProperties(); // 从配置刷新 uid 尾号灰度
            // 配置变了，清掉依赖活动/抽卡/设定的助手答案缓存
            assistAnswerCache.invalidateScene("activity");
            assistAnswerCache.invalidateScene("gacha");
            assistAnswerCache.invalidateScene("lore");
            long cfgVer = configCacheVersion.bump(); // 客户端可用版本号判断是否拉新
            int pushed = updateNotifyBroadcaster.broadcastAll(); // 返回通知到的会话数
            if (configGrayRelease.current().enabled()) {
                // 灰度模式：审计记 gray 策略与版本，不写全量资源名列表
                auditService.record(operator, "reloadAllGray",
                        List.of("gray=" + configGrayRelease.current().note()),
                        false, true, "uidTails=" + configGrayRelease.current().uidTailDigits()
                                + ",configVersion=" + cfgVer);
                log.info("=== staged reload success (GRAY MODE), broadcastSessions={} policy={} configVersion={} ===",
                        pushed, configGrayRelease.current(), cfgVer);
            } else {
                // 全量模式：审计列出各配置域名称
                auditService.record(operator, "reloadAll",
                        List.of("hotfix", "schedule", "activities", "gacha", "coachTips", "guidePack",
                                "externalGuides", "safetyRules", "featureContent", "persona", "encounter", "shop", "skin",
                                "static", "rag"),
                        false, true, "broadcastSessions=" + pushed + ",configVersion=" + cfgVer);
                log.info("=== staged reload success, broadcastSessions={} configVersion={} ===", pushed, cfgVer);
            }
            return new ReloadResult(true, "ok");
        } catch (Exception e) {
            // ---- 回滚：把所有模块指针恢复到本轮开始前 ----
            log.error("Staged reload failed, rolling back in-memory configs", e);
            hotfixDataService.restore(prevHotfix);
            activityScheduleService.restore(prevSchedule);
            activityConfigService.restore(prevActivities);
            gachaConfigService.restore(prevGacha);
            coachTipsRepository.restore(prevCoachTips);
            guidePackRepository.restore(prevGuide);
            externalGuideCatalogRepository.restore(prevExternalGuides);
            assistSafetyRulesRepository.restore(prevSafety);
            assistFeatureContentRepository.restore(prevFeature);
            if (assistPersonaRepository != null) {
                assistPersonaRepository.restore(prevPersona);
            }
            encounterConfigRepository.restore(prevEncounter);
            shopConfigRepository.restore(prevShops);
            skinConfigRepository.restore(prevSkins);
            staticResourceRegistry.restoreCachedRows(prevStaticRows);
            ragKnowledgeService.restore(prevRag);
            auditService.record(operator, "reloadAll", List.of(), false, false, e.getMessage());
            return new ReloadResult(false, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }
}
