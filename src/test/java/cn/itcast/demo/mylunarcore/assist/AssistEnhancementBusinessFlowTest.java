package cn.itcast.demo.mylunarcore.assist;

import cn.itcast.demo.mylunarcore.battle.BattleAutoService;
import cn.itcast.demo.mylunarcore.battle.BattleContext;
import cn.itcast.demo.mylunarcore.battle.BattleManager;
import cn.itcast.demo.mylunarcore.battle.BattleNettyService;
import cn.itcast.demo.mylunarcore.battle.BattleSnapshotService;
import cn.itcast.demo.mylunarcore.battle.EncounterConfig;
import cn.itcast.demo.mylunarcore.battle.EncounterConfigRepository;
import cn.itcast.demo.mylunarcore.battle.assist.HeuristicBattleAssistPolicy;
import cn.itcast.demo.mylunarcore.repo.BattleMonsterWaveRepository;
import cn.itcast.demo.mylunarcore.common.ActivityConfigService;
import cn.itcast.demo.mylunarcore.common.BattleEndedEvent;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.economy.RewardDistributor;
import cn.itcast.demo.mylunarcore.economy.ShopConfigRepository;
import cn.itcast.demo.mylunarcore.gacha.GachaConfigService;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.player.PlayerContextResolver;
import cn.itcast.demo.mylunarcore.player.PlayerSessionState;
import cn.itcast.demo.mylunarcore.protocol.AssistSystemProto;
import cn.itcast.demo.mylunarcore.quest.QuestConfigRepository;
import cn.itcast.demo.mylunarcore.quest.QuestProgressApplicationService;
import com.google.protobuf.ByteString;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AI 助手七项增强能力的业务流程切片测试。
 */
@DisplayName("AI 助手增强业务流程 AssistEnhancementBusinessFlow")
class AssistEnhancementBusinessFlowTest {

    private LunarCoreProperties props;
    private PlayerContextResolver resolver;
    private Channel channel;

    @BeforeEach
    void setUp() {
        props = new LunarCoreProperties();
        props.getAiAssist().setEnabled(true);
        props.getAiAssist().setSyncMode(true);
        props.getAiAssist().setVlmEnabled(true);
        props.getAiAssist().setPersonaEnabled(true);
        props.getAiAssist().setTtsEnabled(true);
        props.getAiAssist().setProactiveCoachEnabled(true);
        props.getAiAssist().setVoiceQuotaDiscountEnabled(true);
        props.getAiAssist().setBattleHintEnabled(true);
        props.getAiAssist().setProactiveFailStreak(2);
        props.getAiAssist().setProactiveBossLingerSeconds(10);
        props.getAiAssist().setProactiveInterruptScoreMax(0.95);
        props.getAiAssist().setProactiveCooldownSeconds(1);
        props.getAiAssist().setRagMinScore(0.5);
        props.getAiAssist().setRagFreshnessBoost(0.30);

        resolver = mock(PlayerContextResolver.class);
        when(resolver.resolveUid(any())).thenReturn(OptionalLong.of(77L));
        when(resolver.resolvePlayerId(any())).thenReturn(77);
        channel = mock(Channel.class);
        when(channel.isActive()).thenReturn(true);
        when(channel.writeAndFlush(any())).thenReturn(mock(ChannelFuture.class));
    }

    @Nested
    @DisplayName("1. VLM 截图解惑")
    class VlmFlow {

        @Test
        @DisplayName("非法 JPEG 应返回 retcode=4")
        void invalidJpegRejected() {
            VisualAssistService visual = newVisual();
            AssistSystemProto.UploadScreenshotScRsp rsp = visual.handleUpload(
                    AssistSystemProto.UploadScreenshotCsReq.newBuilder()
                            .setJpegBytes(ByteString.copyFrom(new byte[]{1, 2, 3}))
                            .setPlaneId(1)
                            .setQuestion("这个机关怎么解")
                            .build(),
                    channel);
            assertEquals(4, rsp.getRetcode());
        }

