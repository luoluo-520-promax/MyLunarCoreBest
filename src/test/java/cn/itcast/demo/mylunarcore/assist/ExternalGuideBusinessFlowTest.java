package cn.itcast.demo.mylunarcore.assist;

import cn.itcast.demo.mylunarcore.analytics.AnalyticsEventPublisher;
import cn.itcast.demo.mylunarcore.assist.remote.AiAssistClient;
import cn.itcast.demo.mylunarcore.common.ActivityQueryService;
import cn.itcast.demo.mylunarcore.common.BusinessMetrics;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.hall.MailApplicationService;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.player.PlayerContextResolver;
import cn.itcast.demo.mylunarcore.protocol.AssistSystemProto;
import cn.itcast.demo.mylunarcore.quest.QuestConfigRepository;
import cn.itcast.demo.mylunarcore.quest.QuestProgressApplicationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 外链攻略新功能全链路业务测试：目录加载 → 检索 → 主问答 → 协议下发。
 */
@DisplayName("外链攻略业务流程 ExternalGuideBusinessFlow")
class ExternalGuideBusinessFlowTest {

    private ExternalGuideCatalogConfig productionCatalog;
    private ExternalGuideCatalogRepository catalogRepo;
    private ExternalGuideSearchService searchService;

    @BeforeEach
    void setUp() throws Exception {
        Path catalogPath = Path.of("data/ExternalGuideCatalog.json");
        productionCatalog = new ObjectMapper().readValue(catalogPath.toFile(), ExternalGuideCatalogConfig.class);
        catalogRepo = mock(ExternalGuideCatalogRepository.class);
        when(catalogRepo.current()).thenReturn(productionCatalog);
        searchService = new ExternalGuideSearchService(catalogRepo);
    }

    @Nested
    @DisplayName("1. 目录与平台检索")
    class CatalogAndSearch {

        @Test
        @DisplayName("生产目录应启用 B站/抖音/官方网站三平台")
        void productionCatalogShouldHaveThreePlatforms() {
            assertTrue(Boolean.TRUE.equals(productionCatalog.enabled()));
            var ids = productionCatalog.platforms().stream().map(ExternalGuideCatalogConfig.PlatformDef::id)
                    .collect(Collectors.toSet());
            assertTrue(ids.contains("bilibili"));
            assertTrue(ids.contains("douyin"));
            assertTrue(ids.contains("official"));
            assertFalse(ids.contains("youtube"));
            assertFalse(ids.contains("miyoushe"));
        }

        @Test
        @DisplayName("问官网应优先命中官方网站精选")
        void officialKeywordShouldHitOfficialGuide() {
            List<AssistMediaLink> links = searchService.search("官网活动公告在哪看", "meta");
            assertFalse(links.isEmpty());
            assertTrue(links.stream().anyMatch(l ->
                    "official".equals(l.platform()) && l.url().contains("mihoyo")));
            assertTrue(links.stream().anyMatch(AssistMediaLink::curated));
        }

        @Test
        @DisplayName("问抖音攻略应命中抖音或平台搜索深链")
        void douyinGuideShouldReturnDouyinLink() {
            List<AssistMediaLink> links = searchService.search("抖音有没有忘却之庭攻略视频", "abyss");
            assertFalse(links.isEmpty());
            assertTrue(links.stream().anyMatch(l ->
                    "douyin".equals(l.platform()) || l.url().contains("douyin")));
        }

        @Test
        @DisplayName("问 B 站攻略应返回 bilibili 链接")
        void bilibiliGuideShouldReturnBiliLink() {
            List<AssistMediaLink> links = searchService.search("B站新手攻略视频", "newbie");
            assertFalse(links.isEmpty());
            assertTrue(links.stream().anyMatch(l ->
                    "bilibili".equals(l.platform()) || l.url().contains("bilibili")));
        }

