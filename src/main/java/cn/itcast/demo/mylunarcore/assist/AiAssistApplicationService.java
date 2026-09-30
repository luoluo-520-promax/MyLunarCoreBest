package cn.itcast.demo.mylunarcore.assist;

import cn.itcast.demo.mylunarcore.analytics.AnalyticsEventPublisher;
import cn.itcast.demo.mylunarcore.assist.remote.AiAssistClient;
import cn.itcast.demo.mylunarcore.assist.remote.RemoteAssistAskRequest;
import cn.itcast.demo.mylunarcore.assist.remote.RemoteAssistAskResponse;
import cn.itcast.demo.mylunarcore.common.ActivityQueryService;
import cn.itcast.demo.mylunarcore.common.AppLogger;
import cn.itcast.demo.mylunarcore.common.BusinessMetrics;
import cn.itcast.demo.mylunarcore.common.LogCategory;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.hall.MailApplicationService;
import cn.itcast.demo.mylunarcore.model.ActivityConfig;
import cn.itcast.demo.mylunarcore.model.PlayerData;
import cn.itcast.demo.mylunarcore.model.QuestProgressEntity;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.quest.QuestConfigRepository;
import cn.itcast.demo.mylunarcore.quest.QuestProgressApplicationService;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * AI 助手应用层服务。
 *
 * <p>职责：先走本地快速判定，再按场景拼装上下文、调用远程/本地 LLM，最后统一做安全过滤、缓存和审计。</p>
 */