        @Test
        @DisplayName("合法截图应受理并推送 VisualHint 叠加")
        void validScreenshotPushesVisualHint() {
            VisualAssistService visual = newVisual();
            byte[] jpeg = new byte[]{(byte) 0xFF, (byte) 0xD8, 0x00, 0x01, 0x02};
            AssistSystemProto.UploadScreenshotScRsp rsp = visual.handleUpload(
                    AssistSystemProto.UploadScreenshotCsReq.newBuilder()
                            .setJpegBytes(ByteString.copyFrom(jpeg))
                            .setPlaneId(1)
                            .setPosX(40f).setPosZ(25f)
                            .setQuestion("这个机关怎么解")
                            .build(),
                    channel);
            assertEquals(0, rsp.getRetcode());
            assertFalse(rsp.getRequestId().isBlank());

            ArgumentCaptor<Object> cap = ArgumentCaptor.forClass(Object.class);
            verify(channel, atLeastOnce()).writeAndFlush(cap.capture());
            GamePacket pkt = (GamePacket) cap.getAllValues().stream()
                    .filter(o -> o instanceof GamePacket gp && gp.getCmdId() == CmdIds.ASSIST_VISUAL_HINT_SC_NOTIFY)
                    .findFirst()
                    .orElseThrow();
            assertEquals(CmdIds.ASSIST_VISUAL_HINT_SC_NOTIFY, pkt.getCmdId());
        }

        private VisualAssistService newVisual() {
            AssistQuotaLimiter quota = mock(AssistQuotaLimiter.class);
            when(quota.tryAcquire(anyLong(), any(), anyInt())).thenReturn(AssistQuotaLimiter.AcquireResult.ok());
            AssistFeatureContentRepository feature = mock(AssistFeatureContentRepository.class);
            when(feature.current()).thenReturn(new AssistFeatureContent(
                    1, List.of(),
                    List.of(new AssistFeatureContent.PoiLore(
                            "ruins_alpha", 1, List.of("ruins", "puzzle"),
                            40f, 0f, 25f, 12f, "古代星轨遗迹", "观星祭坛",
                            "石板上的符文顺序与天上星座方向一致。", "星轨学者")),
                    List.of(), Map.of(), List.of(), List.of()));
            AssistPersonaService persona = mock(AssistPersonaService.class);
            when(persona.wrapAnswer(anyLong(), anyString())).thenAnswer(inv -> inv.getArgument(1));
            when(persona.personaId(anyLong())).thenReturn("tingyun");
            when(persona.voiceId(anyLong())).thenReturn("tingyun_zh");
            return new VisualAssistService(props, resolver, quota, new AssistAuditService(),
                    feature, persona, new AssistTtsService(props),
                    new cn.itcast.demo.mylunarcore.assist.visual.VisualFrameTemporalAnalyzer(),
                    new cn.itcast.demo.mylunarcore.assist.visual.SpatialPuzzleHintBuilder(),
                    Executors.newSingleThreadExecutor());
        }
    }

    @Nested
    @DisplayName("2. 战斗建议一键落地 Auto")
    class ApplyAiSuggestionFlow {

        @Test
        @DisplayName("AskBattleHint 应带回 suggested_auto_override，Apply 后开启 Auto")
        void hintThenApplyEnablesAuto() {
            BattleManager battleManager = new BattleManager(mock(BattleSnapshotService.class));
            List<BattleMonsterWaveRepository.WaveConfig> waves = List.of(
                    new BattleMonsterWaveRepository.WaveConfig(1, 100, 1, "[101]", 5),
                    new BattleMonsterWaveRepository.WaveConfig(2, 100, 2, "[102]", 5));
            BattleContext ctx = BattleContext.createNew(88001L, 77, 1, 100, 1_700_000_000L, waves);
            battleManager.put(ctx);

            LunarCoreProperties battleProps = props;
            battleProps.getAiAssist().setBattleHintEnabled(true);
            HeuristicBattleAssistPolicy policy = new HeuristicBattleAssistPolicy(battleProps, featureRepo());

            ObjectProvider<BattleAutoService> autoProvider = provider(null);
            BattleNettyService battle = newBattleService(battleManager, policy, battleProps, autoProvider);

            AssistSystemProto.AskBattleHintScRsp hint = battle.handleAskBattleHint(
                    AssistSystemProto.AskBattleHintCsReq.newBuilder().setBattleId(88001L).build(),
                    channel);
            assertEquals(0, hint.getRetcode());
            assertTrue(hint.hasSuggestedAutoOverride());
            assertTrue(hint.getSuggestedSkillId() >= 1);

            AssistSystemProto.ApplyAiSuggestionScRsp applied = battle.handleApplyAiSuggestion(
                    AssistSystemProto.ApplyAiSuggestionCsReq.newBuilder()
                            .setBattleId(88001L)
                            .setEnableAuto(true)
                            .setOverride(hint.getSuggestedAutoOverride())
                            .build(),
                    channel);
            assertEquals(0, applied.getRetcode());
            assertTrue(applied.getAutoEnabled());
            assertTrue(ctx.isAutoBattle());
        }

