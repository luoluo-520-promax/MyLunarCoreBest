package cn.itcast.demo.mylunarcore.assist;

import cn.itcast.demo.mylunarcore.assist.remote.AiAssistClient;
import cn.itcast.demo.mylunarcore.assist.remote.RemoteAssistAskResponse;
import cn.itcast.demo.mylunarcore.common.ActivityQueryService;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.hall.MailApplicationService;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.quest.QuestConfigRepository;
import cn.itcast.demo.mylunarcore.quest.QuestProgressApplicationService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AiAssistApplicationService 问答链路测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code AiAssistApplicationServiceTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("AiAssistApplicationService 问答链路测试")
class AiAssistApplicationServiceTest {

    private static final Logger log = LoggerFactory.getLogger(AiAssistApplicationServiceTest.class);

    /**
     * 验证点：功能关闭应返回 retcode=2。
     * <p>测试方法 {@code disabledShouldReturnRetcode2}：
     * <ul>
     *   <li>{@code assertEquals(2, answer.retcode());}</li>
     * </ul>
     */
    @Test
    @DisplayName("功能关闭应返回 retcode=2")
    void disabledShouldReturnRetcode2() {
        LunarCoreProperties props = new LunarCoreProperties();
        props.getAiAssist().setEnabled(false);
        AiAssistApplicationService service = buildService(props, mock(GuidePackRepository.class),
                mock(AiAssistClient.class), mock(LlmAssistGateway.class), mock(RagKnowledgeService.class),
                mock(PlayerCoachApplicationService.class));
        AssistAnswer answer = service.ask(1L, "你好", "general");
        log.info("功能关闭校验: retcode={}, answer='{}', source='{}'",
                answer.retcode(), answer.answer(), answer.source());
        assertEquals(2, answer.retcode());
    }

    /**
     * 验证点：空问题应返回 retcode=5。
     * <p>测试方法 {@code emptyQuestionShouldReturnRetcode5}：
     * <ul>
     *   <li>{@code assertEquals(5, answer.retcode());}</li>
     * </ul>
     */
    @Test
    @DisplayName("空问题应返回 retcode=5")
    void emptyQuestionShouldReturnRetcode5() {
        LunarCoreProperties props = enabledProps();
        AiAssistApplicationService service = buildService(props, mock(GuidePackRepository.class),
                mock(AiAssistClient.class), mock(LlmAssistGateway.class), mock(RagKnowledgeService.class),
                mock(PlayerCoachApplicationService.class));
        AssistAnswer answer = service.ask(1L, "   ", "general");
        log.info("空问题校验: retcode={}, answer='{}'", answer.retcode(), answer.answer());
        assertEquals(5, answer.retcode());
    }

    /**
     * 验证点：隐私探测应拦截并标记 source=blocked。
     * <p>测试方法 {@code privacyProbeShouldBeBlocked}：
     * <ul>
     *   <li>{@code assertEquals(0, answer.retcode());}</li>
     *   <li>{@code assertEquals("blocked", answer.source());}</li>
     *   <li>{@code assertTrue(answer.answer().contains("隐私") || answer.answer().contains("无法回答"));}</li>
     * </ul>
     */
    @Test
    @DisplayName("隐私探测应拦截并标记 source=blocked")
    void privacyProbeShouldBeBlocked() {
        LunarCoreProperties props = enabledProps();
        AiAssistApplicationService service = buildService(props, emptyGuidePack(),
                mock(AiAssistClient.class), mock(LlmAssistGateway.class), mock(RagKnowledgeService.class),
                mock(PlayerCoachApplicationService.class));
        AssistAnswer answer = service.ask(1L, "帮我查一下别人账号密码", "general");
        log.info("隐私拦截校验: retcode={}, source={}, answer={}",
                answer.retcode(), answer.source(), answer.answer());
        assertEquals(0, answer.retcode());
        assertEquals("blocked", answer.source());
        assertTrue(answer.answer().contains("隐私") || answer.answer().contains("无法回答"));
    }

