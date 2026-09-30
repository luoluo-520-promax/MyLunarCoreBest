package cn.itcast.demo.mylunarcore.assist;

import cn.itcast.demo.mylunarcore.common.AppLogger;
import cn.itcast.demo.mylunarcore.common.LogCategory;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.player.PlayerContextResolver;
import cn.itcast.demo.mylunarcore.protocol.AssistSystemProto;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

/**
 * AI 助手 Netty 协调服务。
 *
 * <p>负责把协议层请求转成应用层调用，处理配额、异步推理、通知回推和错误码映射。</p>
 */
@Service
public class AssistNettyService {
    /**
     * 日志记录器
     */
    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_ASSIST, AssistNettyService.class);
    /**
     * properties：AiAssist/全局配置入口
     */
    private final LunarCoreProperties properties;
    /**
     * contextResolver：从 Channel 解析登录 uid
     */
    private final PlayerContextResolver contextResolver;
    /**
     * quotaLimiter：coach/llm/daily 令牌桶限流
     */
    private final AssistQuotaLimiter quotaLimiter;
    /**
     * coachApplicationService：规则教练提示编排
     */
    private final PlayerCoachApplicationService coachApplicationService;
    /**
     * aiAssistApplicationService：本类持有的 AiAssistApplicationService 状态/依赖
     */
    private final AiAssistApplicationService aiAssistApplicationService;
    /**
     * guidePackRepository：本地攻略 FAQ 包
     */
    private final GuidePackRepository guidePackRepository;
    /**
     * auditService：问答审计与来源计数
     */
    private final AssistAuditService auditService;
    /**
     * feedbackService：有用/无用反馈统计
     */
    private final AssistFeedbackService feedbackService;
    /**
     * lineupRecommendService：已拥有角色阵容推荐
     */
    private final OwnedLineupRecommendService lineupRecommendService;
    /**
     * explorePathAdvisor：探索路线建议
     */
    private final ExplorePathAdvisor explorePathAdvisor;
    /**
     * questGuidanceService：任务阶梯引导
     */
    private final QuestGuidanceService questGuidanceService;
    private final ExecutorService assistInferenceExecutor;
    private final GameSessionManager sessionManager;
    private final AssistProactiveCoachService proactiveCoachService;
    private final org.springframework.beans.factory.ObjectProvider<IntentExecuteHandler> intentExecuteProvider;
    private final org.springframework.beans.factory.ObjectProvider<AssistPersonaService> personaProvider;
    private final org.springframework.beans.factory.ObjectProvider<AssistTtsService> ttsProvider;
    private final org.springframework.beans.factory.ObjectProvider<VisualAssistService> visualAssistProvider;
    private final org.springframework.beans.factory.ObjectProvider<AssistBattleRlAdvisor> battleRlProvider;

    public AssistNettyService(LunarCoreProperties properties,
                              PlayerContextResolver contextResolver,
                              AssistQuotaLimiter quotaLimiter,
                              PlayerCoachApplicationService coachApplicationService,
                              AiAssistApplicationService aiAssistApplicationService,
                              GuidePackRepository guidePackRepository,
                              AssistAuditService auditService,
                              AssistFeedbackService feedbackService,
                              OwnedLineupRecommendService lineupRecommendService,
                              ExplorePathAdvisor explorePathAdvisor,
                              QuestGuidanceService questGuidanceService,
                              @Qualifier("assistInferenceExecutor") ExecutorService assistInferenceExecutor,
                              GameSessionManager sessionManager,
                              AssistProactiveCoachService proactiveCoachService) {
        this(properties, contextResolver, quotaLimiter, coachApplicationService, aiAssistApplicationService,
                guidePackRepository, auditService, feedbackService, lineupRecommendService, explorePathAdvisor,
                questGuidanceService, assistInferenceExecutor, sessionManager, proactiveCoachService,
                null, null, null, null, null);
    }

    /** 兼容旧单测：不含 LocalRL advisor。 */
    public AssistNettyService(LunarCoreProperties properties,
                              PlayerContextResolver contextResolver,
                              AssistQuotaLimiter quotaLimiter,
                              PlayerCoachApplicationService coachApplicationService,
                              AiAssistApplicationService aiAssistApplicationService,
                              GuidePackRepository guidePackRepository,
                              AssistAuditService auditService,
                              AssistFeedbackService feedbackService,
                              OwnedLineupRecommendService lineupRecommendService,
                              ExplorePathAdvisor explorePathAdvisor,
                              QuestGuidanceService questGuidanceService,
                              @Qualifier("assistInferenceExecutor") ExecutorService assistInferenceExecutor,
                              GameSessionManager sessionManager,
                              AssistProactiveCoachService proactiveCoachService,
                              org.springframework.beans.factory.ObjectProvider<IntentExecuteHandler> intentExecuteProvider,
                              org.springframework.beans.factory.ObjectProvider<AssistPersonaService> personaProvider,
                              org.springframework.beans.factory.ObjectProvider<AssistTtsService> ttsProvider,
                              org.springframework.beans.factory.ObjectProvider<VisualAssistService> visualAssistProvider) {
        this(properties, contextResolver, quotaLimiter, coachApplicationService, aiAssistApplicationService,
                guidePackRepository, auditService, feedbackService, lineupRecommendService, explorePathAdvisor,
                questGuidanceService, assistInferenceExecutor, sessionManager, proactiveCoachService,
                intentExecuteProvider, personaProvider, ttsProvider, visualAssistProvider, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AssistNettyService(LunarCoreProperties properties,
                              PlayerContextResolver contextResolver,
                              AssistQuotaLimiter quotaLimiter,
                              PlayerCoachApplicationService coachApplicationService,
                              AiAssistApplicationService aiAssistApplicationService,
                              GuidePackRepository guidePackRepository,
                              AssistAuditService auditService,
                              AssistFeedbackService feedbackService,
                              OwnedLineupRecommendService lineupRecommendService,
                              ExplorePathAdvisor explorePathAdvisor,
                              QuestGuidanceService questGuidanceService,
                              @Qualifier("assistInferenceExecutor") ExecutorService assistInferenceExecutor,
                              GameSessionManager sessionManager,
                              AssistProactiveCoachService proactiveCoachService,
                              org.springframework.beans.factory.ObjectProvider<IntentExecuteHandler> intentExecuteProvider,
                              org.springframework.beans.factory.ObjectProvider<AssistPersonaService> personaProvider,
                              org.springframework.beans.factory.ObjectProvider<AssistTtsService> ttsProvider,
                              org.springframework.beans.factory.ObjectProvider<VisualAssistService> visualAssistProvider,
                              org.springframework.beans.factory.ObjectProvider<AssistBattleRlAdvisor> battleRlProvider) {
        this.properties = properties;
        this.contextResolver = contextResolver;
        this.quotaLimiter = quotaLimiter;
        this.coachApplicationService = coachApplicationService;
        this.aiAssistApplicationService = aiAssistApplicationService;
        this.guidePackRepository = guidePackRepository;
        this.auditService = auditService;
        this.feedbackService = feedbackService;
        this.lineupRecommendService = lineupRecommendService;
        this.explorePathAdvisor = explorePathAdvisor;
        this.questGuidanceService = questGuidanceService;
        this.assistInferenceExecutor = assistInferenceExecutor;
        this.sessionManager = sessionManager;
        this.proactiveCoachService = proactiveCoachService;
        this.intentExecuteProvider = intentExecuteProvider;
        this.personaProvider = personaProvider;
        this.ttsProvider = ttsProvider;
        this.visualAssistProvider = visualAssistProvider;
        this.battleRlProvider = battleRlProvider;
    }
    /** 场景移动钩子：驱动 BOSS 门口徘徊等主动教练。 */
    public void onSceneMove(long uid, int planeId, float x, float y, float z) {
        if (proactiveCoachService != null) {
            proactiveCoachService.onPlayerMove(uid, planeId, x, y, z);
        }
    }

    /**
     * 处理教练提示请求
     */
    public AssistSystemProto.AskCoachHintScRsp handleAskCoachHint(AssistSystemProto.AskCoachHintCsReq req, Channel channel) {
        // AI 助手或教练规则总开关关闭时，直接返回 retcode=2 表示功能未启用。
        if (!properties.getAiAssist().isEnabled() || !properties.getAiAssist().isRuleCoachEnabled()) {
            return AssistSystemProto.AskCoachHintScRsp.newBuilder().setRetcode(2).build(); // 组装并返回协议响应：AssistSystemProto.AskCoachHintScRsp.newBuilder().setRet
        }
        // 从 channel 中解析 uid，无法解析则说明会话不存在或已失效。
        var uidOpt = contextResolver.resolveUid(channel);
        // 没有 uid 时返回 retcode=1，表示登录态缺失。
        if (uidOpt.isEmpty()) {
            return AssistSystemProto.AskCoachHintScRsp.newBuilder().setRetcode(1).build(); // 组装并返回协议响应：AssistSystemProto.AskCoachHintScRsp.newBuilder().setRet
        }
        // 取得玩家 uid，后续配额和审计都以它为主键。
        long uid = uidOpt.getAsLong();
        // coach 配额用于控制规则提示类接口的调用频次。
        AssistQuotaLimiter.AcquireResult acquired = quotaLimiter.tryAcquire(uid, AssistQuotaLimiter.QuotaKind.COACH);
        // 未通过配额时直接拒绝，并记录限流审计。
        if (!acquired.allowed()) {
            // 记录 coach 类请求被限流，便于监控接口压力。
            auditService.recordQuotaReject(uid, "coach");
            // retcode=3 表示触发频控或配额拒绝。
            return AssistSystemProto.AskCoachHintScRsp.newBuilder().setRetcode(3).build();
        }
        try { // 包裹可能失败的外部/IO/推理调用
            // 调用教练建议服务，按照 req.getMaxHints() 限制返回条数。
            List<CoachHint> hints = coachApplicationService.suggest(uid, req.getMaxHints());
            // 构造 proto 响应，并先写入成功 retcode=0。
            AssistSystemProto.AskCoachHintScRsp.Builder builder = AssistSystemProto.AskCoachHintScRsp.newBuilder().setRetcode(0);
            // 把领域层 CoachHint 转成协议层对象再追加到响应里。
            for (CoachHint hint : hints) {
                builder.addHints(toProto(hint)); // 完成 handleAskCoachHint 的参数传入并结束语句
            }
            // 返回封装完成的 coach hint 响应。
            return builder.build();
        } catch (Exception e) { // 捕获异常后降级：记日志并返回错误或空结果
            // 记录 coach 接口异常，方便定位规则引擎或配置异常。
            log.warn("askCoachHint failed, uid={}", uid, e);
            // retcode=5 表示服务端异常或不可用。
            return AssistSystemProto.AskCoachHintScRsp.newBuilder().setRetcode(5).build();
        }
    }
    /**
     * 处理 AI 助手问答请求
     */
    public AssistSystemProto.AskAiAssistScRsp handleAskAiAssist(AssistSystemProto.AskAiAssistCsReq req, Channel channel) {
        if (!properties.getAiAssist().isEnabled()) {
            return AssistSystemProto.AskAiAssistScRsp.newBuilder().setRetcode(2).build();
        }
        var uidOpt = contextResolver.resolveUid(channel);
        if (uidOpt.isEmpty()) {
            return AssistSystemProto.AskAiAssistScRsp.newBuilder().setRetcode(1).build();
        }
        long uid = uidOpt.getAsLong();
        String question = req.getQuestion();
        String scene = req.getScene();
        String sessionId = req.getSessionId();
        String locale = req.getLocale();
        boolean voiceInput = req.getInputType() == AssistSystemProto.AssistInputType.ASSIST_INPUT_VOICE;
        int inputTypeValue = req.getInputTypeValue();

        IntentExecuteHandler intentHandler =
                intentExecuteProvider == null ? null : intentExecuteProvider.getIfAvailable();
        if (intentHandler != null) {
            Optional<IntentExecuteHandler.ExecuteResult> intent = intentHandler.tryExecute(uid, question);
            if (intent.isPresent() && intent.get().kind() != IntentExecuteHandler.IntentKind.NONE) {
                IntentExecuteHandler.ExecuteResult er = intent.get();
                intentHandler.pushToChannel(channel, er);
                AssistAnswer decorated = decoratePersonaAndAuto(
                        AssistAnswer.of(0, er.answer(), "intent-execute", List.of(),
                                List.of("intent:" + er.kind().name())),
                        uid, question, scene, inputTypeValue);
                return toAskRsp(decorated);
            }
        }

        AssistBattleRlAdvisor battleRl = battleRlProvider == null ? null : battleRlProvider.getIfAvailable();
        if (battleRl != null) {
            String battleCtx = req.getBattleContextJson();
            Optional<AssistAnswer> rl = battleRl.tryAdvise(uid, question, scene, battleCtx);
            if (rl.isPresent()) {
                return toAskRsp(decoratePersonaAndAuto(rl.get(), uid, question, scene, inputTypeValue));
            }
        }

        Optional<AssistAnswer> fast = aiAssistApplicationService.tryLocalFast(
                uid, question, scene, sessionId, locale, null);
        if (fast.isPresent()) {
            return toAskRsp(decoratePersonaAndAuto(fast.get(), uid, question, scene, inputTypeValue));
        }

        int playerLevel = resolvePlayerLevel(uid);
        AssistQuotaLimiter.AcquireResult sameQ = quotaLimiter.tryAcquireSameQuestion(
                uid, AssistAnswerCache.normalizeQuestion(question));
        if (!sameQ.allowed()) {
            auditService.recordQuotaReject(uid, voiceInput ? "llm-dup-voice" : "llm-dup");
            return AssistSystemProto.AskAiAssistScRsp.newBuilder().setRetcode(3).build();
        }
        AssistQuotaLimiter.AcquireResult acquired = quotaLimiter.tryAcquire(
                uid, AssistQuotaLimiter.QuotaKind.LLM, playerLevel, voiceInput);
        if (!acquired.allowed()) {
            auditService.recordQuotaReject(uid, voiceInput ? "llm-voice" : "llm");
            return AssistSystemProto.AskAiAssistScRsp.newBuilder().setRetcode(3).build();
        }
        if (voiceInput) {
            auditService.record(uid, scene, "voice-input", 0, question, "quota_discounted=" + properties.getAiAssist().isVoiceQuotaDiscountEnabled());
            pushVoiceUsage(channel, "voice-" + System.currentTimeMillis(), true);
        }

        try {
            proactiveCoachService.evaluateIdleTriggers(uid);
        } catch (Exception e) {
            log.debug("proactive evaluate skipped: {}", e.toString());
        }

        if (properties.getAiAssist().isSyncMode()) {
            return toAskRsp(decoratePersonaAndAuto(
                    aiAssistApplicationService.ask(uid, question, scene, sessionId, locale, null),
                    uid, question, scene, inputTypeValue));
        }

        String requestId = UUID.randomUUID().toString();
        try {
            assistInferenceExecutor.execute(() -> runInferenceAndNotify(
                    uid, channel, requestId, question, scene, sessionId, locale, inputTypeValue));
        } catch (RejectedExecutionException ex) {
            log.warn("assist inference rejected, uid={}, fallback sync rule path", uid);
            AssistAnswer fallback = decoratePersonaAndAuto(
                    aiAssistApplicationService.ask(uid, question, scene, sessionId, locale, null),
                    uid, question, scene, inputTypeValue);
            if (fallback.retcode() == 0 && !"llm".equals(fallback.source()) && !fallback.source().startsWith("remote")) {
                return toAskRsp(fallback);
            }
            return AssistSystemProto.AskAiAssistScRsp.newBuilder()
                    .setRetcode(4)
                    .setAnswer(fallback.answer())
                    .setSource(fallback.source().isBlank() ? "fallback" : fallback.source())
                    .setDisclaimer(nullToEmpty(fallback.disclaimer()))
                    .setStrategyVersion(nullToEmpty(fallback.strategyVersion()))
                    .setLocale(nullToEmpty(fallback.locale()))
                    .setRequestId(requestId)
                    .setInputTypeValue(inputTypeValue)
                    .setPersonaId(nullToEmpty(fallback.personaId()))
                    .build();
        }
        return AssistSystemProto.AskAiAssistScRsp.newBuilder()
                .setRetcode(0)
                .setAnswer("accepted")
                .setSource("accepted")
                .setRequestId(requestId)
                .setInputTypeValue(inputTypeValue)
                .setEstimatedWaitMs(aiAssistApplicationService.estimateRemoteWaitMs(question, scene))
                .build();
    }

    /** 截图 VLM 入口。 */
    public AssistSystemProto.UploadScreenshotScRsp handleUploadScreenshot(
            AssistSystemProto.UploadScreenshotCsReq req, Channel channel) {
        VisualAssistService visual = visualAssistProvider == null ? null : visualAssistProvider.getIfAvailable();
        if (visual == null) {
            return AssistSystemProto.UploadScreenshotScRsp.newBuilder().setRetcode(2).build();
        }
        return visual.handleUpload(req, channel);
    }

    private AssistAnswer decoratePersonaAndAuto(AssistAnswer answer, long uid, String question,
                                                String scene, int inputTypeValue) {
        if (answer == null) {
            return AssistAnswer.of(5, "", "error", List.of(), List.of());
        }
        AssistAnswer out = answer.withInputType(inputTypeValue);
        AssistPersonaService persona = personaProvider == null ? null : personaProvider.getIfAvailable();
        if (persona != null) {
            out = out.withAnswer(persona.wrapAnswer(uid, out.answer())).withPersona(persona.personaId(uid));
        }
        SuggestedAutoOverride override = out.suggestedAutoOverride();
        if (override == null || !override.isPresent()) {
            if (scene != null && scene.toLowerCase().contains("battle")) {
                override = AiAutoSuggestionFactory.fromQuestion(question);
            } else {
                override = AiAutoSuggestionFactory.fromQuestion(question);
            }
            if (override != null && override.isPresent()) {
                out = out.withSuggestedAutoOverride(override);
            }
        }
        return out;
    }

    private void pushVoiceUsage(Channel channel, String requestId, boolean discounted) {
        if (channel == null || !channel.isActive()) {
            return;
        }
        AssistSystemProto.AssistVoiceUsageScNotify notify =
                AssistSystemProto.AssistVoiceUsageScNotify.newBuilder()
                        .setRequestId(requestId)
                        .setQuotaDiscounted(discounted)
                        .setRemainingVoiceBonus(discounted ? 1 : 0)
                        .build();
        channel.writeAndFlush(new GamePacket(CmdIds.ASSIST_VOICE_USAGE_SC_NOTIFY, notify.toByteArray()));
    }

    private int resolvePlayerLevel(long uid) {
        GameSession gs = sessionManager.getOrNull(uid);
        if (gs != null && gs.getPlayerData() != null && gs.getPlayerData().getPlayer() != null) {
            return gs.getPlayerData().getPlayer().getLevel();
        }
        return gs == null ? 0 : gs.getLevel();
    }
    /**
     * 异步推理完成后回推通知
     */
    private void runInferenceAndNotify(long uid, Channel channel, String requestId, String question, String scene) {
        runInferenceAndNotify(uid, channel, requestId, question, scene, null, null, 0);
    }

    private void runInferenceAndNotify(long uid, Channel channel, String requestId, String question, String scene,
                                       String sessionId, String locale) {
        runInferenceAndNotify(uid, channel, requestId, question, scene, sessionId, locale, 0);
    }

    private void runInferenceAndNotify(long uid, Channel channel, String requestId, String question, String scene,
                                       String sessionId, String locale, int inputTypeValue) {
        AssistAnswer answer;
        try {
            answer = decoratePersonaAndAuto(
                    aiAssistApplicationService.ask(uid, question, scene, sessionId, locale, null),
                    uid, question, scene, inputTypeValue);
        } catch (Exception e) {
            log.warn("async ai assist failed, uid={}, requestId={}", uid, requestId, e);
            answer = decoratePersonaAndAuto(
                    AssistAnswer.of(4, "助手响应超时，已降级到基于规则和本地知识的答复，请稍后再试或打开任务/活动界面。",
                            "fallback", List.of(), List.of()),
                    uid, question, scene, inputTypeValue);
            auditService.record(uid, scene, "fallback", 4, question, answer.answer());
        }
        if (channel != null && channel.isActive()) {
            pushAiHintNotify(channel, requestId, answer, uid);
        }
    }
    /**
     * 发起问答请求并返回结果
     */
    public AssistAnswer askAndNotify(long uid, Channel channel, String question, String scene) {
        // 直接调用应用层 ask，获得最终 AssistAnswer。
        AssistAnswer answer = aiAssistApplicationService.ask(uid, question, scene);
        // 若 channel 在线且答复成功，则顺便下发一条通知。
        if (channel != null && channel.isActive() && answer.retcode() == 0) {
            // 通知使用新 requestId，便于前端区分主动问答与推送。
            pushAiHintNotify(channel, UUID.randomUUID().toString(), answer.answer(), answer.source(),
                    answer.relatedHints(), answer.disclaimer(), answer.strategyVersion(), answer.locale(),
                    answer.mediaLinks());
        }
        // 返回业务层问答结果给上层调用者。
        return answer;
    }
    /**
     * 向客户端推送 AI 提示通知
     */
    public void pushAiHintNotify(Channel channel, String answer, String source, List<CoachHint> related) {
        // 兼容旧调用方式，内部自动生成 requestId。
        pushAiHintNotify(channel, UUID.randomUUID().toString(), answer, source, related);
    }
    /**
     * 向客户端推送 AI 提示通知
     */
    public void pushAiHintNotify(Channel channel, String requestId, String answer, String source, List<CoachHint> related) {
        pushAiHintNotify(channel, requestId, answer, source, related, "", "", "", List.of());
    }

    public void pushAiHintNotify(Channel channel, String requestId, String answer, String source,
                                 List<CoachHint> related, String disclaimer, String strategyVersion, String locale) {
        pushAiHintNotify(channel, requestId, answer, source, related, disclaimer, strategyVersion, locale, List.of());
    }

    public void pushAiHintNotify(Channel channel, String requestId, String answer, String source,
                                 List<CoachHint> related, String disclaimer, String strategyVersion, String locale,
                                 List<AssistMediaLink> mediaLinks) {
        pushAiHintNotify(channel, requestId, answer, source, related, disclaimer, strategyVersion, locale,
                mediaLinks, null, "", 0L);
    }

    public void pushAiHintNotify(Channel channel, String requestId, String answer, String source,
                                 List<CoachHint> related, String disclaimer, String strategyVersion, String locale,
                                 List<AssistMediaLink> mediaLinks, SuggestedAutoOverride autoOverride,
                                 String personaId, long uid) {
        pushAiHintNotify(channel, requestId, answer, source, related, disclaimer, strategyVersion, locale,
                mediaLinks, autoOverride, personaId, uid, 0, "", "");
    }

    public void pushAiHintNotify(Channel channel, String requestId, String answer, String source,
                                 List<CoachHint> related, String disclaimer, String strategyVersion, String locale,
                                 List<AssistMediaLink> mediaLinks, SuggestedAutoOverride autoOverride,
                                 String personaId, long uid, int estimatedWaitMs, String renderType, String renderJson) {
        if (channel == null || !channel.isActive()) {
            return;
        }
        AssistSystemProto.AiHintNotify.Builder builder = AssistSystemProto.AiHintNotify.newBuilder()
                .setRequestId(requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId)
                .setAnswer(answer == null ? "" : answer)
                .setSource(source == null ? "" : source)
                .setDisclaimer(nullToEmpty(disclaimer))
                .setStrategyVersion(nullToEmpty(strategyVersion))
                .setLocale(nullToEmpty(locale))
                .setForbidExternalBrowser(true)
                .setHintType("llm")
                .setVoicePersonaId(nullToEmpty(personaId));
        if (estimatedWaitMs > 0) {
            builder.setEstimatedWaitMs(estimatedWaitMs);
        }
        if (renderType != null && !renderType.isBlank()) {
            builder.setRenderType(renderType);
        }
        if (renderJson != null && !renderJson.isBlank()) {
            builder.setRenderPayloadJson(renderJson);
        }
        if (related != null) {
            for (CoachHint hint : related) {
                builder.addRelatedHints(toProto(hint));
            }
        }
        List<AssistMediaLink> inApp = AssistInlineSummaryService.toInAppList(mediaLinks);
        if (!inApp.isEmpty()) {
            AssistInlineSummaryService.InlineSummary summary =
                    new AssistInlineSummaryService().summarize(answer, "", inApp);
            builder.setInlineSummary(AssistSystemProto.AssistInlineSummary.newBuilder()
                    .setText(summary.text())
                    .setMaxChars(AssistInlineSummaryService.MAX_CHARS)
                    .setSource(summary.source())
                    .build());
            for (AssistMediaLink link : inApp) {
                builder.addMediaLinks(toMediaProto(link));
            }
        }
        if (autoOverride != null && autoOverride.isPresent()) {
            builder.setSuggestedAutoOverride(toAutoOverrideProto(autoOverride));
        }
        AssistTtsService tts = ttsProvider == null ? null : ttsProvider.getIfAvailable();
        AssistPersonaService persona = personaProvider == null ? null : personaProvider.getIfAvailable();
        if (tts != null && answer != null && !answer.isBlank()) {
            String voice = persona == null ? "" : persona.voiceId(uid);
            AssistTtsService.TtsClip clip = tts.synthesize(uid, answer, voice);
            if (clip.hasAudio()) {
                builder.setAudioChunk(com.google.protobuf.ByteString.copyFrom(clip.audioChunk()))
                        .setAudioFormat(clip.format());
            }
        }
        channel.writeAndFlush(new GamePacket(CmdIds.AI_HINT_SC_NOTIFY, builder.build().toByteArray()));
    }

    /** 从完整 AssistAnswer 推送 AiHintNotify（含 render / estimated_wait）。 */
    public void pushAiHintNotify(Channel channel, String requestId, AssistAnswer answer, long uid) {
        if (answer == null) {
            return;
        }
        pushAiHintNotify(channel, requestId,
                answer.answer(), answer.source(), answer.relatedHints(),
                answer.disclaimer(), answer.strategyVersion(), answer.locale(),
                answer.mediaLinks(), answer.suggestedAutoOverride(), answer.personaId(), uid,
                answer.estimatedWaitMs(), answer.renderType(), answer.renderPayloadJson());
    }
    /**
     * 处理攻略包拉取请求
     */
    public AssistSystemProto.GetGuidePackScRsp handleGetGuidePack(AssistSystemProto.GetGuidePackCsReq req, Channel channel) {
        // 功能总开关关闭时返回 retcode=2。
        if (!properties.getAiAssist().isEnabled()) {
            return AssistSystemProto.GetGuidePackScRsp.newBuilder().setRetcode(2).build(); // 组装并返回协议响应：AssistSystemProto.GetGuidePackScRsp.newBuilder().setRet
        }
        // 没有 uid 的情况下拒绝下发 guide pack，避免匿名读取。
        if (contextResolver.resolveUid(channel).isEmpty()) {
            return AssistSystemProto.GetGuidePackScRsp.newBuilder().setRetcode(1).build(); // 组装并返回协议响应：AssistSystemProto.GetGuidePackScRsp.newBuilder().setRet
        }
        // 正常返回当前 guide pack 快照，retcode=0。
        return AssistSystemProto.GetGuidePackScRsp.newBuilder() // 创建 protobuf 响应或通知构造器
                .setRetcode(0) // 续写 handleGetGuidePack 的参数列表
                .setPack(toGuidePackProto(guidePackRepository.current())) // 续写 handleGetGuidePack 的参数列表
                .build(); // 续写 handleGetGuidePack 的参数列表
    }
    /**
     * 处理助手反馈提交
     */
    public AssistSystemProto.AssistFeedbackScRsp handleAssistFeedback(AssistSystemProto.AssistFeedbackCsReq req, Channel channel) {
        // 总开关关闭时反馈接口同样不可用。
        if (!properties.getAiAssist().isEnabled()) {
            return AssistSystemProto.AssistFeedbackScRsp.newBuilder().setRetcode(2).build(); // 组装并返回协议响应：AssistSystemProto.AssistFeedbackScRsp.newBuilder().setR
        }
        // 解析 uid，确保反馈和具体玩家绑定。
        var uidOpt = contextResolver.resolveUid(channel);
        // 缺失 uid 时返回 retcode=1。
        if (uidOpt.isEmpty()) {
            return AssistSystemProto.AssistFeedbackScRsp.newBuilder().setRetcode(1).build(); // 组装并返回协议响应：AssistSystemProto.AssistFeedbackScRsp.newBuilder().setR
        }
        // 取出 uid，为反馈配额和审计使用。
        long uid = uidOpt.getAsLong();
        // 反馈接口也有频控，防止刷好评或刷差评。
        if (!quotaLimiter.tryAcquireCoach(uid)) {
            // 记录 feedback 限流，便于排查异常提交量。
            auditService.recordQuotaReject(uid, "feedback");
            // retcode=3 表示配额拒绝。
            return AssistSystemProto.AssistFeedbackScRsp.newBuilder().setRetcode(3).build();
        }
        // 将用户反馈提交给反馈服务，记录是否有用和原因码。
        AssistFeedbackService.FeedbackResult result = feedbackService.submit(
                uid, req.getRequestId(), req.getUseful(), req.getReasonCode()); // 在 handleAssistFeedback 中调用：uid, req.getRequestId(), req.getUseful(), req.getReasonCode());
        // 反馈服务返回的 retcode 原样映射给协议层。
        return AssistSystemProto.AssistFeedbackScRsp.newBuilder().setRetcode(result.retcode()).build();
    }
    /**
     * 处理阵容推荐请求
     */
    public AssistSystemProto.AskLineupRecommendScRsp handleAskLineupRecommend(
            AssistSystemProto.AskLineupRecommendCsReq req, Channel channel) {
        // 总开关关闭时，队伍推荐接口也直接返回不可用。
        if (!properties.getAiAssist().isEnabled()) {
            return AssistSystemProto.AskLineupRecommendScRsp.newBuilder().setRetcode(2).build(); // 组装并返回协议响应：AssistSystemProto.AskLineupRecommendScRsp.newBuilder().
        }
        // 解析 uid 作为玩家身份校验。
        var uidOpt = contextResolver.resolveUid(channel);
        // 没有 uid 时返回 retcode=1。
        if (uidOpt.isEmpty()) {
            return AssistSystemProto.AskLineupRecommendScRsp.newBuilder().setRetcode(1).build(); // 组装并返回协议响应：AssistSystemProto.AskLineupRecommendScRsp.newBuilder().
        }
        // 取出 uid，用于配额、审计和推荐逻辑。
        long uid = uidOpt.getAsLong();
        // 阵容推荐走 coach 配额，防止被高频刷取。
        if (!quotaLimiter.tryAcquireCoach(uid)) {
            // 记录 lineup 请求限流，区分于普通问答。
            auditService.recordQuotaReject(uid, "lineup");
            // retcode=3 表示触发频控。
            return AssistSystemProto.AskLineupRecommendScRsp.newBuilder().setRetcode(3).build();
        }
        // 调用配队推荐服务，按 maxSlots、scene 和 enemyTags 生成结果。
        OwnedLineupRecommendService.RecommendResult result =
                lineupRecommendService.recommend(uid, req.getMaxSlots(), req.getScene(), req.getEnemyTagsList()); // 在 handleAskLineupRecommend 中调用：lineupRecommendService.recommend(uid, req.getMaxSlots(), req.getS
        // 构建响应，写入 retcode 和推荐原因。
        AssistSystemProto.AskLineupRecommendScRsp.Builder builder =
                AssistSystemProto.AskLineupRecommendScRsp.newBuilder() // 创建 protobuf 响应或通知构造器
                        .setRetcode(result.retcode()) // 续写 handleAskLineupRecommend 的参数列表
                        .setReason(nullToEmpty(result.reason())); // 续写 handleAskLineupRecommend 的参数列表
        // 只把正整数 avatarId 放入返回列表，避免无效 ID 污染协议。
        for (Integer id : result.avatarIds()) {
            // 非空且大于 0 的 avatarId 才算有效推荐。
            if (id != null && id > 0) {
                builder.addAvatarIds(id); // 完成 handleAskLineupRecommend 的参数传入并结束语句
            }
        }
        // 把相关教练提示一起返回，让客户端同时看到解释性建议。
        for (CoachHint hint : result.relatedHints()) {
            builder.addRelatedHints(toProto(hint)); // 完成 handleAskLineupRecommend 的参数传入并结束语句
        }
        // 返回完整的阵容推荐结果。
        return builder.build();
    }
    /**
     * 处理探索路线请求
     */
    public AssistSystemProto.AskExplorePathScRsp handleAskExplorePath(
            AssistSystemProto.AskExplorePathCsReq req, Channel channel) {
        // 功能关闭时直接返回 retcode=2。
        if (!properties.getAiAssist().isEnabled()) {
            return AssistSystemProto.AskExplorePathScRsp.newBuilder().setRetcode(2).build(); // 组装并返回协议响应：AssistSystemProto.AskExplorePathScRsp.newBuilder().setR
        }
        // 从 channel 解析 uid，保证请求来自登录玩家。
        var uidOpt = contextResolver.resolveUid(channel);
        // 没有 uid 时返回 retcode=1。
        if (uidOpt.isEmpty()) {
            return AssistSystemProto.AskExplorePathScRsp.newBuilder().setRetcode(1).build(); // 组装并返回协议响应：AssistSystemProto.AskExplorePathScRsp.newBuilder().setR
        }
        // 取出 uid 作为频控主键。
        long uid = uidOpt.getAsLong();
        // 寻路建议属于 coach 类接口，因此同样受 coach 配额限制。
        if (!quotaLimiter.tryAcquireCoach(uid)) {
            // 审计记录 explore 限流事件。
            auditService.recordQuotaReject(uid, "explore");
            // retcode=3 说明请求被频控拒绝。
            return AssistSystemProto.AskExplorePathScRsp.newBuilder().setRetcode(3).build();
        }
        // 通过任务 id 和 scene id 生成探索路线建议。
        ExplorePathAdvisor.PathAdvice advice = explorePathAdvisor.advise(req.getQuestId(), req.getSceneId());
        // 构建 proto 响应头，写入 retcode、路线 id、标题和摘要。
        AssistSystemProto.AskExplorePathScRsp.Builder builder = AssistSystemProto.AskExplorePathScRsp.newBuilder() // 创建 protobuf 响应或通知构造器
                .setRetcode(advice.retcode()) // 续写 handleAskExplorePath 的参数列表
                .setRouteId(nullToEmpty(advice.routeId())) // 续写 handleAskExplorePath 的参数列表
                .setTitle(nullToEmpty(advice.title())) // 续写 handleAskExplorePath 的参数列表
                .setSummary(nullToEmpty(advice.summary())); // 续写 handleAskExplorePath 的参数列表
        // 把路线上的每个 waypoint 映射为协议层 ExploreWaypoint。
        for (AssistFeatureContent.Waypoint wp : advice.waypoints()) {
            // 允许路线上存在空点位，但不会把空点位写进协议。
            if (wp == null) {
                continue; // 本条数据无效或不匹配，跳到下一项
            }
            // 每个 waypoint 都携带坐标、标签和 markerType。
            builder.addWaypoints(AssistSystemProto.ExploreWaypoint.newBuilder() // 创建 protobuf 响应或通知构造器
                    .setX(wp.x()).setY(wp.y()).setZ(wp.z()) // 续写 handleAskExplorePath 的参数列表
                    .setLabel(nullToEmpty(wp.label())) // 续写 handleAskExplorePath 的参数列表
                    .setMarkerType(nullToEmpty(wp.markerType())) // 续写 handleAskExplorePath 的参数列表
                    .build()); // 续写 handleAskExplorePath 的参数列表
        }
        // 资源提示直接透传到协议层。
        builder.addAllResourceHints(advice.resourceHints());
        // 返回完整寻路建议。
        return builder.build();
    }
    /**
     * 处理任务引导请求
     */
    public AssistSystemProto.AskQuestGuidanceScRsp handleAskQuestGuidance(
            AssistSystemProto.AskQuestGuidanceCsReq req, Channel channel) {
        // 总开关关闭时，任务引导接口不可用。
        if (!properties.getAiAssist().isEnabled()) {
            return AssistSystemProto.AskQuestGuidanceScRsp.newBuilder().setRetcode(2).build(); // 组装并返回协议响应：AssistSystemProto.AskQuestGuidanceScRsp.newBuilder().se
        }
        // 解析 uid，任务引导需要知道当前玩家身份。
        var uidOpt = contextResolver.resolveUid(channel);
        // 缺失 uid 时返回 retcode=1。
        if (uidOpt.isEmpty()) {
            return AssistSystemProto.AskQuestGuidanceScRsp.newBuilder().setRetcode(1).build(); // 组装并返回协议响应：AssistSystemProto.AskQuestGuidanceScRsp.newBuilder().se
        }
        // 取出 uid，用于频控与任务引导逻辑。
        long uid = uidOpt.getAsLong();
        // 任务引导同样走 coach 配额。
        if (!quotaLimiter.tryAcquireCoach(uid)) {
            // 记录 quest-guide 限流，以便后续查看任务引导压力。
            auditService.recordQuotaReject(uid, "quest-guide");
            // retcode=3 表示被频控。
            return AssistSystemProto.AskQuestGuidanceScRsp.newBuilder().setRetcode(3).build();
        }
        // 调用任务引导服务，生成阶段、标题和消息。
        QuestGuidanceService.Guidance g = questGuidanceService.guide(uid, req.getQuestion());
        // 将领域层 guidance 直接映射到协议层。
        return AssistSystemProto.AskQuestGuidanceScRsp.newBuilder() // 创建 protobuf 响应或通知构造器
                .setRetcode(g.retcode()) // 续写 handleAskQuestGuidance 的参数列表
                .setQuestId(Math.max(0, g.questId())) // 续写 handleAskQuestGuidance 的参数列表
                .setStage(Math.max(0, g.stage())) // 续写 handleAskQuestGuidance 的参数列表
                .setTitle(nullToEmpty(g.title())) // 续写 handleAskQuestGuidance 的参数列表
                .setMessage(nullToEmpty(g.message())) // 续写 handleAskQuestGuidance 的参数列表
                .setSource(nullToEmpty(g.source())) // 续写 handleAskQuestGuidance 的参数列表
                .build(); // 续写 handleAskQuestGuidance 的参数列表
    }
    /**
     * 推送环境旁白
     */
    public void pushEnvironmentNarration(Channel channel, String poiId, String title, String message, String source) {
        // channel 无效时不做任何推送，避免向断线客户端写包。
        if (channel == null || !channel.isActive()) {
            return; // 条件不满足，本方法提前结束不写状态
        }
        // 构造环境叙事通知，常用于场景点位、标题和提示语展示。
        AssistSystemProto.EnvironmentNarrationScNotify notify =
                AssistSystemProto.EnvironmentNarrationScNotify.newBuilder() // 创建 protobuf 响应或通知构造器
                        .setPoiId(nullToEmpty(poiId)) // 续写 pushEnvironmentNarration 的参数列表
                        .setTitle(nullToEmpty(title)) // 续写 pushEnvironmentNarration 的参数列表
                        .setMessage(nullToEmpty(message)) // 续写 pushEnvironmentNarration 的参数列表
                        .setSource(nullToEmpty(source)) // 续写 pushEnvironmentNarration 的参数列表
                        .build(); // 续写 pushEnvironmentNarration 的参数列表
        // 通过固定 cmdId 推送环境叙事消息。
        channel.writeAndFlush(new GamePacket(CmdIds.ENVIRONMENT_NARRATION_SC_NOTIFY, notify.toByteArray()));
    }
    /**
     * 推送攻略包更新
     */
    public void pushGuidePackUpdate(Channel channel) {
        // channel 无效时直接返回，不触发任何网络写入。
        if (channel == null || !channel.isActive()) {
            return; // 条件不满足，本方法提前结束不写状态
        }
        // 重新下发 guide pack，用于客户端刷新 FAQ 包版本。
        AssistSystemProto.GuidePackUpdateScNotify notify = AssistSystemProto.GuidePackUpdateScNotify.newBuilder() // 创建 protobuf 响应或通知构造器
                .setPack(toGuidePackProto(guidePackRepository.current())) // 续写 pushGuidePackUpdate 的参数列表
                .build(); // 续写 pushGuidePackUpdate 的参数列表
        // 把 guide pack 更新通知写入游戏包并发送。
        channel.writeAndFlush(new GamePacket(CmdIds.GUIDE_PACK_UPDATE_SC_NOTIFY, notify.toByteArray()));
    }
    /**
     * 把领域 AssistAnswer 映射为 AskAiAssistScRsp
     */
    public static AssistSystemProto.AskAiAssistScRsp toAskRsp(AssistAnswer answer) {
        AssistSystemProto.AskAiAssistScRsp.Builder builder = AssistSystemProto.AskAiAssistScRsp.newBuilder()
                .setRetcode(answer.retcode())
                .setAnswer(answer.answer())
                .setSource(answer.source())
                .setDisclaimer(answer.disclaimer() == null ? "" : answer.disclaimer())
                .setStrategyVersion(answer.strategyVersion() == null ? "" : answer.strategyVersion())
                .setLocale(answer.locale() == null ? "" : answer.locale())
                .setForbidExternalBrowser(answer.forbidExternalBrowser())
                .setPersonaId(answer.personaId() == null ? "" : answer.personaId())
                .setInputTypeValue(answer.inputType());
        for (CoachHint hint : answer.relatedHints()) {
            builder.addRelatedHints(toProto(hint));
        }
        builder.addAllCitedConfigIds(answer.citedConfigIds());
        if (answer.inlineSummary() != null && !answer.inlineSummary().isBlank()) {
            builder.setInlineSummary(AssistSystemProto.AssistInlineSummary.newBuilder()
                    .setText(answer.inlineSummary())
                    .setMaxChars(AssistInlineSummaryService.MAX_CHARS)
                    .setSource(answer.source() == null ? "hybrid" : answer.source())
                    .build());
        }
        if (answer.mediaLinks() != null) {
            for (AssistMediaLink link : AssistInlineSummaryService.toInAppList(answer.mediaLinks())) {
                builder.addMediaLinks(toMediaProto(link));
            }
        }
        if (answer.suggestedAutoOverride() != null && answer.suggestedAutoOverride().isPresent()) {
            builder.setSuggestedAutoOverride(toAutoOverrideProto(answer.suggestedAutoOverride()));
        }
        if (answer.estimatedWaitMs() > 0) {
            builder.setEstimatedWaitMs(answer.estimatedWaitMs());
        }
        if (answer.renderType() != null && !answer.renderType().isBlank()) {
            builder.setRenderType(answer.renderType());
        }
        if (answer.renderPayloadJson() != null && !answer.renderPayloadJson().isBlank()) {
            builder.setRenderPayloadJson(answer.renderPayloadJson());
        }
        return builder.build();
    }

    public static AssistSystemProto.SuggestedAutoOverride toAutoOverrideProto(SuggestedAutoOverride o) {
        if (o == null) {
            return AssistSystemProto.SuggestedAutoOverride.getDefaultInstance();
        }
        AssistSystemProto.SuggestedAutoOverride.Builder b = AssistSystemProto.SuggestedAutoOverride.newBuilder()
                .setTargetFocus(o.targetFocus())
                .setSkillPriority(o.skillPriority())
                .setUltReserve(o.ultReserve())
                .setReason(o.reason() == null ? "" : o.reason())
                .setApplyCountdownMs(o.applyCountdownMs() > 0
                        ? o.applyCountdownMs()
                        : SuggestedAutoOverride.COUNTDOWN_DEFAULT_MS)
                .setLockNextSkillId(o.lockNextSkillId())
                .setLockCasterEntityId(o.lockCasterEntityId())
                .setAutoRevertAfterAction(o.autoRevertAfterAction())
                .setBattleStateSummary(o.battleStateSummary() == null ? "" : o.battleStateSummary());
        return b.build();
    }
    /**
     * 把 GuidePackConfig 映射为协议 GuidePackInfo
     */
    static AssistSystemProto.GuidePackInfo toGuidePackProto(GuidePackConfig config) {
        // 若配置对象为空，则使用空 guide pack 作为协议默认值。
        GuidePackConfig safe = config == null ? GuidePackConfig.empty() : config;
        // size 为空时按 0 处理，避免协议字段缺失。
        long size = safe.size() == null ? 0L : safe.size();
        // 构造 guide pack 基本信息，包含版本、语言、下载地址和内容 hash。
        AssistSystemProto.GuidePackInfo.Builder builder = AssistSystemProto.GuidePackInfo.newBuilder() // 创建 protobuf 响应或通知构造器
                .setVersion(nullToEmpty(safe.version())) // 续写 toGuidePackProto 的参数列表
                .setLocale(nullToEmpty(safe.locale())) // 续写 toGuidePackProto 的参数列表
                .setDownloadUrl(nullToEmpty(safe.downloadUrl())) // 续写 toGuidePackProto 的参数列表
                .setContentHash(nullToEmpty(safe.contentHash())) // 续写 toGuidePackProto 的参数列表
                .setSize(size); // 续写 toGuidePackProto 的参数列表
        // 把 FAQ 项逐条转成协议层 entry。
        for (GuidePackConfig.FaqEntry faq : safe.faqs()) {
            // 空 FAQ 不参与下发，避免协议里出现脏数据。
            if (faq == null) {
                continue; // 本条数据无效或不匹配，跳到下一项
            }
            // 每条 FAQ 都携带 id、question、answer 和 scene。
            builder.addFaqs(AssistSystemProto.GuideFaqEntry.newBuilder() // 创建 protobuf 响应或通知构造器
                    .setId(nullToEmpty(faq.id())) // 续写 toGuidePackProto 的参数列表
                    .setQuestion(nullToEmpty(faq.question())) // 续写 toGuidePackProto 的参数列表
                    .setAnswer(nullToEmpty(faq.answer())) // 续写 toGuidePackProto 的参数列表
                    .setScene(nullToEmpty(faq.scene())) // 续写 toGuidePackProto 的参数列表
                    .build()); // 续写 toGuidePackProto 的参数列表
        }
        // 返回协议层 guide pack 信息。
        return builder.build();
    }
    /**
     * 把领域 CoachHint 映射为协议 CoachHint
     */
    static AssistSystemProto.CoachHint toProto(CoachHint hint) {
        // 把领域 CoachHint 映射到协议对象，并对空值做兜底。
        return AssistSystemProto.CoachHint.newBuilder() // 创建 protobuf 响应或通知构造器
                .setTipId(nullToEmpty(hint.tipId())) // 续写 toProto 的参数列表
                .setCategory(nullToEmpty(hint.category())) // 续写 toProto 的参数列表
                .setPriority(Math.max(0, hint.priority())) // 续写 toProto 的参数列表
                .setTitle(nullToEmpty(hint.title())) // 续写 toProto 的参数列表
                .setMessage(nullToEmpty(hint.message())) // 续写 toProto 的参数列表
                .setAction(nullToEmpty(hint.action())) // 续写 toProto 的参数列表
                .setRefId(Math.max(0, hint.refId())) // 续写 toProto 的参数列表
                .build(); // 续写 toProto 的参数列表
    }

    /** 把领域 AssistMediaLink 映射为协议 AssistMediaLink。 */
    static AssistSystemProto.AssistMediaLink toMediaProto(AssistMediaLink link) {
        if (link == null) {
            return AssistSystemProto.AssistMediaLink.getDefaultInstance();
        }
        return AssistSystemProto.AssistMediaLink.newBuilder()
                .setId(nullToEmpty(link.id()))
                .setTitle(nullToEmpty(link.title()))
                .setPlatform(nullToEmpty(link.platform()))
                .setMediaType(nullToEmpty(link.mediaType()))
                .setUrl(nullToEmpty(link.url()))
                .setAction(nullToEmpty(link.action()))
                .setCurated(link.curated())
                .setRenderMode(nullToEmpty(link.renderMode()))
                .build();
    }
    /**
     * 将 null 转为空字符串
     */
    private static String nullToEmpty(String s) {
        // 协议层字段统一把 null 转为 пуст字符串，防止序列化空指针。
        return s == null ? "" : s;
    }
}