        private AssistFeatureContentRepository featureRepo() {
            AssistFeatureContentRepository repo = mock(AssistFeatureContentRepository.class);
            when(repo.current()).thenReturn(new AssistFeatureContent(
                    1, List.of(), List.of(),
                    List.of(new AssistFeatureContent.EnemyWeakness(
                            101, "迅捷虫", List.of("beast"), List.of("ice"),
                            List.of("physical"), "优先控场再集火精英", 0.35)),
                    Map.of("1001", List.of("ice")), List.of(), List.of()));
            return repo;
        }
    }

    @Nested
    @DisplayName("3. 人设 + TTS")
    class PersonaTtsFlow {

        @Test
        @DisplayName("人设包装 + TTS 音频应进入 AiHintNotify")
        void personaAndTtsOnHintNotify() throws Exception {
            AssistPersonaRepository personaRepo = mock(AssistPersonaRepository.class);
            when(personaRepo.current()).thenReturn(new AssistPersonaConfig(1, "tingyun", List.of(
                    new AssistPersonaConfig.PersonaEntry("tingyun", "停云", 1102, "tingyun_zh", 0,
                            "温柔", "贵客请听停云一言——")), Map.of()));
            GameSessionManager sessions = mock(GameSessionManager.class);
            when(sessions.getOrNull(77L)).thenReturn(null);
            AssistPersonaService persona = new AssistPersonaService(props, personaRepo, sessions);
            AssistTtsService tts = new AssistTtsService(props);

            AssistNettyService netty = newAssistNetty(persona, tts, null, null);
            netty.pushAiHintNotify(channel, "req-1", "建议先破盾", "rule", List.of(),
                    "", "v1", "zh-CN", List.of(),
                    SuggestedAutoOverride.of(1, 1, false, "集火"),
                    "tingyun", 77L);

            ArgumentCaptor<Object> cap = ArgumentCaptor.forClass(Object.class);
            verify(channel).writeAndFlush(cap.capture());
            GamePacket pkt = (GamePacket) cap.getValue();
            assertEquals(CmdIds.AI_HINT_SC_NOTIFY, pkt.getCmdId());
            AssistSystemProto.AiHintNotify notify =
                    AssistSystemProto.AiHintNotify.parseFrom(pkt.getPayload());
            assertEquals("tingyun", notify.getVoicePersonaId());
            assertFalse(notify.getAudioChunk().isEmpty());
            assertEquals("opus", notify.getAudioFormat());
            assertTrue(notify.hasSuggestedAutoOverride());
        }
    }

    @Nested
    @DisplayName("4. 主动教练：连败 + BOSS 徘徊")
    class ProactiveFlow {

        @Test
        @DisplayName("连续失败达标应推送配队建议")
        void failStreakPushesLineupHint() throws Exception {
            GameSessionManager sessions = mock(GameSessionManager.class);
            GameSession session = mock(GameSession.class);
            when(session.getChannel()).thenReturn(channel);
            when(sessions.getOrNull(77L)).thenReturn(session);

            AssistFeatureContentRepository feature = mock(AssistFeatureContentRepository.class);
            when(feature.current()).thenReturn(AssistFeatureContent.empty());
            PlayerCoachApplicationService coach = mock(PlayerCoachApplicationService.class);
            when(coach.suggest(anyLong(), anyInt())).thenReturn(List.of(
                    new CoachHint("x", "battle", 90, "配队", "打开", "OPEN_LINEUP", 0)));

            AssistProactiveCoachService proactive = new AssistProactiveCoachService(
                    props, sessions, coach, mock(QuestProgressApplicationService.class),
                    feature, provider(null), provider(null), provider(null));

            proactive.onBattleEnded(new BattleEndedEvent(1L, 77, 0, "FAIL", 1L));
            proactive.onBattleEnded(new BattleEndedEvent(2L, 77, 0, "FAIL", 2L));

            ArgumentCaptor<Object> cap = ArgumentCaptor.forClass(Object.class);
            verify(channel, atLeastOnce()).writeAndFlush(cap.capture());
            boolean hit = cap.getAllValues().stream().anyMatch(o ->
                    o instanceof GamePacket gp && gp.getCmdId() == CmdIds.AI_HINT_SC_NOTIFY);
            assertTrue(hit);
        }

