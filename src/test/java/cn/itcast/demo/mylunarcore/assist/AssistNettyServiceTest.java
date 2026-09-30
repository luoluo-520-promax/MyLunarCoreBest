package cn.itcast.demo.mylunarcore.assist;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.player.PlayerContextResolver;
import cn.itcast.demo.mylunarcore.protocol.AssistSystemProto;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AssistNettyService 协议门面测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code AssistNettyServiceTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("AssistNettyService 协议门面测试")
class AssistNettyServiceTest {

    private static final Logger log = LoggerFactory.getLogger(AssistNettyServiceTest.class);

    /**
     * 验证点：AskCoachHint：未登录/关闭/限流应返回对应 retcode。
     * <p>测试方法 {@code askCoachHintShouldReturnGateRetcodes}：
     * <ul>
     *   <li>{@code when(limited.tryAcquire(eq(1L), eq(AssistQuotaLimiter.QuotaKind.COACH)))}</li>
     *   <li>{@code assertEquals(2, closed);}</li>
     *   <li>{@code assertEquals(1, unauth);}</li>
     *   <li>{@code assertEquals(3, quota);}</li>
     * </ul>
     */
    @Test
    @DisplayName("AskCoachHint：未登录/关闭/限流应返回对应 retcode")
    void askCoachHintShouldReturnGateRetcodes() {
        LunarCoreProperties props = new LunarCoreProperties();
        props.getAiAssist().setEnabled(false);
        AssistNettyService disabled = newService(props, mockResolver(OptionalLong.of(1L)),
                alwaysAllowQuota(), mock(PlayerCoachApplicationService.class),
                mock(AiAssistApplicationService.class), mock(GuidePackRepository.class));
        int closed = disabled.handleAskCoachHint(AssistSystemProto.AskCoachHintCsReq.newBuilder().setMaxHints(3).build(),
                mock(Channel.class)).getRetcode();

        props.getAiAssist().setEnabled(true);
        props.getAiAssist().setRuleCoachEnabled(true);
        AssistNettyService noLogin = newService(props, mockResolver(OptionalLong.empty()),
                alwaysAllowQuota(), mock(PlayerCoachApplicationService.class),
                mock(AiAssistApplicationService.class), mock(GuidePackRepository.class));
        int unauth = noLogin.handleAskCoachHint(AssistSystemProto.AskCoachHintCsReq.newBuilder().setMaxHints(3).build(),
                mock(Channel.class)).getRetcode();

        AssistQuotaLimiter limited = mock(AssistQuotaLimiter.class);
        when(limited.tryAcquire(eq(1L), eq(AssistQuotaLimiter.QuotaKind.COACH)))
                .thenReturn(AssistQuotaLimiter.AcquireResult.rejected(30));
        AssistNettyService rateLimited = newService(props, mockResolver(OptionalLong.of(1L)),
                limited, mock(PlayerCoachApplicationService.class),
                mock(AiAssistApplicationService.class), mock(GuidePackRepository.class));
        int quota = rateLimited.handleAskCoachHint(AssistSystemProto.AskCoachHintCsReq.newBuilder().setMaxHints(3).build(),
                mock(Channel.class)).getRetcode();

        log.info("AskCoachHint 门禁校验: closed={}, unauth={}, quota={}", closed, unauth, quota);
        assertEquals(2, closed);
        assertEquals(1, unauth);
        assertEquals(3, quota);
    }