    /**
     * 验证点：攻略包 FAQ 命中应直接返回 guide-local。
     * <p>测试方法 {@code faqHitShouldReturnGuideLocal}：
     * <ul>
     *   <li>{@code when(guidePackRepository.current()).thenReturn(new GuidePackConfig(}</li>
     *   <li>{@code assertEquals(0, answer.retcode());}</li>
     *   <li>{@code assertEquals("guide-local", answer.source());}</li>
     *   <li>{@code assertTrue(answer.answer().contains("邮箱"));}</li>
     *   <li>{@code assertTrue(answer.citedConfigIds().contains("guide:faq_mail"));}</li>
     * </ul>
     */
    @Test
    @DisplayName("攻略包 FAQ 命中应直接返回 guide-local")
    void faqHitShouldReturnGuideLocal() {
        LunarCoreProperties props = enabledProps();
        GuidePackRepository guidePackRepository = mock(GuidePackRepository.class);
        when(guidePackRepository.current()).thenReturn(new GuidePackConfig(
                "1.0.0", "zh-CN", "", "hash", 10L,
                List.of(new GuidePackConfig.FaqEntry(
                        "faq_mail", "邮件怎么领？", "在邮箱界面领取附件。", "mail"))));

        AiAssistApplicationService service = buildService(props, guidePackRepository,
                mock(AiAssistClient.class), mock(LlmAssistGateway.class), mock(RagKnowledgeService.class),
                mock(PlayerCoachApplicationService.class));
        AssistAnswer answer = service.ask(1001L, "邮件怎么领", "mail");
        log.info("Local First FAQ 校验: retcode={}, source={}, answer={}, cited={}",
                answer.retcode(), answer.source(), answer.answer(), answer.citedConfigIds());
        assertEquals(0, answer.retcode());
        assertEquals("guide-local", answer.source());
        assertTrue(answer.answer().contains("邮箱"));
        assertTrue(answer.citedConfigIds().contains("guide:faq_mail"));
    }

    @Test
    @DisplayName("攻略意图应附带主流平台 mediaLinks")
    void guideIntentShouldAttachMediaLinks() {
        LunarCoreProperties props = enabledProps();
        props.getAiAssist().setExternalGuideEnabled(true);
        GuidePackRepository guidePackRepository = mock(GuidePackRepository.class);
        when(guidePackRepository.current()).thenReturn(GuidePackConfig.empty());

        ExternalGuideSearchService guides = mock(ExternalGuideSearchService.class);
        List<AssistMediaLink> links = List.of(new AssistMediaLink(
                "eg_abyss", "深渊攻略", "bilibili", "video",
                "https://search.bilibili.com/all?keyword=abyss", "OPEN_VIDEO", true));
        when(guides.search(anyString(), anyString())).thenReturn(links);
        when(guides.formatAnswerSuffix(any())).thenReturn("\n\n相关攻略\nhttps://search.bilibili.com/all?keyword=abyss");

        AiAssistClient client = mock(AiAssistClient.class);
        when(client.isRemoteEnabled()).thenReturn(false);
        LlmAssistGateway llm = mock(LlmAssistGateway.class);
        when(llm.ask(anyString(), any(), any(), any())).thenReturn(Optional.of("可参考社区视频攻略。"));
        RagKnowledgeService rag = mock(RagKnowledgeService.class);
        when(rag.search(anyString(), anyString(), anyInt())).thenReturn(List.of());
        PlayerCoachApplicationService coach = mock(PlayerCoachApplicationService.class);
        when(coach.suggest(anyLong(), anyInt())).thenReturn(List.of());

        QuestProgressApplicationService quests = mock(QuestProgressApplicationService.class);
        when(quests.list(anyInt())).thenReturn(List.of());
        AssistContextBuilder ctxBuilder = mock(AssistContextBuilder.class);
        when(ctxBuilder.build(any(), any(), any())).thenReturn(Map.of("scene", "abyss"));

        AiAssistApplicationService service = newService(
                props, mock(GameSessionManager.class), coach, quests, mock(QuestConfigRepository.class),
                mock(ActivityQueryService.class), ctxBuilder, rag, llm, client, guidePackRepository, guides);

        AssistAnswer answer = service.ask(1001L, "忘却之庭怎么打 有攻略视频吗", "abyss");
        assertEquals(0, answer.retcode());
        assertFalse(answer.mediaLinks().isEmpty());
        assertTrue(answer.answer().contains("bilibili") || answer.answer().contains("相关攻略")
                || answer.answer().contains("站内攻略浮层") || !answer.inlineSummary().isBlank());
        assertTrue(answer.forbidExternalBrowser());
        assertTrue(answer.citedConfigIds().stream().anyMatch(id -> id.startsWith("ext-guide:")));
    }