        @Test
        @DisplayName("攻略意图无精选命中时应回落三平台搜索深链")
        void guideIntentWithoutCuratedShouldFallbackPlatformSearch() {
            ExternalGuideCatalogConfig noGuides = new ExternalGuideCatalogConfig(
                    "t", true, 3, "崩坏星穹铁道",
                    productionCatalog.allowedHosts(),
                    productionCatalog.guideIntentKeywords(),
                    productionCatalog.platforms(),
                    List.of());
            when(catalogRepo.current()).thenReturn(noGuides);
            ExternalGuideSearchService svc = new ExternalGuideSearchService(catalogRepo);

            List<AssistMediaLink> links = svc.search("这个角色怎么培养 攻略", "growth");
            assertEquals(3, links.size());
            var platforms = links.stream().map(AssistMediaLink::platform).collect(Collectors.toSet());
            assertTrue(platforms.contains("bilibili"));
            assertTrue(platforms.contains("douyin"));
            assertTrue(platforms.contains("official"));
            assertTrue(links.stream().noneMatch(AssistMediaLink::curated));
            assertTrue(links.stream().anyMatch(l -> l.url().contains("bilibili")));
            assertTrue(links.stream().anyMatch(l -> l.url().contains("douyin")));
            assertTrue(links.stream().anyMatch(l -> l.url().contains("mihoyo")));
        }

        @Test
        @DisplayName("目录关闭时检索应为空")
        void disabledCatalogShouldReturnEmpty() {
            when(catalogRepo.current()).thenReturn(ExternalGuideCatalogConfig.empty());
            assertTrue(new ExternalGuideSearchService(catalogRepo)
                    .search("攻略视频", "general").isEmpty());
        }

        @Test
        @DisplayName("答案后缀应提示站内浮层且不含原始 URL")
        void answerSuffixShouldContainPlatformAndUrl() {
            List<AssistMediaLink> links = searchService.search("官方网站", "meta");
            String suffix = searchService.formatAnswerSuffix(links);
            assertTrue(suffix.contains("站内攻略浮层"));
            assertFalse(suffix.contains("https://"));
            assertTrue(suffix.contains("official") || suffix.contains("官网") || suffix.contains("mihoyo")
                    || !links.isEmpty());
        }
    }

    @Nested
    @DisplayName("2. 主问答链路")
    class AskPipeline {

        @Test
        @DisplayName("真实检索服务接入 ask：攻略问句应带回 mediaLinks")
        void askWithRealSearchShouldAttachMediaLinks() {
            LunarCoreProperties props = enabledProps();
            props.getAiAssist().setExternalGuideEnabled(true);
            props.getAiAssist().setPreferRemoteOnly(false);
            props.getAiAssist().setLlmEnabled(true);

            AiAssistClient client = mock(AiAssistClient.class);
            when(client.isRemoteEnabled()).thenReturn(false);
            LlmAssistGateway llm = mock(LlmAssistGateway.class);
            when(llm.ask(anyString(), any(), any(), any())).thenReturn(Optional.of("先看官方说明，再参考社区视频。"));

            AiAssistApplicationService app = buildAskService(props, searchService, client, llm);
            AssistAnswer answer = app.ask(2001L, "忘却之庭通关攻略有视频吗", "abyss");

            assertEquals(0, answer.retcode());
            assertFalse(answer.mediaLinks().isEmpty());
            assertTrue(answer.answer().contains("站内攻略浮层") || !answer.inlineSummary().isBlank());
            assertTrue(answer.forbidExternalBrowser());
            assertTrue(answer.mediaLinks().stream().allMatch(l -> l.url().startsWith("https://")));
            assertTrue(answer.mediaLinks().stream().noneMatch(l ->
                    "OPEN_EXTERNAL_LINK".equals(l.action()) || "OPEN_VIDEO".equals(l.action())));
            assertTrue(answer.citedConfigIds().stream().anyMatch(id -> id.startsWith("ext-guide:"))
                    || answer.mediaLinks().stream().anyMatch(l -> !l.curated()));
        }