        @Test
        @DisplayName("BOSS 门口徘徊达标应推送 scene_stay")
        void bossLingerPushesStayHint() throws Exception {
            GameSessionManager sessions = mock(GameSessionManager.class);
            GameSession session = mock(GameSession.class);
            when(session.getChannel()).thenReturn(channel);
            when(session.getSessionState()).thenReturn(PlayerSessionState.SCENE);
            when(sessions.getOrNull(77L)).thenReturn(session);

            AssistFeatureContentRepository feature = mock(AssistFeatureContentRepository.class);
            when(feature.current()).thenReturn(new AssistFeatureContent(
                    1, List.of(),
                    List.of(new AssistFeatureContent.PoiLore(
                            "boss_gate", 1, List.of("boss"),
                            10f, 0f, 10f, 8f, "BOSS 门口", "", "", "")),
                    List.of(), Map.of(), List.of(), List.of()));

            AssistProactiveCoachService proactive = new AssistProactiveCoachService(
                    props, sessions, mock(PlayerCoachApplicationService.class),
                    mock(QuestProgressApplicationService.class),
                    feature, provider(null), provider(null), provider(null));

            // 模拟 >10s 徘徊并来回移动
            java.lang.reflect.Field field = AssistProactiveCoachService.class.getDeclaredField("lingerByUid");
            // 直接多次 onPlayerMove：用时间推进较难，改为反复振荡并降低阈值
            props.getAiAssist().setProactiveBossLingerSeconds(0);
            for (int i = 0; i < 6; i++) {
                float x = 10f + (i % 2 == 0 ? 2f : -2f);
                proactive.onPlayerMove(77L, 1, x, 0f, 10f);
                Thread.sleep(5);
            }
            // linger sinceMs 与 now 几乎相同，需手动把 since 往前拨
            // 通过反射写入 linger 状态
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            var map = (java.util.concurrent.ConcurrentHashMap<Long, Object>) field.get(proactive);
            // 重新以长停留触发：先建立状态再改 since
            proactive.onPlayerMove(77L, 1, 12f, 0f, 10f);
            Object state = map.get(77L);
            assertTrue(state != null);
            java.lang.reflect.Field since = state.getClass().getDeclaredField("sinceMs");
            since.setAccessible(true);
            since.set(state, System.currentTimeMillis() - 20_000L);
            java.lang.reflect.Field osc = state.getClass().getDeclaredField("oscillateCount");
            osc.setAccessible(true);
            osc.set(state, 3);
            proactive.onPlayerMove(77L, 1, 8f, 0f, 10f);

            ArgumentCaptor<Object> cap = ArgumentCaptor.forClass(Object.class);
            verify(channel, atLeastOnce()).writeAndFlush(cap.capture());
            boolean hit = false;
            for (Object o : cap.getAllValues()) {
                if (o instanceof GamePacket gp && gp.getCmdId() == CmdIds.AI_HINT_SC_NOTIFY) {
                    AssistSystemProto.AiHintNotify n =
                            AssistSystemProto.AiHintNotify.parseFrom(gp.getPayload());
                    if ("proactive_scene_stay".equals(n.getHintType()) || n.getSource().contains("boss")) {
                        hit = true;
                    }
                }
            }
            assertTrue(hit);
        }

        @Test
        @DisplayName("战斗中打扰系数应偏高")
        void interruptScoreHighInBattle() {
            GameSessionManager sessions = mock(GameSessionManager.class);
            GameSession session = mock(GameSession.class);
            when(session.getSessionState()).thenReturn(PlayerSessionState.BATTLE);
            when(sessions.getOrNull(77L)).thenReturn(session);
            AssistProactiveCoachService proactive = new AssistProactiveCoachService(
                    props, sessions, mock(PlayerCoachApplicationService.class),
                    mock(QuestProgressApplicationService.class),
                    mock(AssistFeatureContentRepository.class),
                    provider(null), provider(null), provider(null));
            assertTrue(proactive.interruptScore(77L, false) >= 0.4);
        }
    }

    @Nested
    @DisplayName("5. 语音输入配额减免")
    class VoiceQuotaFlow {