    /**
     * 验证点：设置相关问句应解析为 settings 场景并命中 FAQ。
     * <p>测试方法 {@code settingsQuestionShouldResolveSceneAndHitFaq}：
     * <ul>
     *   <li>{@code assertEquals("settings", AiAssistApplicationService.resolveScene(null, "怎么调画面画质"));}</li>
     *   <li>{@code assertEquals("settings", AiAssistApplicationService.resolveScene(null, "按键怎么改"));}</li>
     *   <li>{@code assertEquals("settings", AiAssistApplicationService.resolveScene(null, "联系客服问音量"));}</li>
     *   <li>{@code when(guidePackRepository.current()).thenReturn(new GuidePackConfig(}</li>
     *   <li>{@code assertEquals(0, answer.retcode());}</li>
     *   <li>{@code assertEquals("guide-local", answer.source());}</li>
     * </ul>
     */
    @Test
    @DisplayName("设置相关问句应解析为 settings 场景并命中 FAQ")
    void settingsQuestionShouldResolveSceneAndHitFaq() {
        assertEquals("settings", AiAssistApplicationService.resolveScene(null, "怎么调画面画质"));
        assertEquals("settings", AiAssistApplicationService.resolveScene(null, "按键怎么改"));
        assertEquals("settings", AiAssistApplicationService.resolveScene(null, "联系客服问音量"));

        LunarCoreProperties props = enabledProps();
        GuidePackRepository guidePackRepository = mock(GuidePackRepository.class);
        when(guidePackRepository.current()).thenReturn(new GuidePackConfig(
                "1.0.0", "zh-CN", "", "hash", 10L,
                List.of(new GuidePackConfig.FaqEntry(
                        "faq_settings_sound",
                        "怎么调声音/音量？",
                        "设置 → 声音：可分别调节主音量、音乐、音效、语音。",
                        "settings",
                        List.of("声音", "音量"),
                        11))));
        AiAssistApplicationService service = buildService(props, guidePackRepository,
                mock(AiAssistClient.class), mock(LlmAssistGateway.class), mock(RagKnowledgeService.class),
                mock(PlayerCoachApplicationService.class));
        AssistAnswer answer = service.ask(1001L, "怎么调音量", null);
        assertEquals(0, answer.retcode());
        assertEquals("guide-local", answer.source());
        assertTrue(answer.answer().contains("声音"));
    }

    /**
     * 验证点：远程旁路成功时应使用 remote 来源。
     * <p>测试方法 {@code remoteSuccessShouldUseRemoteSource}：
     * <ul>
     *   <li>{@code when(client.isRemoteEnabled()).thenReturn(true);}</li>
     *   <li>{@code when(client.ask(any())).thenReturn(Optional.of(new RemoteAssistAskResponse(}</li>
     *   <li>{@code when(coach.suggest(anyLong(), anyInt())).thenReturn(List.of());}</li>
     *   <li>{@code when(rag.search(anyString(), anyString(), anyInt())).thenReturn(List.of());}</li>
     *   <li>{@code when(quests.list(anyInt())).thenReturn(List.of());}</li>
     *   <li>{@code when(activities.listCurrentlyActiveConfigs()).thenReturn(List.of());}</li>
     * </ul>
     */
    @Test
    @DisplayName("远程旁路成功时应使用 remote 来源")
    void remoteSuccessShouldUseRemoteSource() {
        LunarCoreProperties props = enabledProps();
        AiAssistClient client = mock(AiAssistClient.class);
        when(client.isRemoteEnabled()).thenReturn(true);
        when(client.ask(any())).thenReturn(Optional.of(new RemoteAssistAskResponse(
                0, "先做主线", "rule",
                List.of(new RemoteAssistAskResponse.Hint("quest_next", "quest", 100, "继续", "去做主线", "OPEN", 1)),
                List.of("quest:1"))));

        PlayerCoachApplicationService coach = mock(PlayerCoachApplicationService.class);
        when(coach.suggest(anyLong(), anyInt())).thenReturn(List.of());
        RagKnowledgeService rag = mock(RagKnowledgeService.class);
        when(rag.search(anyString(), anyString(), anyInt())).thenReturn(List.of());
        QuestProgressApplicationService quests = mock(QuestProgressApplicationService.class);
        when(quests.list(anyInt())).thenReturn(List.of());
        ActivityQueryService activities = mock(ActivityQueryService.class);
        when(activities.listCurrentlyActiveConfigs()).thenReturn(List.of());
        AssistContextBuilder ctxBuilder = mock(AssistContextBuilder.class);
        when(ctxBuilder.build(any(), any(), anyString())).thenReturn(Map.of("scene", "quest"));

        AiAssistApplicationService service = newService(props, mock(GameSessionManager.class), coach, quests,
                mock(QuestConfigRepository.class), activities, ctxBuilder, rag, mock(LlmAssistGateway.class),
                client, emptyGuidePack());

        AssistAnswer answer = service.ask(1001L, "下一步做什么", "quest");
        log.info("远程旁路成功校验: retcode={}, source={}, answer={}, relatedTipId={}, cited={}",
                answer.retcode(), answer.source(), answer.answer(),
                answer.relatedHints().isEmpty() ? null : answer.relatedHints().get(0).tipId(),
                answer.citedConfigIds());
        assertEquals(0, answer.retcode());
        assertEquals("remote-rule", answer.source());
        assertEquals("先做主线", answer.answer());
        assertEquals("quest_next", answer.relatedHints().get(0).tipId());
    }