    /**
     * 验证点：AskCoachHint 成功应填充 hints。
     * <p>测试方法 {@code askCoachHintSuccessShouldFillHints}：
     * <ul>
     *   <li>{@code when(coach.suggest(1L, 2)).thenReturn(List.of(}</li>
     *   <li>{@code assertEquals(0, rsp.getRetcode());}</li>
     *   <li>{@code assertEquals(1, rsp.getHintsCount());}</li>
     *   <li>{@code assertEquals("quest_next", rsp.getHints(0).getTipId());}</li>
     *   <li>{@code assertEquals(10001, rsp.getHints(0).getRefId());}</li>
     * </ul>
     */
    @Test
    @DisplayName("AskCoachHint 成功应填充 hints")
    void askCoachHintSuccessShouldFillHints() {
        LunarCoreProperties props = new LunarCoreProperties();
        props.getAiAssist().setEnabled(true);
        props.getAiAssist().setRuleCoachEnabled(true);
        PlayerCoachApplicationService coach = mock(PlayerCoachApplicationService.class);
        when(coach.suggest(1L, 2)).thenReturn(List.of(
                new CoachHint("quest_next", "quest", 100, "继续", "去做主线", "OPEN_QUEST", 10001)));
        AssistNettyService service = newService(props, mockResolver(OptionalLong.of(1L)),
                alwaysAllowQuota(), coach, mock(AiAssistApplicationService.class), mock(GuidePackRepository.class));

        AssistSystemProto.AskCoachHintScRsp rsp = service.handleAskCoachHint(
                AssistSystemProto.AskCoachHintCsReq.newBuilder().setMaxHints(2).build(), mock(Channel.class));
        log.info("AskCoachHint 成功校验: retcode={}, hintCount={}, tipId={}, title={}, refId={}",
                rsp.getRetcode(), rsp.getHintsCount(),
                rsp.getHints(0).getTipId(), rsp.getHints(0).getTitle(), rsp.getHints(0).getRefId());
        assertEquals(0, rsp.getRetcode());
        assertEquals(1, rsp.getHintsCount());
        assertEquals("quest_next", rsp.getHints(0).getTipId());
        assertEquals(10001, rsp.getHints(0).getRefId());
    }