@Service
public class AiAssistApplicationService {
    /**
     * 日志记录器
     */
    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_ASSIST, AiAssistApplicationService.class);
    /**
     * properties：AiAssist/全局配置入口
     */
    private final LunarCoreProperties properties;
    /**
     * sessionManager：在线 GameSession 与 PlayerData
     */
    private final GameSessionManager sessionManager;
    /**
     * coachApplicationService：规则教练提示编排
     */
    private final PlayerCoachApplicationService coachApplicationService;
    /**
     * questProgressApplicationService：玩家任务进度查询
     */
    private final QuestProgressApplicationService questProgressApplicationService;
    /**
     * questConfigRepository：任务静态配置（标题等）
     */
    private final QuestConfigRepository questConfigRepository;
    /**
     * activityQueryService：当前开放活动列表
     */
    private final ActivityQueryService activityQueryService;
    /**
     * contextBuilder：拼装只读玩家上下文
     */
    private final AssistContextBuilder contextBuilder;
    /**
     * ragKnowledgeService：本地 RAG 知识检索
     */
    private final RagKnowledgeService ragKnowledgeService;
    /**
     * llmAssistGateway：直连 Chat Completions LLM
     */
    private final LlmAssistGateway llmAssistGateway;
    /**
     * safetyFilter：入站分流与出站清洗
     */
    private final AssistSafetyFilter safetyFilter;
    /**
     * auditService：问答审计与来源计数
     */
    private final AssistAuditService auditService;
    /**
     * mailApplicationService：未读邮件摘要
     */
    private final MailApplicationService mailApplicationService;
    /**
     * aiAssistClient：远程 ai-assist-service HTTP 客户端
     */
    private final AiAssistClient aiAssistClient;
    /**
     * guidePackRepository：本地攻略 FAQ 包
     */
    private final GuidePackRepository guidePackRepository;
    /**
     * answerCache：问答 Caffeine 缓存
     */
    private final AssistAnswerCache answerCache;
    /**
     * explorePathAdvisor：探索路线建议
     */
    private final ExplorePathAdvisor explorePathAdvisor;
    /**
     * questGuidanceService：任务阶梯引导
     */
    private final QuestGuidanceService questGuidanceService;
    /**
     * worldLoreService：世界观百科
     */
    private final WorldLoreService worldLoreService;
    /** 多轮对话历史。 */
    private final AssistConversationHistoryService conversationHistoryService;
    /** LLM 入参 PII 脱敏。 */
    private final AssistPiiRedactor piiRedactor;
    /** 多语言 locale 解析与免责文案。 */
    private final AssistLocaleService localeService;
    /** AI 策略灰度。 */
    private final AssistStrategyGrayService strategyGrayService;
    /** Prometheus 业务指标。 */
    private final BusinessMetrics businessMetrics;
    /** 产品埋点。 */
    private final AnalyticsEventPublisher analyticsEventPublisher;
    /** 主流平台外链/视频攻略检索。 */
    private final ExternalGuideSearchService externalGuideSearchService;
    /** 三级意图漏斗。 */
    private final cn.itcast.demo.mylunarcore.assist.funnel.AssistIntentFunnelService intentFunnelService;
    /** 结构化渲染（TEAM_CARD 等）。 */
    private final cn.itcast.demo.mylunarcore.assist.render.AssistStructuredRenderService structuredRenderService;
    /** 养成优先级顾问。 */
    private final cn.itcast.demo.mylunarcore.assist.cultivation.AssistCultivationAdvisorService cultivationAdvisorService;
    /** 玩家长期记忆。 */
    private final cn.itcast.demo.mylunarcore.assist.memory.AssistLongTermMemoryService longTermMemoryService;

    /**
     * 构造并注入依赖
     */
    public AiAssistApplicationService(LunarCoreProperties properties,
                                      GameSessionManager sessionManager,
                                      PlayerCoachApplicationService coachApplicationService,
                                      QuestProgressApplicationService questProgressApplicationService,
                                      QuestConfigRepository questConfigRepository,
                                      ActivityQueryService activityQueryService,
                                      AssistContextBuilder contextBuilder,
                                      RagKnowledgeService ragKnowledgeService,
                                      LlmAssistGateway llmAssistGateway,
                                      AssistSafetyFilter safetyFilter,
                                      AssistAuditService auditService,
                                      MailApplicationService mailApplicationService,
                                      AiAssistClient aiAssistClient,
                                      GuidePackRepository guidePackRepository,
                                      AssistAnswerCache answerCache,
                                      ExplorePathAdvisor explorePathAdvisor,
                                      QuestGuidanceService questGuidanceService,
                                      WorldLoreService worldLoreService,
                                      AssistConversationHistoryService conversationHistoryService,
                                      AssistPiiRedactor piiRedactor,
                                      AssistLocaleService localeService,
                                      AssistStrategyGrayService strategyGrayService,
                                      BusinessMetrics businessMetrics,
                                      AnalyticsEventPublisher analyticsEventPublisher,
                                      ExternalGuideSearchService externalGuideSearchService,
                                      cn.itcast.demo.mylunarcore.assist.funnel.AssistIntentFunnelService intentFunnelService,
                                      cn.itcast.demo.mylunarcore.assist.render.AssistStructuredRenderService structuredRenderService,
                                      cn.itcast.demo.mylunarcore.assist.cultivation.AssistCultivationAdvisorService cultivationAdvisorService,
                                      cn.itcast.demo.mylunarcore.assist.memory.AssistLongTermMemoryService longTermMemoryService) {
        this.properties = properties;
        this.sessionManager = sessionManager;
        this.coachApplicationService = coachApplicationService;
        this.questProgressApplicationService = questProgressApplicationService;
        this.questConfigRepository = questConfigRepository;
        this.activityQueryService = activityQueryService;
        this.contextBuilder = contextBuilder;
        this.ragKnowledgeService = ragKnowledgeService;
        this.llmAssistGateway = llmAssistGateway;
        this.safetyFilter = safetyFilter;
        this.auditService = auditService;
        this.mailApplicationService = mailApplicationService;
        this.aiAssistClient = aiAssistClient;
        this.guidePackRepository = guidePackRepository;
        this.answerCache = answerCache;
        this.explorePathAdvisor = explorePathAdvisor;
        this.questGuidanceService = questGuidanceService;
        this.worldLoreService = worldLoreService;
        this.conversationHistoryService = conversationHistoryService;
        this.piiRedactor = piiRedactor;
        this.localeService = localeService;
        this.strategyGrayService = strategyGrayService;
        this.businessMetrics = businessMetrics;
        this.analyticsEventPublisher = analyticsEventPublisher;
        this.externalGuideSearchService = externalGuideSearchService;
        this.intentFunnelService = intentFunnelService;
        this.structuredRenderService = structuredRenderService;
        this.cultivationAdvisorService = cultivationAdvisorService;
        this.longTermMemoryService = longTermMemoryService;
    }
    /**
     * 本地快速路径（兼容旧签名）。
     */
    public Optional<AssistAnswer> tryLocalFast(long uid, String question, String scene) {
        return tryLocalFast(uid, question, scene, null, null, null);
    }

    /**
     * 本地快速路径。检查开关、安全、缓存、FAQ/lore/探索/任务引导。
     */
    public Optional<AssistAnswer> tryLocalFast(long uid, String question, String scene,
                                               String sessionId, String locale, String acceptLanguage) {
        long started = System.currentTimeMillis();
        String resolvedLocale = localeService.resolveLocale(locale, acceptLanguage);
        String strategyVersion = strategyGrayService.resolveStrategyVersion(uid);
        String disclaimer = localeService.complianceDisclaimer(resolvedLocale);

        if (!properties.getAiAssist().isEnabled()) {
            return Optional.of(decorate(AssistAnswer.of(2, "", "", List.of(), List.of()),
                    disclaimer, strategyVersion, resolvedLocale));
        }
        String q = question == null ? "" : question.trim();
        if (q.isEmpty()) {
            return Optional.of(decorate(AssistAnswer.of(5, localeService.emptyQuestionReply(resolvedLocale),
                    "", List.of(), List.of()), disclaimer, strategyVersion, resolvedLocale));
        }
        AssistSafetyFilter.InboundLevel level = safetyFilter.classifyInbound(q);
        if (level == AssistSafetyFilter.InboundLevel.BLOCK) {
            AssistAnswer blocked = decorate(AssistAnswer.of(0, safetyFilter.blockedQuestionReply(),
                    "blocked", List.of(), List.of()), disclaimer, strategyVersion, resolvedLocale);
            auditService.record(uid, scene, "blocked", 0, q, blocked.answer(), elapsed(started), false, 0);
            recordOutcome(uid, scene, blocked, elapsed(started), false);
            return Optional.of(blocked);
        }
        if (level == AssistSafetyFilter.InboundLevel.SOFT_BLOCK) {
            AssistAnswer soft = decorate(AssistAnswer.of(0, safetyFilter.softBlockedQuestionReply(),
                    "blocked", List.of(), List.of()), disclaimer, strategyVersion, resolvedLocale);
            auditService.record(uid, scene, "blocked", 0, q, soft.answer(), elapsed(started), false, 0);
            recordOutcome(uid, scene, soft, elapsed(started), false);
            return Optional.of(soft);
        }

        String resolvedScene = resolveScene(scene, q);
        Optional<cn.itcast.demo.mylunarcore.assist.funnel.AssistIntentFunnelService.FunnelResult> tier1 =
                intentFunnelService.tryTier1Local(uid, q, resolvedScene, disclaimer, strategyVersion, resolvedLocale);
        if (tier1.isPresent() && tier1.get().answer().isPresent()) {
            AssistAnswer hit = decorate(tier1.get().answer().get(), disclaimer, strategyVersion, resolvedLocale)
                    .withEstimatedWait(tier1.get().estimatedWaitMs());
            auditService.record(uid, resolvedScene, hit.source(), hit.retcode(), q, hit.answer(),
                    elapsed(started), false, hit.citedConfigIds().size());
            recordOutcome(uid, resolvedScene, hit, elapsed(started), false);
            return Optional.of(hit);
        }

        Optional<AssistAnswer> cached = answerCache.get(uid, resolvedScene, q);
        if (cached.isPresent()) {
            AssistAnswer hit = decorate(cached.get(), disclaimer, strategyVersion, resolvedLocale);
            auditService.record(uid, resolvedScene, hit.source(), hit.retcode(), q, hit.answer(),
                    elapsed(started), true, hit.citedConfigIds().size());
            recordOutcome(uid, resolvedScene, hit, elapsed(started), true);
            return Optional.of(hit);
        }
        Optional<cn.itcast.demo.mylunarcore.assist.funnel.AssistIntentFunnelService.FunnelResult> tier2 =
                intentFunnelService.tryTier2Cache(uid, q, resolvedScene);
        if (tier2.isPresent() && tier2.get().answer().isPresent()) {
            AssistAnswer hit = decorate(tier2.get().answer().get(), disclaimer, strategyVersion, resolvedLocale)
                    .withEstimatedWait(tier2.get().estimatedWaitMs());
            auditService.record(uid, resolvedScene, hit.source(), hit.retcode(), q, hit.answer(),
                    elapsed(started), true, hit.citedConfigIds().size());
            recordOutcome(uid, resolvedScene, hit, elapsed(started), true);
            return Optional.of(hit);
        }
        businessMetrics.recordAiCacheHit(false);

        GameSession sessionEarly = sessionManager.getOrNull(uid);
        PlayerData playerDataEarly = sessionEarly == null ? null : sessionEarly.getPlayerData();
        if (cultivationAdvisorService.looksLikeCompareQuestion(q)) {
            var advice = cultivationAdvisorService.advise(playerDataEarly, q);
            if (advice.present()) {
                AssistAnswer hit = attachExternalGuides(
                        decorate(AssistAnswer.of(0, advice.reason(), "cultivation-advisor", List.of(),
                                        List.of("cultivation:" + advice.recommendedAvatarId()),
                                        disclaimer, strategyVersion, resolvedLocale),
                                disclaimer, strategyVersion, resolvedLocale),
                        q, "growth").withEstimatedWait(900);
                answerCache.put(uid, resolvedScene, q, hit);
                remember(uid, sessionId, q, hit);
                return Optional.of(hit);
            }
        }
        var render = structuredRenderService.tryRender(q, resolvedScene, playerDataEarly);
        if (render.present()) {
            AssistAnswer hit = decorate(
                    AssistAnswer.of(0, render.payload().textFallback(), "structured-render", List.of(),
                            List.of("render:" + render.payload().renderType()),
                            disclaimer, strategyVersion, resolvedLocale,
                            List.of(), "", true, null, "", 0,
                            intentFunnelService.estimateRemoteWaitMs(q, resolvedScene),
                            render.payload().renderType(), render.payload().payloadJson()),
                    disclaimer, strategyVersion, resolvedLocale);
            remember(uid, sessionId, q, hit);
            return Optional.of(hit);
        }

        Optional<AssistAnswer> localFaq = matchGuideFaq(q, resolvedScene);
        if (localFaq.isPresent()) {
            AssistAnswer hit = attachExternalGuides(
                    decorate(localFaq.get(), disclaimer, strategyVersion, resolvedLocale), q, resolvedScene);
            answerCache.put(uid, resolvedScene, q, hit);
            auditService.record(uid, resolvedScene, hit.source(), 0, q, hit.answer(),
                    elapsed(started), false, hit.citedConfigIds().size());
            remember(uid, sessionId, q, hit);
            recordOutcome(uid, resolvedScene, hit, elapsed(started), false);
            return Optional.of(hit);
        }

        Optional<WorldLoreService.LoreAnswer> lore = worldLoreService.answer(q);
        if (lore.isPresent()) {
            WorldLoreService.LoreAnswer la = lore.get();
            AssistAnswer hit = attachExternalGuides(
                    decorate(AssistAnswer.of(0, la.answer(), la.source(), List.of(), la.citedIds()),
                            disclaimer, strategyVersion, resolvedLocale), q, "lore");
            answerCache.put(uid, "lore", q, hit);
            auditService.record(uid, "lore", hit.source(), 0, q, hit.answer(),
                    elapsed(started), false, hit.citedConfigIds().size());
            remember(uid, sessionId, q, hit);
            recordOutcome(uid, "lore", hit, elapsed(started), false);
            return Optional.of(hit);
        }

        Optional<ExplorePathAdvisor.PathAdvice> path = explorePathAdvisor.adviseFromQuestion(q);
        if (path.isPresent() && path.get().retcode() == 0) {
            ExplorePathAdvisor.PathAdvice pa = path.get();
            AssistAnswer hit = attachExternalGuides(
                    decorate(AssistAnswer.of(0, pa.summary(), "explore-rule", List.of(),
                            List.of("route:" + pa.routeId())), disclaimer, strategyVersion, resolvedLocale),
                    q, "explore");
            auditService.record(uid, "explore", hit.source(), 0, q, hit.answer(),
                    elapsed(started), false, 1);
            remember(uid, sessionId, q, hit);
            recordOutcome(uid, "explore", hit, elapsed(started), false);
            return Optional.of(hit);
        }

        if (questGuidanceService.looksLost(q)) {
            QuestGuidanceService.Guidance g = questGuidanceService.guide(uid, q);
            AssistAnswer hit = attachExternalGuides(
                    decorate(AssistAnswer.of(0, g.message(), g.source(), List.of(
                                    new CoachHint("quest_guide_" + g.stage(), "quest", 90, g.title(), g.message(),
                                            "OPEN_QUEST", g.questId())),
                            List.of("quest-guide:" + g.questId() + ":s" + g.stage())),
                            disclaimer, strategyVersion, resolvedLocale),
                    q, "quest");
            auditService.record(uid, "quest", hit.source(), 0, q, hit.answer(),
                    elapsed(started), false, 1);
            remember(uid, sessionId, q, hit);
            recordOutcome(uid, "quest", hit, elapsed(started), false);
            return Optional.of(hit);
        }
        return Optional.empty();
    }

    /**
     * 主问答入口（兼容旧签名）。
     */
    public AssistAnswer ask(long uid, String question, String scene) {
        return ask(uid, question, scene, null, null, null);
    }

    /**
     * 主问答入口：本地快路径 → 上下文/历史/脱敏 → 远程/LLM/规则 → 审计与埋点。
     */
    public AssistAnswer ask(long uid, String question, String scene,
                            String sessionId, String locale, String acceptLanguage) {
        long started = System.currentTimeMillis();
        String resolvedLocale = localeService.resolveLocale(locale, acceptLanguage);
        String strategyVersion = strategyGrayService.resolveStrategyVersion(uid);
        String disclaimer = localeService.complianceDisclaimer(resolvedLocale);

        Optional<AssistAnswer> fast = tryLocalFast(uid, question, scene, sessionId, locale, acceptLanguage);
        if (fast.isPresent()) {
            return fast.get();
        }

        String q = question == null ? "" : question.trim();
        String resolvedScene = resolveScene(scene, q);
        try {
            List<CoachHint> related = coachApplicationService.suggest(uid, 3);
            // 结构化快捷指令：对阵容类建议补充 OPEN_LINEUP
            related = enrichStructuredActions(related, q);
            int playerId = (int) (uid & 0xffffffffL);
            List<QuestProgressEntity> quests = questProgressApplicationService.list(playerId);
            GameSession session = sessionManager.getOrNull(uid);
            PlayerData playerData = session == null ? null : session.getPlayerData();
            Map<String, Object> ctx = new LinkedHashMap<>(contextBuilder.build(playerData, quests, resolvedScene));

            String history = conversationHistoryService.promptBlock(uid, sessionId);
            if (history != null && !history.isBlank()) {
                ctx.put("conversationHistory", history);
            }
            String longTerm = longTermMemoryService.promptBlock(uid, resolvedScene);
            if (longTerm != null && !longTerm.isBlank()) {
                ctx.put("longTermMemory", longTerm);
            }
            ctx.put("strategyVersion", strategyVersion);
            ctx.put("locale", resolvedLocale);
            ctx.put("systemPrompt", strategyGrayService.systemPrompt(strategyVersion, resolvedLocale));

            if (looksLikeMailQuestion(q)) {
                ctx.put("mailSummary", mailApplicationService.summarizeUnread(playerId));
            }

            List<RagKnowledgeService.KnowledgeChunk> knowledge = ragKnowledgeService.search(q, resolvedScene, 5);
            List<String> cited = new ArrayList<>();
            for (RagKnowledgeService.KnowledgeChunk chunk : knowledge) {
                cited.add(chunk.id());
            }

            Map<String, Object> llmCtx = ctx;
            String llmQuestion = q;
            if (properties.getAiAssist().isPiiRedactionEnabled()) {
                llmQuestion = piiRedactor.redact(q);
                llmCtx = piiRedactor.redactContext(ctx);
            }

            Optional<AssistAnswer> remote = tryRemote(uid, llmQuestion, resolvedScene, llmCtx, quests, knowledge, related);
            if (remote.isPresent()) {
                AssistAnswer result = withRemoteWait(attachExternalGuides(
                        decorate(remote.get(), disclaimer, strategyVersion, resolvedLocale), q, resolvedScene),
                        q, resolvedScene);
                answerCache.put(uid, resolvedScene, q, result);
                auditService.record(uid, resolvedScene, result.source(), result.retcode(), q, result.answer(),
                        elapsed(started), false, result.citedConfigIds().size());
                remember(uid, sessionId, q, result);
                recordOutcome(uid, resolvedScene, result, elapsed(started), false);
                return result;
            }

            boolean skipLocalLlm = aiAssistClient.isRemoteEnabled()
                    && properties.getAiAssist().isPreferRemoteOnly();
            Optional<String> llmAnswer = skipLocalLlm
                    ? Optional.empty()
                    : llmAssistGateway.ask(llmQuestion, llmCtx, knowledge,
                    strategyGrayService.systemPrompt(strategyVersion, resolvedLocale));
            String answer;
            String source;
            if (llmAnswer.isPresent()) {
                answer = safetyFilter.sanitizeAnswer(llmAnswer.get(), resolvedScene);
                source = "llm";
            } else {
                answer = safetyFilter.sanitizeAnswer(buildRuleFallbackAnswer(q, related, knowledge, ctx), resolvedScene);
                source = related.isEmpty() && knowledge.isEmpty() ? "fallback" : "rule";
            }
            AssistAnswer result = withRemoteWait(attachExternalGuides(
                    decorate(AssistAnswer.of(0, answer, source, related, cited),
                            disclaimer, strategyVersion, resolvedLocale), q, resolvedScene),
                    q, resolvedScene);
            answerCache.put(uid, resolvedScene, q, result);
            auditService.record(uid, resolvedScene, source, 0, q, answer,
                    elapsed(started), false, cited.size());
            remember(uid, sessionId, q, result);
            recordOutcome(uid, resolvedScene, result, elapsed(started), false);
            return result;
        } catch (Exception e) {
            log.warn("ai assist ask failed, uid={}", uid, e);
            AssistAnswer err = decorate(AssistAnswer.of(5, localeService.unavailableReply(resolvedLocale),
                    "error", List.of(), List.of()), disclaimer, strategyVersion, resolvedLocale);
            auditService.record(uid, scene, "error", 5, q, err.answer(), elapsed(started), false, 0);
            recordOutcome(uid, scene, err, elapsed(started), false);
            return err;
        }
    }

    /** 远程大模型路径预计等待毫秒（供客户端加载动画）。 */
    public int estimateRemoteWaitMs(String question, String scene) {
        return intentFunnelService.estimateRemoteWaitMs(question, scene);
    }

    private AssistAnswer decorate(AssistAnswer answer, String disclaimer, String strategyVersion, String locale) {
        if (answer == null) {
            return AssistAnswer.of(5, localeService.unavailableReply(locale), "error", List.of(), List.of(),
                    disclaimer, strategyVersion, locale);
        }
        return answer.withMeta(disclaimer, strategyVersion, locale);
    }

    private AssistAnswer withRemoteWait(AssistAnswer answer, String question, String scene) {
        if (answer == null) {
            return null;
        }
        return answer.estimatedWaitMs() > 0
                ? answer
                : answer.withEstimatedWait(intentFunnelService.estimateRemoteWaitMs(question, scene));
    }

    /**
     * 攻略意图：先强制生成 ≤200 字摘要，链接仅作站内 WebView 深度参考。
     */
    private AssistAnswer attachExternalGuides(AssistAnswer answer, String question, String scene) {
        if (answer == null || answer.retcode() != 0) {
            return answer;
        }
        if ("blocked".equals(answer.source()) || "accepted".equals(answer.source())) {
            return answer;
        }
        List<AssistMediaLink> existing = answer.mediaLinks() == null ? List.of() : answer.mediaLinks();
        List<AssistMediaLink> links = existing;
        if (existing.isEmpty()
                && properties.getAiAssist().isExternalGuideEnabled()
                && externalGuideSearchService != null) {
            links = externalGuideSearchService.search(question, scene);
        }
        links = AssistInlineSummaryService.toInAppList(links);
        AssistInlineSummaryService.InlineSummary summary =
                new AssistInlineSummaryService().summarize(answer.answer(), question, links);
        if (links.isEmpty()) {
            return copyExtras(answer, AssistAnswer.of(answer.retcode(), answer.answer(), answer.source(),
                    answer.relatedHints(), answer.citedConfigIds(), answer.disclaimer(),
                    answer.strategyVersion(), answer.locale(), List.of(), summary.text(), true));
        }
        String body = summary.text();
        if (externalGuideSearchService != null) {
            String suffix = externalGuideSearchService.formatAnswerSuffix(links);
            if (!suffix.isBlank() && !body.contains("站内攻略浮层")) {
                body = AssistInlineSummaryService.clip(body + " " + suffix);
            }
        }
        List<String> cited = new ArrayList<>(answer.citedConfigIds() == null ? List.of() : answer.citedConfigIds());
        for (AssistMediaLink link : links) {
            if (link.curated() && link.id() != null && !link.id().isBlank()) {
                String id = "ext-guide:" + link.id();
                if (!cited.contains(id)) {
                    cited.add(id);
                }
            }
        }
        return copyExtras(answer, AssistAnswer.of(answer.retcode(), body, answer.source(), answer.relatedHints(), cited,
                answer.disclaimer(), answer.strategyVersion(), answer.locale(),
                links, summary.text(), true));
    }

    private static AssistAnswer copyExtras(AssistAnswer from, AssistAnswer to) {
        if (from == null || to == null) {
            return to;
        }
        AssistAnswer out = to;
        if (from.estimatedWaitMs() > 0) {
            out = out.withEstimatedWait(from.estimatedWaitMs());
        }
        if (from.renderType() != null && !from.renderType().isBlank()) {
            out = out.withRender(from.renderType(), from.renderPayloadJson());
        }
        if (from.suggestedAutoOverride() != null && from.suggestedAutoOverride().isPresent()) {
            out = out.withSuggestedAutoOverride(from.suggestedAutoOverride());
        }
        return out;
    }

    private void remember(long uid, String sessionId, String question, AssistAnswer answer) {
        if (answer == null || answer.retcode() != 0) {
            return;
        }
        if ("blocked".equals(answer.source()) || "accepted".equals(answer.source())) {
            return;
        }
        conversationHistoryService.append(uid, sessionId, question, answer.answer());
    }

    private void recordOutcome(long uid, String scene, AssistAnswer answer, long latencyMs, boolean cacheHit) {
        if (answer == null) {
            return;
        }
        String source = answer.source() == null ? "" : answer.source();
        if (cacheHit) {
            businessMetrics.recordAiCacheHit(true);
        }
        String metricStatus = source;
        if (source.contains("fallback")) {
            metricStatus = "fallback";
        } else if ("blocked".equals(source)) {
            metricStatus = "blocked";
        } else if ("error".equals(source)) {
            metricStatus = "error";
        } else if (answer.retcode() == 0) {
            metricStatus = "success";
        }
        businessMetrics.recordAiRequest(metricStatus, latencyMs);
        strategyGrayService.recordOutcome(answer.strategyVersion(), source);
        analyticsEventPublisher.aiAsk((int) (uid & 0xffffffffL), scene, source, cacheHit, answer.strategyVersion());
    }

    /** 对“抽谁/阵容”类问题补充可一键应用的结构化 action。 */
    private static List<CoachHint> enrichStructuredActions(List<CoachHint> related, String question) {
        if (related == null || related.isEmpty() || question == null) {
            return related == null ? List.of() : related;
        }
        String q = question.toLowerCase(Locale.ROOT);
        boolean lineupLike = q.contains("抽谁") || q.contains("阵容") || q.contains("上阵") || q.contains("配队");
        if (!lineupLike) {
            return related;
        }
        List<CoachHint> out = new ArrayList<>(related.size());
        for (CoachHint h : related) {
            if (h == null) {
                continue;
            }
            String action = h.action() == null || h.action().isBlank() ? "OPEN_LINEUP" : h.action();
            out.add(new CoachHint(h.tipId(), h.category(), h.priority(), h.title(), h.message(), action, h.refId()));
        }
        return List.copyOf(out);
    }

    /**
     * 尝试调用远程 AI 辅助
     */
    private Optional<AssistAnswer> tryRemote(long uid,
                                             String question,
                                             String scene,
                                             Map<String, Object> ctx,
                                             List<QuestProgressEntity> quests,
                                             List<RagKnowledgeService.KnowledgeChunk> knowledge,
                                             List<CoachHint> localRelated) {
        // 若远程助手客户端未开启，则直接跳过远程推理分支。
        if (!aiAssistClient.isRemoteEnabled()) {
            // 返回空，交给本地 LLM 或规则兜底。
            return Optional.empty();
        }
        // 构造远程任务快照，向 ai-assist-service 传递任务状态。
        List<RemoteAssistAskRequest.QuestSnapshot> questSnapshots = new ArrayList<>();
        // 仅当任务列表不为空时才收集快照，减少无意义对象构造。
        if (quests != null) {
            // 遍历玩家任务进度，筛出与 AI 助手相关的关键任务状态。
            for (QuestProgressEntity qe : quests) {
                // 空任务进度直接略过，避免空指针和脏数据。
                if (qe == null) {
                    continue; // 本条数据无效或不匹配，跳到下一项
                }
                // 仅保留进行中与可提交任务，因为它们最能影响辅助建议。
                if (qe.getStatus() != CoachRuleEngine.QUEST_STATUS_IN_PROGRESS
                        && qe.getStatus() != CoachRuleEngine.QUEST_STATUS_READY_SUBMIT) { // 延续上一行布尔条件
                    continue; // 本条数据无效或不匹配，跳到下一项
                }
                // 通过 questId 找配置标题，向远程端传递更可读的任务快照。
                QuestConfigRepository.QuestConfig cfg = questConfigRepository.find(qe.getQuestId());
                // 若配置缺失则回退空标题，防止远程序列化报错。
                String title = cfg == null || cfg.title() == null ? "" : cfg.title();
                // 将关键任务状态追加到远程请求中。
                questSnapshots.add(new RemoteAssistAskRequest.QuestSnapshot(qe.getQuestId(), qe.getStatus(), title));
            }
        }

        // 构造活动快照，把当前开放活动透传到远程推理层。
        List<RemoteAssistAskRequest.ActivitySnapshot> activitySnapshots = new ArrayList<>();
        // 从活动查询服务拉取当前活动列表，向远程提供时效性上下文。
        for (ActivityConfig a : activityQueryService.listCurrentlyActiveConfigs()) {
            // 空活动对象直接跳过，避免传播无效记录。
            if (a == null) {
                continue; // 本条数据无效或不匹配，跳到下一项
            }
            // 将活动 id、名称、结束时间和描述封装成快照对象。
            activitySnapshots.add(new RemoteAssistAskRequest.ActivitySnapshot(
                    a.getActivityId(), // 续写 tryRemote 的参数列表
                    a.getName(), // 续写 tryRemote 的参数列表
                    a.getEndTime(), // 续写 tryRemote 的参数列表
                    a.getDescription() // 续写构造参数：取出关联业务字段
            )); // 结束多行构造或方法调用
        }

        // 构造知识提示，把 RAG 检索到的知识片段传给远程服务。
        List<RemoteAssistAskRequest.KnowledgeHint> knowledgeHints = new ArrayList<>();
        // 仅当检索结果不为空时才创建远程知识提示。
        if (knowledge != null) {
            // 遍历知识片段，向远程端传递 id、type 与 text。
            for (RagKnowledgeService.KnowledgeChunk chunk : knowledge) {
                // 知识片段直接映射为远程提示对象。
                knowledgeHints.add(new RemoteAssistAskRequest.KnowledgeHint(chunk.id(), chunk.type(), chunk.text()));
            }
        }

        // 组装远程问答请求，包含上下文、任务、活动和知识提示。
        RemoteAssistAskRequest request = new RemoteAssistAskRequest(
                uid, question, scene, ctx, questSnapshots, activitySnapshots, knowledgeHints); // 续写远程 ask 请求的上下文与知识参数
        // 调用远程 ai-assist-service 获取回答。
        Optional<RemoteAssistAskResponse> remote = aiAssistClient.ask(request);
        // 远程未返回时，交由本地路径处理。
        if (remote.isEmpty()) {
            return Optional.empty(); // 无命中结果，向上层返回空以便继续降级
        }
        // 取出远程响应对象，继续做安全净化和封装。
        RemoteAssistAskResponse rsp = remote.get();
        // 远程 hint 需要转换成本地 CoachHint 结构。
        List<CoachHint> hints = new ArrayList<>();
        // 如果远程返回了相关提示，则按原样转换。
        if (rsp.relatedHints() != null && !rsp.relatedHints().isEmpty()) {
            // 遍历远程返回的提示列表，保留 tipId、category、priority 等字段。
            for (RemoteAssistAskResponse.Hint h : rsp.relatedHints()) {
                // 远程提示对象允许空值，空值则跳过。
                if (h == null) {
                    continue; // 本条数据无效或不匹配，跳到下一项
                }
                // 将远程提示转成本地 CoachHint，并补空字段。
                hints.add(new CoachHint(
                        nullToEmpty(h.tipId()), // 续写 tryRemote 的参数列表
                        nullToEmpty(h.category()), // 续写 tryRemote 的参数列表
                        h.priority(), // 续写 tryRemote 的参数列表
                        nullToEmpty(h.title()), // 续写 tryRemote 的参数列表
                        nullToEmpty(h.message()), // 续写 tryRemote 的参数列表
                        nullToEmpty(h.action()), // 续写 tryRemote 的参数列表
                        h.refId() // 续写构造参数：取出关联业务字段
                )); // 结束多行构造或方法调用
            }
        } else { // 条件不成立时的替代分支
            // 若远程没给提示，则退回本地建议列表，避免 hint 为空。
            hints.addAll(localRelated);
        }
        // 对远程 answer 做安全净化，避免远端生成越权内容。
        String answer = safetyFilter.sanitizeAnswer(rsp.answer(), scene);
        // 若远程 source 为空，则统一写 remote，否则在前面加 remote- 前缀。
        String source = rsp.source() == null || rsp.source().isBlank() ? "remote" : "remote-" + rsp.source();
        // retcode 为 0 表示远程成功，否则透传远程错误码。
        return Optional.of(AssistAnswer.of(
                rsp.retcode() == 0 ? 0 : rsp.retcode(), // 续写 tryRemote 的参数列表
                answer, // 续写 tryRemote 的参数列表
                source, // 续写 tryRemote 的参数列表
                hints, // 续写 tryRemote 的参数列表
                rsp.citedConfigIds() == null ? List.of() : rsp.citedConfigIds() // 远程引用 ID 空则用空列表
        )); // 结束多行构造或方法调用
    }
    /**
     * 匹配本地攻略 FAQ
     */
    private Optional<AssistAnswer> matchGuideFaq(String question, String scene) {
        // 读取当前 guide pack 快照，用于本地 FAQ 比对。
        GuidePackConfig pack = guidePackRepository.current();
        // 若 guide pack 为空或没有 FAQ，直接返回空。
        if (pack == null || pack.faqs().isEmpty()) {
            return Optional.empty(); // 无命中结果，向上层返回空以便继续降级
        }
        // 把问题转为小写，减少大小写差异对命中率的影响。
        String q = question.toLowerCase(Locale.ROOT);
        // 为问题提取 token，方便与 FAQ 语义重叠做粗匹配。
        Set<String> qTokens = RagKnowledgeService.tokenize(question);
        // best 保存当前得分最高的 FAQ 项。
        GuidePackConfig.FaqEntry best = null;
        // bestScore 记录最佳 FAQ 的综合命中分。
        double bestScore = 0;
        // 遍历所有 FAQ，根据正文、关键词、场景和优先级综合打分。
        for (GuidePackConfig.FaqEntry faq : pack.faqs()) {
            // FAQ 记录或问题文本为空时直接跳过。
            if (faq == null || faq.question() == null) {
                continue; // 本条数据无效或不匹配，跳到下一项
            }
            // 初始化单条 FAQ 的匹配分。
            double score = 0;
            // FAQ 问句也统一小写，便于包含关系判断。
            String fq = faq.question().toLowerCase(Locale.ROOT);
            // 问题包含 FAQ 或 FAQ 包含问题时，视为强相关命中。
            if (q.contains(fq) || fq.contains(q)) {
                score += 5; // 累加评分或计数：score += 5;
            }
            // 以空白和中文句号切分 FAQ 文本，找更短的关键术语。
            for (String token : fq.split("[\\s。]+")) {
                // 长度至少 2 的 token 才算有效业务关键词。
                if (token.length() >= 2 && q.contains(token)) {
                    score += 1; // 累加评分或计数：score += 1;
                }
            }
            // FAQ 配置中的 keywords 代表人工维护的显式业务标签。
            if (faq.keywords() != null) {
                // 遍历每个关键词，增加场景性权重。
                for (String kw : faq.keywords()) {
                    // 关键词非空且至少 2 个字符时才参与匹配。
                    if (kw != null && kw.length() >= 2 && q.contains(kw.toLowerCase(Locale.ROOT))) {
                        score += 2; // 累加评分或计数：score += 2;
                    }
                }
            }
            // 再次用 tokenizer 比较 question 与 FAQ 问句的 token 交集。
            for (String token : RagKnowledgeService.tokenize(faq.question())) {
                // 问题中出现同 token 则认为是较强语义对齐。
                if (qTokens.contains(token)) {
                    score += token.length() >= 2 ? 1.5 : 0.5; // 在 matchGuideFaq 中调用：score += token.length() >= 2 ? 1.5 : 0.5;
                }
            }
            // 场景一致时给额外加分，提升场景化 FAQ 的优先级。
            if (scene != null && !scene.isBlank() && scene.equalsIgnoreCase(nullToEmpty(faq.scene()))) {
                score += 2; // 累加评分或计数：score += 2;
            }
            // FAQ 的 priority 作为人工兜底权重，乘以 0.1 参与评分。
            score += Math.max(0, faq.priority() == null ? 0 : faq.priority()) * 0.1; // 累加评分或计数：score += Math.max(0, faq.priority() == null ? 0 : faq.priority()) * 0.1;
            // 只保留分数最高的 FAQ，作为最终命中项。
            if (score > bestScore) {
                bestScore = score; // 赋值 bestScore，供 matchGuideFaq 使用
                best = faq; // 赋值 best，供 matchGuideFaq 使用
            }
        }
        // 低分或无答案的 FAQ 不返回，避免误命中。
        if (best == null || bestScore < 2 || best.answer() == null || best.answer().isBlank()) {
            return Optional.empty(); // 无命中结果，向上层返回空以便继续降级
        }
        // 将 FAQ 命中包装成标准 AssistAnswer，并引用 guide:xxx 编号。
        return Optional.of(AssistAnswer.of(0, best.answer(), "guide-local", List.of(),
                List.of("guide:" + (best.id() == null ? "faq" : best.id())))); // 续写 matchGuideFaq 的参数列表
    }
    /**
     * 判断是否像邮件相关提问
     */
    private static boolean looksLikeMailQuestion(String q) {
        // 转小写后做关键词检测，覆盖中英文邮件问法。
        String lower = q.toLowerCase(Locale.ROOT);
        // 只要命中邮件、邮箱或 mail 词根，就认为该问题与邮件相关。
        return lower.contains("邮件") || lower.contains("邮箱") || lower.contains("mail");
    }
    /**
     * 将显式 scene 或问句关键词归一为业务场景名
     */
    static String resolveScene(String scene, String question) {
        // 外部明确传入 scene 时优先采用，避免问句误判。
        if (scene != null && !scene.isBlank()) {
            // 将 scene 归一为小写，保证缓存键稳定。
            return scene.trim().toLowerCase(Locale.ROOT);
        }
        // 没有显式 scene 时，使用问句内容推断业务场景。
        String q = question.toLowerCase(Locale.ROOT);
        // 抽卡相关词汇映射为 gacha 场景。
        if (q.contains("抽卡") || q.contains("卡池") || q.contains("保底") || q.contains("gacha")) {
            return "gacha"; // 返回："gacha"
        }
        // 任务相关词汇映射为 quest 场景。
        if (q.contains("任务") || q.contains("主线") || q.contains("quest")) {
            return "quest"; // 返回："quest"
        }
        // 活动相关词汇映射为 activity 场景。
        if (q.contains("活动") || q.contains("activity")) {
            return "activity"; // 返回："activity"
        }
        // 养成、突破、天赋和加点映射为 growth 场景。
        if (q.contains("养成") || q.contains("突破") || q.contains("天赋") || q.contains("加点")) {
            return "growth"; // 返回："growth"
        }
        // 商店、购买相关词汇映射为 shop 场景。
        if (q.contains("商店") || q.contains("买") || q.contains("shop")) {
            return "shop"; // 返回："shop"
        }
        // 邮件相关词汇映射为 mail 场景。
        if (q.contains("邮件") || q.contains("mail")) {
            return "mail"; // 返回："mail"
        }
        // 使用率、热门、出场相关词汇映射为 meta 场景。
        if (q.contains("使用率") || q.contains("热门") || q.contains("出场") || q.contains("popular") || q.contains("meta")) {
            return "meta"; // 返回："meta"
        }
        // 世界观、历史和背景类词汇映射为 lore 场景。
        if (q.contains("历史") || q.contains("势力") || q.contains("世界观") || q.contains("百科") || q.contains("背景")) {
            return "lore"; // 返回："lore"
        }
        // 路线、导航和路径映射为 explore 场景。
        if (q.contains("路线") || q.contains("导航") || q.contains("怎么走") || q.contains("路径")) {
            return "explore"; // 返回："explore"
        }
        // 设置与客服：命中后走 GuidePack 中 scene=settings 的 FAQ（画面/音量/按键/工单指引）。
        if (q.contains("设置") || q.contains("画面") || q.contains("画质") || q.contains("音量")
                || q.contains("声音") || q.contains("按键") || q.contains("键位") || q.contains("灵敏度")
                || q.contains("客服") || q.contains("settings") || q.contains("keybind") || q.contains("volume")) {
            return "settings"; // 返回设置场景，供本地 FAQ 与检索加权
        }
        // 兜底为 general，供通用问答与检索使用。
        return "general";
    }
    /**
     * 构建规则兜底回答
     */
    private static String buildRuleFallbackAnswer(String question,
                                                  List<CoachHint> related,
                                                  List<RagKnowledgeService.KnowledgeChunk> knowledge,
                                                  Map<String, Object> ctx) {
        // 用 StringBuilder 组合规则兜底答案，避免频繁字符串拼接。
        StringBuilder sb = new StringBuilder();
        // 从上下文中提取邮件摘要，若存在则优先展示给用户。
        Object mailSummary = ctx == null ? null : ctx.get("mailSummary");
        if (mailSummary instanceof String ms && !ms.isBlank()) {
            sb.append(ms);
        }
        Object personalization = ctx == null ? null : ctx.get("personalizationHint");
        if (personalization instanceof String ph && !ph.isBlank()) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append("个性化：").append(ph).append('。');
        }
        Object history = ctx == null ? null : ctx.get("conversationHistory");
        if (history instanceof String hist && !hist.isBlank()) {
            if (!sb.isEmpty()) {
                sb.append(' ');
            }
            sb.append("结合上文：").append(truncate(hist, 120));
        }
        // 从上下文中提取监测摘要，补充活动或系统状态信息。
        Object monitoring = ctx == null ? null : ctx.get("monitoringSummary");
        // 若监测摘要存在，则以“监测：”前缀写入答案。
        if (monitoring instanceof String summary && !summary.isBlank()) {
            // 在已有内容后补空格，避免句子连写。
            if (!sb.isEmpty()) {
                sb.append(' '); // 在 buildRuleFallbackAnswer 中调用：sb.append(' ');
            }
            // 监测摘要用于表现活动、系统或场景运行状态。
            sb.append("监测：").append(summary).append('。');
        }
        // 若有本地教练提示，则把优先建议合并进规则答案。
        if (related != null && !related.isEmpty()) {
            // 在已有内容后补空格，保证段落分隔清晰。
            if (!sb.isEmpty()) {
                sb.append(' '); // 在 buildRuleFallbackAnswer 中调用：sb.append(' ');
            }
            // 教练提示用于回答“先做什么”这种行动性问题。
            sb.append("根据当前进度，建议优先：");
            // 遍历相关提示，按顺序输出标题和说明。
            for (int i = 0; i < related.size(); i++) {
                // 取当前提示，按照优先级已由上层排序。
                CoachHint h = related.get(i);
                // 多条提示之间用中文分号分隔，增强可读性。
                if (i > 0) {
                    sb.append('；'); // 在 buildRuleFallbackAnswer 中调用：sb.append('；');
                }
                // 标题与消息共同表达该提示的业务含义。
                sb.append(h.title()).append(" — ").append(h.message());
            }
        }
        // 若有知识片段，则把前三条最相关配置摘要拼入回答。
        if (knowledge != null && !knowledge.isEmpty()) {
            // 在已有答案后增加一个段落空格。
            if (!sb.isEmpty()) {
                sb.append(' '); // 在 buildRuleFallbackAnswer 中调用：sb.append(' ');
            }
            // knowledge 段用于展示命中的配置 id 和短文本。
            sb.append("相关配置：");
            // 只展示前三条，避免回答过长。
            for (int i = 0; i < Math.min(3, knowledge.size()); i++) {
                // 取出当前知识片段，作为配置引用依据。
                RagKnowledgeService.KnowledgeChunk c = knowledge.get(i);
                // 多条配置之间用分号分隔，帮助用户区分来源。
                if (i > 0) {
                    sb.append('；'); // 在 buildRuleFallbackAnswer 中调用：sb.append('；');
                }
                // 以 id + 截断文本的形式展示引用。
                sb.append('[').append(c.id()).append(']').append(truncate(c.text(), 60));
            }
        }
        // 当没有任何本地信息可用时，给出通用兜底文案。
        if (sb.isEmpty()) {
            // 该文案引导用户打开任务或活动界面，减少纯空回复。
            return "暂未匹配到具体建议。你可以打开任务或活动界面查看当前目标。（问题：" + question + "）";
        }
        // 返回组装后的规则兜底答案。
        return sb.toString();
    }
    /**
     * 计算耗时毫秒
     */
    private static long elapsed(long started) {
        // 以毫秒差计算耗时，负值做 0 保护。
        return Math.max(0L, System.currentTimeMillis() - started);
    }
    /**
     * 截断过长文本
     */
    private static String truncate(String s, int max) {
        // 空字符串保持为空，避免截断时抛空指针。
        if (s == null) {
            return ""; // 返回：""
        }
        // 字符串未超过上限则原样返回。
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
    /**
     * 将 null 转为空字符串
     */
    private static String nullToEmpty(String s) {
        // 工具方法把 null 统一映射为空串，便于协议序列化。
        return s == null ? "" : s;
    }
}