    /**
     * 验证点：远程失败且本地 LLM 可用时应 source=llm。
     * <p>测试方法 {@code localLlmFallbackShouldWork}：
     * <ul>
     *   <li>{@code when(client.isRemoteEnabled()).thenReturn(false);}</li>
     *   <li>{@code when(llm.ask(anyString(), any(), any())).thenReturn(Optional.of("建议先推进主线任务。"));}</li>
     *   <li>{@code when(coach.suggest(anyLong(), anyInt())).thenReturn(List.of(}</li>
     *   <li>{@code when(rag.search(anyString(), anyString(), anyInt())).thenReturn(List.of(}</li>
     *   <li>{@code when(quests.list(anyInt())).thenReturn(List.of());}</li>
     *   <li>{@code when(ctxBuilder.build(any(), any(), anyString())).thenReturn(Map.of("scene", "quest"));}</li>
     * </ul>
     */
    @Test
    @DisplayName("远程失败且本地 LLM 可用时应 source=llm")
    void localLlmFallbackShouldWork() {
        LunarCoreProperties props = enabledProps();
        AiAssistClient client = mock(AiAssistClient.class);
        when(client.isRemoteEnabled()).thenReturn(false);

        LlmAssistGateway llm = mock(LlmAssistGateway.class);
        when(llm.ask(anyString(), any(), any(), any())).thenReturn(Optional.of("建议先推进主线任务。"));

        PlayerCoachApplicationService coach = mock(PlayerCoachApplicationService.class);
        when(coach.suggest(anyLong(), anyInt())).thenReturn(List.of(
                new CoachHint("quest_next", "quest", 100, "继续", "去做主线", "OPEN", 1)));
        RagKnowledgeService rag = mock(RagKnowledgeService.class);
        when(rag.search(anyString(), anyString(), anyInt())).thenReturn(List.of(
                new RagKnowledgeService.KnowledgeChunk("quest:1", "quest", "任务 主线")));
        QuestProgressApplicationService quests = mock(QuestProgressApplicationService.class);
        when(quests.list(anyInt())).thenReturn(List.of());
        AssistContextBuilder ctxBuilder = mock(AssistContextBuilder.class);
        when(ctxBuilder.build(any(), any(), anyString())).thenReturn(Map.of("scene", "quest"));

        AiAssistApplicationService service = newService(props, mock(GameSessionManager.class), coach, quests,
                mock(QuestConfigRepository.class), mock(ActivityQueryService.class), ctxBuilder, rag, llm,
                client, emptyGuidePack());

        AssistAnswer answer = service.ask(1001L, "主线怎么做", "quest");
        log.info("本地 LLM 降级校验: retcode={}, source={}, answer={}, relatedCount={}, cited={}",
                answer.retcode(), answer.source(), answer.answer(),
                answer.relatedHints().size(), answer.citedConfigIds());
        assertEquals(0, answer.retcode());
        assertEquals("llm", answer.source());
        assertTrue(answer.answer().contains("主线"));
        assertEquals(List.of("quest:1"), answer.citedConfigIds());
    }