    /**
     * 验证点：AskAiAssist 同步模式应映射 answer/source/cited。
     * <p>测试方法 {@code askAiAssistShouldMapAnswer}：
     * <ul>
     *   <li>{@code when(app.tryLocalFast(anyLong(), any(), any())).thenReturn(Optional.empty());}</li>
     *   <li>{@code when(app.ask(1L, "主线怎么做", "quest")).thenReturn(AssistAnswer.of(}</li>
     *   <li>{@code assertEquals(0, rsp.getRetcode());}</li>
     *   <li>{@code assertEquals("llm", rsp.getSource());}</li>
     *   <li>{@code assertEquals("先推进主线", rsp.getAnswer());}</li>
     *   <li>{@code assertEquals(List.of("quest:1"), rsp.getCitedConfigIdsList());}</li>
     * </ul>
     */
    @Test
    @DisplayName("AskAiAssist 同步模式应映射 answer/source/cited")
    void askAiAssistShouldMapAnswer() {
        LunarCoreProperties props = new LunarCoreProperties();
        props.getAiAssist().setEnabled(true);
        props.getAiAssist().setSyncMode(true);
        AiAssistApplicationService app = mock(AiAssistApplicationService.class);
        when(app.tryLocalFast(anyLong(), any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        when(app.ask(anyLong(), any(), any(), any(), any(), any())).thenReturn(AssistAnswer.of(
                0, "先推进主线", "llm",
                List.of(new CoachHint("quest_next", "quest", 1, "继续", "去做", "OPEN", 1)),
                List.of("quest:1")));
        AssistNettyService service = newService(props, mockResolver(OptionalLong.of(1L)),
                alwaysAllowQuota(), mock(PlayerCoachApplicationService.class), app, mock(GuidePackRepository.class));

        AssistSystemProto.AskAiAssistScRsp rsp = service.handleAskAiAssist(
                AssistSystemProto.AskAiAssistCsReq.newBuilder().setQuestion("主线怎么做").setScene("quest").build(),
                mock(Channel.class));
        log.info("AskAiAssist 映射校验: retcode={}, source={}, answer={}, relatedCount={}, cited={}",
                rsp.getRetcode(), rsp.getSource(), rsp.getAnswer(),
                rsp.getRelatedHintsCount(), rsp.getCitedConfigIdsList());
        assertEquals(0, rsp.getRetcode());
        assertEquals("llm", rsp.getSource());
        assertEquals("先推进主线", rsp.getAnswer());
        assertEquals(List.of("quest:1"), rsp.getCitedConfigIdsList());
    }

    /**
     * 验证点：AskAiAssist 异步模式应先回执 accepted。
     * <p>测试方法 {@code askAiAssistAsyncShouldAccept}：
     * <ul>
     *   <li>{@code when(app.tryLocalFast(anyLong(), any(), any())).thenReturn(Optional.empty());}</li>
     *   <li>{@code when(app.ask(anyLong(), any(), any())).thenReturn(AssistAnswer.of(0, "稍后答案", "rule", List.of(), List.of()));}</li>
     *   <li>{@code when(channel.isActive()).thenReturn(true);}</li>
     *   <li>{@code when(channel.writeAndFlush(any())).thenReturn(mock(ChannelFuture.class));}</li>
     *   <li>{@code assertEquals(0, rsp.getRetcode());}</li>
     *   <li>{@code assertEquals("accepted", rsp.getAnswer());}</li>
     * </ul>
     */
    @Test
    @DisplayName("AskAiAssist 异步模式应先回执 accepted")
    void askAiAssistAsyncShouldAccept() {
        LunarCoreProperties props = new LunarCoreProperties();
        props.getAiAssist().setEnabled(true);
        props.getAiAssist().setSyncMode(false);
        AiAssistApplicationService app = mock(AiAssistApplicationService.class);
        when(app.tryLocalFast(anyLong(), any(), any(), any(), any(), any())).thenReturn(Optional.empty());
        when(app.ask(anyLong(), any(), any(), any(), any(), any())).thenReturn(AssistAnswer.of(0, "稍后答案", "rule", List.of(), List.of()));
        AssistNettyService service = newService(props, mockResolver(OptionalLong.of(1L)),
                alwaysAllowQuota(), mock(PlayerCoachApplicationService.class), app, mock(GuidePackRepository.class));

        Channel channel = mock(Channel.class);
        when(channel.isActive()).thenReturn(true);
        when(channel.writeAndFlush(any())).thenReturn(mock(ChannelFuture.class));

        AssistSystemProto.AskAiAssistScRsp rsp = service.handleAskAiAssist(
                AssistSystemProto.AskAiAssistCsReq.newBuilder().setQuestion("活动怎么玩").setScene("activity").build(),
                channel);
        log.info("AskAiAssist 异步回执校验: retcode={}, answer={}, source={}",
                rsp.getRetcode(), rsp.getAnswer(), rsp.getSource());
        assertEquals(0, rsp.getRetcode());
        assertEquals("accepted", rsp.getAnswer());
        assertEquals("accepted", rsp.getSource());
    }

    /**
     * 验证点：AssistFeedback 成功与非法 requestId 应映射 retcode。
     * <p>测试方法 {@code assistFeedbackShouldMapRetcodes}：
     * <ul>
     *   <li>{@code assertEquals(0, ok);}</li>
     *   <li>{@code assertEquals(5, bad);}</li>
     *   <li>{@code assertEquals(1, feedback.getFeedbackCount());}</li>
     * </ul>
     */
    @Test
    @DisplayName("AssistFeedback 成功与非法 requestId 应映射 retcode")
    void assistFeedbackShouldMapRetcodes() {
        LunarCoreProperties props = new LunarCoreProperties();
        props.getAiAssist().setEnabled(true);
        AssistFeedbackService feedback = mockFeedback();
        AssistNettyService service = newService(props, mockResolver(OptionalLong.of(1L)),
                alwaysAllowQuota(), mock(PlayerCoachApplicationService.class),
                mock(AiAssistApplicationService.class), mock(GuidePackRepository.class),
                feedback, mock(OwnedLineupRecommendService.class));

        int ok = service.handleAssistFeedback(
                AssistSystemProto.AssistFeedbackCsReq.newBuilder()
                        .setRequestId("req-ok").setUseful(true).setReasonCode("other").build(),
                mock(Channel.class)).getRetcode();
        int bad = service.handleAssistFeedback(
                AssistSystemProto.AssistFeedbackCsReq.newBuilder()
                        .setRequestId("").setUseful(false).setReasonCode("inaccurate").build(),
                mock(Channel.class)).getRetcode();
        log.info("AssistFeedback 校验: ok={}, bad={}, feedbackCount={}, usefulRate={}",
                ok, bad, feedback.getFeedbackCount(), feedback.usefulRate());
        assertEquals(0, ok);
        assertEquals(5, bad);
        assertEquals(1, feedback.getFeedbackCount());
    }

    /**
     * 验证点：AskLineupRecommend 成功应映射 avatarIds 与 reason。
     * <p>测试方法 {@code askLineupRecommendShouldMapResult}：
     * <ul>
     *   <li>{@code when(lineup.recommend(eq(1L), eq(3), eq("meta"), any())).thenReturn(new OwnedLineupRecommendService.RecommendResult(}</li>
     *   <li>{@code assertEquals(0, rsp.getRetcode());}</li>
     *   <li>{@code assertEquals(List.of(1001, 1002), rsp.getAvatarIdsList());}</li>
     *   <li>{@code assertTrue(rsp.getReason().contains("已拥有"));}</li>
     *   <li>{@code assertEquals(1, rsp.getRelatedHintsCount());}</li>
     * </ul>
     */
    @Test
    @DisplayName("AskLineupRecommend 成功应映射 avatarIds 与 reason")
    void askLineupRecommendShouldMapResult() {
        LunarCoreProperties props = new LunarCoreProperties();
        props.getAiAssist().setEnabled(true);
        OwnedLineupRecommendService lineup = mock(OwnedLineupRecommendService.class);
        when(lineup.recommend(eq(1L), eq(3), eq("meta"), any())).thenReturn(new OwnedLineupRecommendService.RecommendResult(
                0, List.of(1001, 1002), "基于已拥有角色推荐", List.of(
                new CoachHint("act", "activity", 1, "活动", "去参与", "OPEN", 9))));
        AssistNettyService service = newService(props, mockResolver(OptionalLong.of(1L)),
                alwaysAllowQuota(), mock(PlayerCoachApplicationService.class),
                mock(AiAssistApplicationService.class), mock(GuidePackRepository.class),
                mockFeedback(), lineup);

        AssistSystemProto.AskLineupRecommendScRsp rsp = service.handleAskLineupRecommend(
                AssistSystemProto.AskLineupRecommendCsReq.newBuilder()
                        .setMaxSlots(3).setScene("meta").build(),
                mock(Channel.class));
        log.info("AskLineupRecommend 校验: retcode={}, avatarIds={}, reason={}, hintCount={}",
                rsp.getRetcode(), rsp.getAvatarIdsList(), rsp.getReason(), rsp.getRelatedHintsCount());
        assertEquals(0, rsp.getRetcode());
        assertEquals(List.of(1001, 1002), rsp.getAvatarIdsList());
        assertTrue(rsp.getReason().contains("已拥有"));
        assertEquals(1, rsp.getRelatedHintsCount());
    }

    /**
     * 验证点：GetGuidePack 成功应返回版本与 FAQ 及 size。
     * <p>测试方法 {@code getGuidePackShouldReturnPack}：
     * <ul>
     *   <li>{@code when(guide.current()).thenReturn(new GuidePackConfig("2.0.0", "zh-CN", "http://cdn", "h1", 128L,}</li>
     *   <li>{@code assertEquals(0, rsp.getRetcode());}</li>
     *   <li>{@code assertEquals("2.0.0", rsp.getPack().getVersion());}</li>
     *   <li>{@code assertEquals(1, rsp.getPack().getFaqsCount());}</li>
     *   <li>{@code assertEquals(128L, rsp.getPack().getSize());}</li>
     * </ul>
     */
    @Test
    @DisplayName("GetGuidePack 成功应返回版本与 FAQ 及 size")
    void getGuidePackShouldReturnPack() {
        LunarCoreProperties props = new LunarCoreProperties();
        props.getAiAssist().setEnabled(true);
        GuidePackRepository guide = mock(GuidePackRepository.class);
        when(guide.current()).thenReturn(new GuidePackConfig("2.0.0", "zh-CN", "http://cdn", "h1", 128L,
                List.of(new GuidePackConfig.FaqEntry("f1", "问", "答", "general"))));
        AssistNettyService service = newService(props, mockResolver(OptionalLong.of(1L)),
                alwaysAllowQuota(), mock(PlayerCoachApplicationService.class),
                mock(AiAssistApplicationService.class), guide);

        AssistSystemProto.GetGuidePackScRsp rsp = service.handleGetGuidePack(
                AssistSystemProto.GetGuidePackCsReq.newBuilder().build(), mock(Channel.class));
        log.info("GetGuidePack 校验: retcode={}, version={}, locale={}, faqCount={}, size={}",
                rsp.getRetcode(), rsp.getPack().getVersion(), rsp.getPack().getLocale(),
                rsp.getPack().getFaqsCount(), rsp.getPack().getSize());
        assertEquals(0, rsp.getRetcode());
        assertEquals("2.0.0", rsp.getPack().getVersion());
        assertEquals(1, rsp.getPack().getFaqsCount());
        assertEquals(128L, rsp.getPack().getSize());
    }

    /**
     * 验证点：askAndNotify 成功且通道活跃时应写回通知包。
     * <p>测试方法 {@code askAndNotifyShouldPushOnSuccess}：
     * <ul>
     *   <li>{@code when(app.ask(1L, "你好", "general")).thenReturn(AssistAnswer.of(0, "你好啊", "rule", List.of(), List.of()));}</li>
     *   <li>{@code when(channel.isActive()).thenReturn(true);}</li>
     *   <li>{@code when(channel.writeAndFlush(any())).thenReturn(mock(ChannelFuture.class));}</li>
     *   <li>{@code assertEquals(0, answer.retcode());}</li>
     *   <li>{@code verify(channel).writeAndFlush(any());}</li>
     * </ul>
     */
    @Test
    @DisplayName("askAndNotify 成功且通道活跃时应写回通知包")
    void askAndNotifyShouldPushOnSuccess() {
        LunarCoreProperties props = new LunarCoreProperties();
        AiAssistApplicationService app = mock(AiAssistApplicationService.class);
        when(app.ask(1L, "你好", "general")).thenReturn(AssistAnswer.of(0, "你好啊", "rule", List.of(), List.of()));
        AssistNettyService service = newService(props, mockResolver(OptionalLong.of(1L)),
                alwaysAllowQuota(), mock(PlayerCoachApplicationService.class), app, mock(GuidePackRepository.class));

        Channel channel = mock(Channel.class);
        when(channel.isActive()).thenReturn(true);
        when(channel.writeAndFlush(any())).thenReturn(mock(ChannelFuture.class));

        AssistAnswer answer = service.askAndNotify(1L, channel, "你好", "general");
        log.info("askAndNotify 推送校验: retcode={}, source={}, answer={}",
                answer.retcode(), answer.source(), answer.answer());
        assertEquals(0, answer.retcode());
        verify(channel).writeAndFlush(any());
    }

    /**
     * 验证点：通道不活跃时 pushAiHintNotify 不应写包。
     * <p>测试方法 {@code pushShouldSkipInactiveChannel}：
     * <ul>
     *   <li>{@code when(channel.isActive()).thenReturn(false);}</li>
     *   <li>{@code verify(channel, never()).writeAndFlush(any());}</li>
     *   <li>{@code assertTrue(true);}</li>
     * </ul>
     */
    @Test
    @DisplayName("通道不活跃时 pushAiHintNotify 不应写包")
    void pushShouldSkipInactiveChannel() {
        AssistNettyService service = newService(new LunarCoreProperties(), mockResolver(OptionalLong.of(1L)),
                alwaysAllowQuota(), mock(PlayerCoachApplicationService.class),
                mock(AiAssistApplicationService.class), mock(GuidePackRepository.class));
        Channel channel = mock(Channel.class);
        when(channel.isActive()).thenReturn(false);
        service.pushAiHintNotify(channel, "x", "rule", List.of());
        log.info("非活跃通道推送校验: isActive={}", channel.isActive());
        verify(channel, never()).writeAndFlush(any());
        assertTrue(true);
    }

    private static AssistNettyService newService(LunarCoreProperties props,
                                                 PlayerContextResolver resolver,
                                                 AssistQuotaLimiter quota,
                                                 PlayerCoachApplicationService coach,
                                                 AiAssistApplicationService app,
                                                 GuidePackRepository guide) {
        return newService(props, resolver, quota, coach, app, guide,
                mockFeedback(), mock(OwnedLineupRecommendService.class));
    }

    private static AssistFeedbackService mockFeedback() {
        return new AssistFeedbackService(
                mock(cn.itcast.demo.mylunarcore.common.BusinessMetrics.class),
                mock(cn.itcast.demo.mylunarcore.analytics.AnalyticsEventPublisher.class));
    }

    private static AssistNettyService newService(LunarCoreProperties props,
                                                 PlayerContextResolver resolver,
                                                 AssistQuotaLimiter quota,
                                                 PlayerCoachApplicationService coach,
                                                 AiAssistApplicationService app,
                                                 GuidePackRepository guide,
                                                 AssistFeedbackService feedback,
                                                 OwnedLineupRecommendService lineup) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AssistProactiveCoachService proactive = mock(AssistProactiveCoachService.class);
        GameSessionManager sessions = mock(GameSessionManager.class);
        when(sessions.getOrNull(anyLong())).thenReturn(null);
        // 若调用方未配置同问限流/等级配额，给默认放行，避免 NPE
        try {
            when(quota.tryAcquireSameQuestion(anyLong(), any())).thenReturn(AssistQuotaLimiter.AcquireResult.ok());
        } catch (Exception ignored) {
            // already stubbed
        }
        try {
            when(quota.tryAcquire(anyLong(), any(), anyInt())).thenReturn(AssistQuotaLimiter.AcquireResult.ok());
            when(quota.tryAcquire(anyLong(), any(), anyInt(), anyBoolean())).thenReturn(AssistQuotaLimiter.AcquireResult.ok());
        } catch (Exception ignored) {
            // already stubbed
        }
        return new AssistNettyService(props, resolver, quota, coach, app, guide,
                new AssistAuditService(), feedback, lineup,
                mock(ExplorePathAdvisor.class), mock(QuestGuidanceService.class), executor,
                sessions, proactive);
    }

    private static PlayerContextResolver mockResolver(OptionalLong uid) {
        PlayerContextResolver resolver = mock(PlayerContextResolver.class);
        when(resolver.resolveUid(any())).thenReturn(uid);
        return resolver;
    }

    private static AssistQuotaLimiter alwaysAllowQuota() {
        AssistQuotaLimiter quota = mock(AssistQuotaLimiter.class);
        when(quota.tryAcquire(anyLong(), any())).thenReturn(AssistQuotaLimiter.AcquireResult.ok());
        when(quota.tryAcquire(anyLong(), any(), anyInt())).thenReturn(AssistQuotaLimiter.AcquireResult.ok());
        when(quota.tryAcquire(anyLong(), any(), anyInt(), anyBoolean())).thenReturn(AssistQuotaLimiter.AcquireResult.ok());
        when(quota.tryAcquireSameQuestion(anyLong(), any())).thenReturn(AssistQuotaLimiter.AcquireResult.ok());
        when(quota.tryAcquire(anyLong())).thenReturn(true);
        when(quota.tryAcquireCoach(anyLong())).thenReturn(true);
        when(quota.tryAcquireLlm(anyLong())).thenReturn(true);
        return quota;
    }
}