        @Test
        @DisplayName("externalGuideEnabled=false 时不应附带外链")
        void disabledFlagShouldSkipMediaLinks() {
            LunarCoreProperties props = enabledProps();
            props.getAiAssist().setExternalGuideEnabled(false);
            props.getAiAssist().setPreferRemoteOnly(false);

            AiAssistClient client = mock(AiAssistClient.class);
            when(client.isRemoteEnabled()).thenReturn(false);
            LlmAssistGateway llm = mock(LlmAssistGateway.class);
            when(llm.ask(anyString(), any(), any(), any())).thenReturn(Optional.of("建议打开任务界面。"));

            AiAssistApplicationService app = buildAskService(props, searchService, client, llm);
            AssistAnswer answer = app.ask(2002L, "深渊攻略视频", "abyss");
            assertEquals(0, answer.retcode());
            assertTrue(answer.mediaLinks().isEmpty());
            assertFalse(answer.answer().contains("相关攻略（主流平台）"));
        }

        @Test
        @DisplayName("非攻略问句即使检索服务可用也不应硬塞外链")
        void nonGuideQuestionShouldNotForceMedia() {
            LunarCoreProperties props = enabledProps();
            props.getAiAssist().setPreferRemoteOnly(false);
            AiAssistClient client = mock(AiAssistClient.class);
            when(client.isRemoteEnabled()).thenReturn(false);
            LlmAssistGateway llm = mock(LlmAssistGateway.class);
            when(llm.ask(anyString(), any(), any(), any())).thenReturn(Optional.of("今天可以先做日常。"));

            AiAssistApplicationService app = buildAskService(props, searchService, client, llm);
            AssistAnswer answer = app.ask(2003L, "今天做什么日常比较合适", "general");
            assertEquals(0, answer.retcode());
            assertTrue(answer.mediaLinks().isEmpty());
        }
    }

    @Nested
    @DisplayName("3. 协议下发")
    class ProtocolMapping {

        @Test
        @DisplayName("toAskRsp 应映射 media_links 全字段")
        void toAskRspShouldMapMediaLinks() {
            AssistAnswer answer = AssistAnswer.of(0, "正文", "llm", List.of(), List.of("ext-guide:eg1"),
                    "免责", "baseline", "zh-CN",
                    List.of(new AssistMediaLink("eg1", "B站攻略", "bilibili", "video",
                                    "https://search.bilibili.com/all?keyword=x", "OPEN_VIDEO", true),
                            new AssistMediaLink("search:douyin", "抖音搜索", "douyin", "search",
                                    "https://www.douyin.com/search/x", "OPEN_EXTERNAL_LINK", false)));

            AssistSystemProto.AskAiAssistScRsp rsp = AssistNettyService.toAskRsp(answer);
            assertEquals(2, rsp.getMediaLinksCount());
            AssistSystemProto.AssistMediaLink first = rsp.getMediaLinks(0);
            assertEquals("eg1", first.getId());
            assertEquals("bilibili", first.getPlatform());
            assertEquals("video", first.getMediaType());
            assertEquals("OPEN_VIDEO_INLINE", first.getAction());
            assertEquals("INLINE_WEBVIEW", first.getRenderMode());
            assertTrue(first.getCurated());
            assertTrue(first.getUrl().startsWith("https://"));
            assertTrue(rsp.getForbidExternalBrowser());

            AssistSystemProto.AssistMediaLink second = rsp.getMediaLinks(1);
            assertEquals("douyin", second.getPlatform());
            assertFalse(second.getCurated());
            assertEquals("OPEN_IN_APP_WEBVIEW", second.getAction());
        }

        @Test
        @DisplayName("同步 AskAiAssist 应把 media_links 返回给客户端")
        void syncAskAiAssistShouldReturnMediaLinks() {
            LunarCoreProperties props = enabledProps();
            props.getAiAssist().setSyncMode(true);

            List<AssistMediaLink> links = List.of(new AssistMediaLink(
                    "eg_official_home", "官网", "official", "article",
                    "https://sr.mihoyo.com/", "OPEN_EXTERNAL_LINK", true));
            AiAssistApplicationService app = mock(AiAssistApplicationService.class);
            when(app.tryLocalFast(anyLong(), anyString(), anyString(), any(), any(), any()))
                    .thenReturn(Optional.empty());
            when(app.ask(anyLong(), anyString(), anyString(), any(), any(), any()))
                    .thenReturn(AssistAnswer.of(0, "请看官网", "llm", List.of(), List.of("ext-guide:eg_official_home"),
                            "免责", "baseline", "zh-CN", links));

            AssistNettyService netty = newNetty(props, app);
            AssistSystemProto.AskAiAssistScRsp rsp = netty.handleAskAiAssist(
                    AssistSystemProto.AskAiAssistCsReq.newBuilder()
                            .setQuestion("官方网站攻略")
                            .setScene("meta")
                            .build(),
                    mock(Channel.class));

            assertEquals(0, rsp.getRetcode());
            assertEquals(1, rsp.getMediaLinksCount());
            assertEquals("official", rsp.getMediaLinks(0).getPlatform());
            assertEquals("https://sr.mihoyo.com/", rsp.getMediaLinks(0).getUrl());
        }