        @Test
        @DisplayName("VOICE 输入应走折扣路径并回传 input_type")
        void voiceAskUsesDiscountAndEchoesType() {
            AssistQuotaLimiter quota = mock(AssistQuotaLimiter.class);
            when(quota.tryAcquireSameQuestion(anyLong(), any())).thenReturn(AssistQuotaLimiter.AcquireResult.ok());
            when(quota.tryAcquire(eq(77L), eq(AssistQuotaLimiter.QuotaKind.LLM), anyInt(), eq(true)))
                    .thenReturn(AssistQuotaLimiter.AcquireResult.ok());

            AiAssistApplicationService app = mock(AiAssistApplicationService.class);
            when(app.tryLocalFast(anyLong(), anyString(), anyString(), any(), any(), any()))
                    .thenReturn(Optional.empty());
            when(app.ask(anyLong(), anyString(), anyString(), any(), any(), any()))
                    .thenReturn(AssistAnswer.of(0, "语音答复", "rule", List.of(), List.of()));

            AssistNettyService netty = newAssistNettyWithQuota(quota, app,
                    mock(AssistPersonaService.class), new AssistTtsService(props));

            AssistSystemProto.AskAiAssistScRsp rsp = netty.handleAskAiAssist(
                    AssistSystemProto.AskAiAssistCsReq.newBuilder()
                            .setQuestion("这个怪怎么打")
                            .setScene("battle")
                            .setInputType(AssistSystemProto.AssistInputType.ASSIST_INPUT_VOICE)
                            .build(),
                    channel);
            assertEquals(0, rsp.getRetcode());
            assertEquals(AssistSystemProto.AssistInputType.ASSIST_INPUT_VOICE, rsp.getInputType());
            verify(quota).tryAcquire(eq(77L), eq(AssistQuotaLimiter.QuotaKind.LLM), anyInt(), eq(true));
        }

        @Test
        @DisplayName("真实配额器：语音应跳过 daily 扣减")
        void realQuotaVoiceSkipsDaily() {
            AssistQuotaLimiter limiter = new AssistQuotaLimiter(props);
            props.getAiAssist().setDailyLlmBudget(1);
            props.getAiAssist().setFreeDailyLlmBudget(1);
            props.getAiAssist().setLlmPerUidPerMinute(20);
            // 第一次文本耗尽 daily
            assertTrue(limiter.tryAcquire(9L, AssistQuotaLimiter.QuotaKind.LLM, 1, false).allowed());
            assertFalse(limiter.tryAcquire(9L, AssistQuotaLimiter.QuotaKind.LLM, 1, false).allowed());
            // 语音仍可过
            assertTrue(limiter.tryAcquire(9L, AssistQuotaLimiter.QuotaKind.LLM, 1, true).allowed());
        }
    }

    @Nested
    @DisplayName("6. UI_INTERACTION 深链")
    class UiDeepLinkFlow {

        @Test
        @DisplayName("合成台意图：导航 + DeepLink Notify")
        void synthIntentPushesNavigateAndDeepLink() {
            ExplorePathAdvisor explore = mock(ExplorePathAdvisor.class);
            when(explore.advise(0, 1)).thenReturn(new ExplorePathAdvisor.PathAdvice(
                    0, "r1", "材料房", List.of(new AssistFeatureContent.Waypoint(1, 0, 1, "门", "quest")),
                    List.of(), "去合成"));
            IntentExecuteHandler handler = new IntentExecuteHandler(explore, mock(QuestGuidanceService.class));
            Optional<IntentExecuteHandler.ExecuteResult> r = handler.tryExecute(77L, "去合成台做材料");
            assertTrue(r.isPresent());
            assertEquals(IntentExecuteHandler.IntentKind.UI_INTERACTION, r.get().kind());
            assertEquals("OPEN_SYNTH", r.get().uiAction());

            handler.pushToChannel(channel, r.get());
            ArgumentCaptor<Object> cap = ArgumentCaptor.forClass(Object.class);
            verify(channel, atLeastOnce()).writeAndFlush(cap.capture());
            List<Integer> cmds = cap.getAllValues().stream()
                    .filter(GamePacket.class::isInstance)
                    .map(o -> ((GamePacket) o).getCmdId())
                    .toList();
            assertTrue(cmds.contains(CmdIds.SCENE_NAVIGATE_SC_NOTIFY));
            assertTrue(cmds.contains(CmdIds.ASSIST_DEEP_LINK_SC_NOTIFY));
        }