    /**
     * 验证点：LLM 也失败时应拼装规则/兜底文案。
     * <p>测试方法 {@code ruleFallbackShouldAssembleHintsAndKnowledge}：
     * <ul>
     *   <li>{@code when(client.isRemoteEnabled()).thenReturn(false);}</li>
     *   <li>{@code when(llm.ask(anyString(), any(), any())).thenReturn(Optional.empty());}</li>
     *   <li>{@code when(coach.suggest(anyLong(), anyInt())).thenReturn(List.of(}</li>
     *   <li>{@code when(rag.search(anyString(), anyString(), anyInt())).thenReturn(List.of(}</li>
     *   <li>{@code when(ctxBuilder.build(any(), any(), anyString())).thenReturn(Map.of("scene", "activity"));}</li>
     *   <li>{@code when(quests.list(anyInt())).thenReturn(List.of());}</li>
     * </ul>
     */
    @Test
    @DisplayName("LLM 也失败时应拼装规则/兜底文案")
    void ruleFallbackShouldAssembleHintsAndKnowledge() {
        LunarCoreProperties props = enabledProps();
        AiAssistClient client = mock(AiAssistClient.class);
        when(client.isRemoteEnabled()).thenReturn(false);
        LlmAssistGateway llm = mock(LlmAssistGateway.class);
        when(llm.ask(anyString(), any(), any(), any())).thenReturn(Optional.empty());

        PlayerCoachApplicationService coach = mock(PlayerCoachApplicationService.class);
        when(coach.suggest(anyLong(), anyInt())).thenReturn(List.of(
                new CoachHint("quest_next", "quest", 100, "继续任务", "去做主线", "OPEN", 1)));
        RagKnowledgeService rag = mock(RagKnowledgeService.class);
        when(rag.search(anyString(), anyString(), anyInt())).thenReturn(List.of(
                new RagKnowledgeService.KnowledgeChunk("activity:9", "activity", "活动 夏日庆典 收集代币赢大奖")));
        AssistContextBuilder ctxBuilder = mock(AssistContextBuilder.class);
        when(ctxBuilder.build(any(), any(), anyString())).thenReturn(Map.of("scene", "activity"));
        QuestProgressApplicationService quests = mock(QuestProgressApplicationService.class);
        when(quests.list(anyInt())).thenReturn(List.of());

        AiAssistApplicationService service = newService(props, mock(GameSessionManager.class), coach, quests,
                mock(QuestConfigRepository.class), mock(ActivityQueryService.class), ctxBuilder, rag, llm,
                client, emptyGuidePack());

        AssistAnswer answer = service.ask(1001L, "活动怎么玩", null);
        log.info("规则兜底拼装校验: retcode={}, source={}, answer={}",
                answer.retcode(), answer.source(), answer.answer());
        assertEquals(0, answer.retcode());
        assertEquals("rule", answer.source());
        assertTrue(answer.answer().contains("继续任务"));
        assertTrue(answer.answer().contains("activity:9"));
    }

    private static LunarCoreProperties enabledProps() {
        LunarCoreProperties props = new LunarCoreProperties();
        props.getAiAssist().setEnabled(true);
        props.getAiAssist().setAnswerCacheTtlSeconds(0);
        return props;
    }

    private static GuidePackRepository emptyGuidePack() {
        GuidePackRepository repo = mock(GuidePackRepository.class);
        when(repo.current()).thenReturn(GuidePackConfig.empty());
        return repo;
    }

    private static AssistAnswerCache disabledCache(LunarCoreProperties props) {
        AssistAnswerCache cache = new AssistAnswerCache(props);
        cache.init();
        return cache;
    }

    private static AiAssistApplicationService newService(LunarCoreProperties props,
                                                         GameSessionManager sessions,
                                                         PlayerCoachApplicationService coach,
                                                         QuestProgressApplicationService quests,
                                                         QuestConfigRepository questConfig,
                                                         ActivityQueryService activities,
                                                         AssistContextBuilder ctxBuilder,
                                                         RagKnowledgeService rag,
                                                         LlmAssistGateway llm,
                                                         AiAssistClient client,
                                                         GuidePackRepository guide) {
        return newService(props, sessions, coach, quests, questConfig, activities, ctxBuilder, rag, llm, client, guide,
                emptyExternalGuides());
    }