        @Test
        @DisplayName("askAndNotify 推送通知应携带 media_links")
        void askAndNotifyShouldPushMediaLinks() {
            LunarCoreProperties props = enabledProps();
            AiAssistApplicationService app = mock(AiAssistApplicationService.class);
            when(app.ask(1L, "抖音攻略", "abyss")).thenReturn(AssistAnswer.of(
                    0, "看这个视频", "rule", List.of(), List.of(),
                    "", "", "zh-CN",
                    List.of(new AssistMediaLink("eg_abyss_douyin", "抖音攻略", "douyin", "video",
                            "https://www.douyin.com/search/x", "OPEN_VIDEO", true))));

            AssistNettyService netty = newNetty(props, app);
            Channel channel = mock(Channel.class);
            when(channel.isActive()).thenReturn(true);
            when(channel.writeAndFlush(any())).thenReturn(mock(ChannelFuture.class));

            AssistAnswer answer = netty.askAndNotify(1L, channel, "抖音攻略", "abyss");
            assertEquals(1, answer.mediaLinks().size());
            verify(channel).writeAndFlush(any());
        }
    }

    @Nested
    @DisplayName("4. 热更仓库")
    class CatalogRepository {

        @Test
        @DisplayName("reload 成功后 current 应读到生产目录三平台")
        void reloadShouldLoadProductionFile() {
            LunarCoreProperties props = new LunarCoreProperties();
            props.setDataDir("data");
            props.getAiAssist().setExternalGuideCatalogResource("ExternalGuideCatalog.json");
            ExternalGuideCatalogRepository repo = new ExternalGuideCatalogRepository(
                    new cn.itcast.demo.mylunarcore.common.ConfigFileService(props), props);
            assertTrue(repo.reload());
            ExternalGuideCatalogConfig cfg = repo.current();
            assertTrue(Boolean.TRUE.equals(cfg.enabled()));
            assertEquals(3, cfg.platforms().size());
            assertTrue(cfg.guides().stream().anyMatch(g -> "official".equals(g.platform())));
        }

        @Test
        @DisplayName("restore 应回退到旧快照")
        void restoreShouldRollbackSnapshot() {
            LunarCoreProperties props = new LunarCoreProperties();
            props.setDataDir("data");
            ExternalGuideCatalogRepository repo = new ExternalGuideCatalogRepository(
                    new cn.itcast.demo.mylunarcore.common.ConfigFileService(props), props);
            repo.reload();
            ExternalGuideCatalogConfig previous = ExternalGuideCatalogConfig.empty();
            repo.restore(previous);
            assertFalse(Boolean.TRUE.equals(repo.current().enabled()));
        }
    }

    private static LunarCoreProperties enabledProps() {
        LunarCoreProperties props = new LunarCoreProperties();
        props.getAiAssist().setEnabled(true);
        props.getAiAssist().setAnswerCacheTtlSeconds(0);
        props.getAiAssist().setExternalGuideEnabled(true);
        return props;
    }