        @Test
        @DisplayName("AskAiAssist 命中意图时应短路径返回 intent-execute")
        void askAiAssistIntentShortCircuit() {
            ExplorePathAdvisor explore = mock(ExplorePathAdvisor.class);
            when(explore.advise(0, 1)).thenReturn(new ExplorePathAdvisor.PathAdvice(
                    0, "r1", "跃迁", List.of(), List.of(), "抽卡"));
            IntentExecuteHandler handler = new IntentExecuteHandler(explore, mock(QuestGuidanceService.class));
            AssistNettyService netty = newAssistNetty(null, null, handler, null);

            AssistSystemProto.AskAiAssistScRsp rsp = netty.handleAskAiAssist(
                    AssistSystemProto.AskAiAssistCsReq.newBuilder()
                            .setQuestion("我想去抽卡")
                            .setScene("gacha")
                            .build(),
                    channel);
            assertEquals(0, rsp.getRetcode());
            assertEquals("intent-execute", rsp.getSource());
            verify(channel, atLeastOnce()).writeAndFlush(any());
        }
    }

    @Nested
    @DisplayName("7. RAG 公告灌库与新鲜度")
    class RagIngestFlow {

        @Test
        @DisplayName("公告灌库后打新 BOSS 优先命中新语料")
        void freshAnnounceBeatsStale() {
            ActivityConfigService activities = mock(ActivityConfigService.class);
            when(activities.snapshot()).thenReturn(Map.of());
            QuestConfigRepository quests = mock(QuestConfigRepository.class);
            when(quests.listAll()).thenReturn(List.of());
            GachaConfigService gacha = mock(GachaConfigService.class);
            when(gacha.snapshot()).thenReturn(Map.of());
            ShopConfigRepository shop = mock(ShopConfigRepository.class);
            when(shop.snapshot()).thenReturn(Map.of());
            AvatarUsageStatsService usage = mock(AvatarUsageStatsService.class);
            when(usage.summaryText(10)).thenReturn("");
            when(usage.topN(10)).thenReturn(List.of());
            WorldLoreService lore = mock(WorldLoreService.class);
            when(lore.toKnowledgeChunks()).thenReturn(List.of(
                    new RagKnowledgeService.KnowledgeChunk("lore:old_boss", "lore",
                            "旧版 BOSS 先打小怪再打本体", System.currentTimeMillis() - 90L * 86_400_000L)
            ));

            RagKnowledgeService rag = new RagKnowledgeService(
                    activities, quests, gacha, shop, usage, props, lore);
            rag.init();
            rag.ingestAnnouncement("v4.0", "版本活动", "新 BOSS 机制：先破盾再爆发 集火精英",
                    System.currentTimeMillis());

            List<RagKnowledgeService.KnowledgeChunk> hits = rag.search("打新 BOSS 破盾", "announce", 3);
            assertFalse(hits.isEmpty());
            assertTrue(hits.get(0).id().startsWith("announce:") || hits.get(0).text().contains("破盾"));
        }
    }

    // ---------- helpers ----------