    private static AiAssistApplicationService newService(LunarCoreProperties props,
                                                         GameSessionManager sessions,
                                                         PlayerCoachApplicationService coach,
                                                         QuestProgressApplicationService quests,
                                                         QuestConfigRepository questConfig,
                                                         ActivityQueryService activities,
                                                         AssistContextBuilder ctxBuilder,
                                                         RagKnowledgeService rag,
                                                         LlmAssistGateway llm,
                                                         AiAssistClient client,
                                                         GuidePackRepository guide,
                                                         ExternalGuideSearchService externalGuides) {
        return new AiAssistApplicationService(
                props, sessions, coach, quests, questConfig, activities, ctxBuilder, rag, llm,
                new AssistSafetyFilter(AssistSafetyRulesConfig::defaults),
                new AssistAuditService(), mock(MailApplicationService.class), client, guide,
                disabledCache(props),
                emptyExploreAdvisor(),
                emptyQuestGuidance(),
                emptyWorldLore(),
                emptyConversationHistory(),
                passthroughPii(),
                new AssistLocaleService(props),
                new AssistStrategyGrayService(props),
                mock(cn.itcast.demo.mylunarcore.common.BusinessMetrics.class),
                mock(cn.itcast.demo.mylunarcore.analytics.AnalyticsEventPublisher.class),
                externalGuides,
                new cn.itcast.demo.mylunarcore.assist.funnel.AssistIntentFunnelService(props, disabledCache(props)),
                new cn.itcast.demo.mylunarcore.assist.render.AssistStructuredRenderService(),
                mock(cn.itcast.demo.mylunarcore.assist.cultivation.AssistCultivationAdvisorService.class),
                mock(cn.itcast.demo.mylunarcore.assist.memory.AssistLongTermMemoryService.class));
    }

    private static ExternalGuideSearchService emptyExternalGuides() {
        ExternalGuideSearchService svc = mock(ExternalGuideSearchService.class);
        when(svc.search(anyString(), anyString())).thenReturn(List.of());
        when(svc.formatAnswerSuffix(any())).thenReturn("");
        return svc;
    }

    private static AssistConversationHistoryService emptyConversationHistory() {
        AssistConversationHistoryService history = mock(AssistConversationHistoryService.class);
        when(history.promptBlock(anyLong(), any())).thenReturn("");
        return history;
    }

    private static AssistPiiRedactor passthroughPii() {
        AssistPiiRedactor redactor = mock(AssistPiiRedactor.class);
        when(redactor.redact(any())).thenAnswer(inv -> inv.getArgument(0));
        when(redactor.redactContext(any())).thenAnswer(inv -> inv.getArgument(0));
        return redactor;
    }

    private static ExplorePathAdvisor emptyExploreAdvisor() {
        ExplorePathAdvisor advisor = mock(ExplorePathAdvisor.class);
        when(advisor.adviseFromQuestion(any())).thenReturn(Optional.empty());
        when(advisor.advise(anyInt(), anyInt())).thenReturn(
                new ExplorePathAdvisor.PathAdvice(4, "", "", List.of(), List.of(), ""));
        return advisor;
    }

    private static QuestGuidanceService emptyQuestGuidance() {
        QuestGuidanceService guidance = mock(QuestGuidanceService.class);
        when(guidance.looksLost(any())).thenReturn(false);
        return guidance;
    }

    private static WorldLoreService emptyWorldLore() {
        WorldLoreService lore = mock(WorldLoreService.class);
        when(lore.answer(any())).thenReturn(Optional.empty());
        return lore;
    }

    private static AiAssistApplicationService buildService(LunarCoreProperties props,
                                                           GuidePackRepository guidePackRepository,
                                                           AiAssistClient client,
                                                           LlmAssistGateway llm,
                                                           RagKnowledgeService rag,
                                                           PlayerCoachApplicationService coach) {
        when(coach.suggest(anyLong(), anyInt())).thenReturn(List.of());
        when(rag.search(anyString(), anyString(), anyInt())).thenReturn(List.of());
        QuestProgressApplicationService quests = mock(QuestProgressApplicationService.class);
        when(quests.list(anyInt())).thenReturn(List.of());
        AssistContextBuilder ctxBuilder = mock(AssistContextBuilder.class);
        when(ctxBuilder.build(any(), any(), any())).thenReturn(Map.of("scene", "general"));
        return newService(props, mock(GameSessionManager.class), coach, quests, mock(QuestConfigRepository.class),
                mock(ActivityQueryService.class), ctxBuilder, rag, llm, client, guidePackRepository);
    }
}