    private static AiAssistApplicationService buildAskService(LunarCoreProperties props,
                                                              ExternalGuideSearchService guides,
                                                              AiAssistClient client,
                                                              LlmAssistGateway llm) {
        GuidePackRepository guidePack = mock(GuidePackRepository.class);
        when(guidePack.current()).thenReturn(GuidePackConfig.empty());
        PlayerCoachApplicationService coach = mock(PlayerCoachApplicationService.class);
        when(coach.suggest(anyLong(), anyInt())).thenReturn(List.of());
        RagKnowledgeService rag = mock(RagKnowledgeService.class);
        when(rag.search(anyString(), anyString(), anyInt())).thenReturn(List.of());
        QuestProgressApplicationService quests = mock(QuestProgressApplicationService.class);
        when(quests.list(anyInt())).thenReturn(List.of());
        AssistContextBuilder ctx = mock(AssistContextBuilder.class);
        when(ctx.build(any(), any(), any())).thenReturn(Map.of("scene", "abyss"));

        ExplorePathAdvisor explore = mock(ExplorePathAdvisor.class);
        when(explore.adviseFromQuestion(any())).thenReturn(Optional.empty());
        QuestGuidanceService questGuide = mock(QuestGuidanceService.class);
        when(questGuide.looksLost(any())).thenReturn(false);
        WorldLoreService lore = mock(WorldLoreService.class);
        when(lore.answer(any())).thenReturn(Optional.empty());
        AssistConversationHistoryService history = mock(AssistConversationHistoryService.class);
        when(history.promptBlock(anyLong(), any())).thenReturn("");
        AssistPiiRedactor pii = mock(AssistPiiRedactor.class);
        when(pii.redact(any())).thenAnswer(inv -> inv.getArgument(0));
        when(pii.redactContext(any())).thenAnswer(inv -> inv.getArgument(0));

        AssistAnswerCache cache = new AssistAnswerCache(props);
        cache.init();

        return new AiAssistApplicationService(
                props, mock(GameSessionManager.class), coach, quests, mock(QuestConfigRepository.class),
                mock(ActivityQueryService.class), ctx, rag, llm,
                new AssistSafetyFilter(AssistSafetyRulesConfig::defaults),
                new AssistAuditService(), mock(MailApplicationService.class), client, guidePack,
                cache, explore, questGuide, lore, history, pii,
                new AssistLocaleService(props), new AssistStrategyGrayService(props),
                mock(BusinessMetrics.class), mock(AnalyticsEventPublisher.class), guides,
                new cn.itcast.demo.mylunarcore.assist.funnel.AssistIntentFunnelService(props, cache),
                new cn.itcast.demo.mylunarcore.assist.render.AssistStructuredRenderService(),
                mock(cn.itcast.demo.mylunarcore.assist.cultivation.AssistCultivationAdvisorService.class),
                mock(cn.itcast.demo.mylunarcore.assist.memory.AssistLongTermMemoryService.class));
    }

    private static AssistNettyService newNetty(LunarCoreProperties props, AiAssistApplicationService app) {
        PlayerContextResolver resolver = mock(PlayerContextResolver.class);
        when(resolver.resolveUid(any())).thenReturn(OptionalLong.of(1L));
        AssistQuotaLimiter quota = mock(AssistQuotaLimiter.class);
        when(quota.tryAcquireSameQuestion(anyLong(), any())).thenReturn(AssistQuotaLimiter.AcquireResult.ok());
        when(quota.tryAcquire(anyLong(), any(), anyInt())).thenReturn(AssistQuotaLimiter.AcquireResult.ok());
        when(quota.tryAcquire(anyLong(), any(), anyInt(), anyBoolean()))
                .thenReturn(AssistQuotaLimiter.AcquireResult.ok());
        GameSessionManager sessions = mock(GameSessionManager.class);
        when(sessions.getOrNull(anyLong())).thenReturn(null);
        return new AssistNettyService(props, resolver, quota,
                mock(PlayerCoachApplicationService.class), app, mock(GuidePackRepository.class),
                new AssistAuditService(),
                new AssistFeedbackService(mock(BusinessMetrics.class), mock(AnalyticsEventPublisher.class)),
                mock(OwnedLineupRecommendService.class),
                mock(ExplorePathAdvisor.class), mock(QuestGuidanceService.class),
                Executors.newSingleThreadExecutor(), sessions, mock(AssistProactiveCoachService.class));
    }
}