    private AssistNettyService newAssistNetty(AssistPersonaService persona, AssistTtsService tts,
                                              IntentExecuteHandler intent, VisualAssistService visual) {
        AssistQuotaLimiter quota = mock(AssistQuotaLimiter.class);
        when(quota.tryAcquireSameQuestion(anyLong(), any())).thenReturn(AssistQuotaLimiter.AcquireResult.ok());
        when(quota.tryAcquire(anyLong(), any(), anyInt())).thenReturn(AssistQuotaLimiter.AcquireResult.ok());
        when(quota.tryAcquire(anyLong(), any(), anyInt(), anyBoolean()))
                .thenReturn(AssistQuotaLimiter.AcquireResult.ok());
        AiAssistApplicationService app = mock(AiAssistApplicationService.class);
        when(app.tryLocalFast(anyLong(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(Optional.empty());
        when(app.ask(anyLong(), anyString(), anyString(), any(), any(), any()))
                .thenReturn(AssistAnswer.of(0, "ok", "rule", List.of(), List.of()));
        return newAssistNettyWithQuota(quota, app, persona, tts, intent, visual);
    }

    private AssistNettyService newAssistNettyWithQuota(AssistQuotaLimiter quota,
                                                       AiAssistApplicationService app,
                                                       AssistPersonaService persona,
                                                       AssistTtsService tts) {
        return newAssistNettyWithQuota(quota, app, persona, tts, null, null);
    }

    private AssistNettyService newAssistNettyWithQuota(AssistQuotaLimiter quota,
                                                       AiAssistApplicationService app,
                                                       AssistPersonaService persona,
                                                       AssistTtsService tts,
                                                       IntentExecuteHandler intent,
                                                       VisualAssistService visual) {
        if (persona == null) {
            persona = mock(AssistPersonaService.class);
            when(persona.wrapAnswer(anyLong(), anyString())).thenAnswer(inv -> inv.getArgument(1));
            when(persona.personaId(anyLong())).thenReturn("");
            when(persona.voiceId(anyLong())).thenReturn("");
        }
        if (tts == null) {
            tts = new AssistTtsService(props);
        }
        AssistProactiveCoachService proactive = mock(AssistProactiveCoachService.class);
        GameSessionManager sessions = mock(GameSessionManager.class);
        when(sessions.getOrNull(anyLong())).thenReturn(null);
        AssistPersonaService finalPersona = persona;
        AssistTtsService finalTts = tts;
        return new AssistNettyService(props, resolver, quota,
                mock(PlayerCoachApplicationService.class), app, mock(GuidePackRepository.class),
                new AssistAuditService(),
                new AssistFeedbackService(mock(cn.itcast.demo.mylunarcore.common.BusinessMetrics.class),
                        mock(cn.itcast.demo.mylunarcore.analytics.AnalyticsEventPublisher.class)),
                mock(OwnedLineupRecommendService.class),
                mock(ExplorePathAdvisor.class), mock(QuestGuidanceService.class),
                Executors.newSingleThreadExecutor(), sessions, proactive,
                provider(intent), provider(finalPersona), provider(finalTts), provider(visual));
    }

    private BattleNettyService newBattleService(BattleManager battleManager,
                                                HeuristicBattleAssistPolicy policy,
                                                LunarCoreProperties battleProps,
                                                ObjectProvider<BattleAutoService> autoProvider) {
        when(resolver.resolveUid(any())).thenReturn(OptionalLong.of(77L));
        EncounterConfigRepository encounterRepo = mock(EncounterConfigRepository.class);
        when(encounterRepo.current()).thenReturn(EncounterConfig.empty());
        RewardDistributor rewardDistributor = mock(RewardDistributor.class);
        when(rewardDistributor.grantBattleRewards(anyInt(), any(), anyInt(), anyString()))
                .thenReturn(List.of());
        return new BattleNettyService(
                battleManager,
                mock(cn.itcast.demo.mylunarcore.repo.BattleRepository.class),
                mock(cn.itcast.demo.mylunarcore.repo.BattleMonsterWaveRepository.class),
                mock(cn.itcast.demo.mylunarcore.repo.MazeSkillRepository.class),
                mock(cn.itcast.demo.mylunarcore.repo.MazeSkillActionRepository.class),
                mock(cn.itcast.demo.mylunarcore.repo.MazeBuffRepository.class),
                mock(cn.itcast.demo.mylunarcore.repo.SummonUnitConfigRepository.class),
                new cn.itcast.demo.mylunarcore.battle.BattleSceneFactory(),
                mock(cn.itcast.demo.mylunarcore.common.GameEventPublisher.class),
                resolver,
                policy,
                battleProps,
                mock(cn.itcast.demo.mylunarcore.scene.SceneManager.class),
                mock(cn.itcast.demo.mylunarcore.scene.SceneSyncBroadcaster.class),
                mock(GameSessionManager.class),
                encounterRepo,
                rewardDistributor,
                mock(cn.itcast.demo.mylunarcore.scene.ZoneWorldService.class),
                mock(cn.itcast.demo.mylunarcore.scene.ZoneManager.class),
                mock(cn.itcast.demo.mylunarcore.anticheat.BattleAuditService.class),
                mock(cn.itcast.demo.mylunarcore.party.PartyService.class),
                new cn.itcast.demo.mylunarcore.anticheat.BattleDeterministicValidator(
                        mock(cn.itcast.demo.mylunarcore.anticheat.BattleAuditService.class)),
                emptyProvider(), emptyProvider(), emptyProvider(), emptyProvider(), emptyProvider(),
                autoProvider);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> emptyProvider() {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(null);
        return p;
    }
}
