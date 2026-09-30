package cn.itcast.demo.mylunarcore.assist;

import cn.itcast.demo.mylunarcore.assist.cultivation.AssistCultivationAdvisorService;
import cn.itcast.demo.mylunarcore.assist.funnel.AssistIntentFunnelService;
import cn.itcast.demo.mylunarcore.assist.memory.AssistGachaMemoryHook;
import cn.itcast.demo.mylunarcore.assist.memory.AssistLongTermMemoryService;
import cn.itcast.demo.mylunarcore.assist.render.AssistStructuredRenderService;
import cn.itcast.demo.mylunarcore.assist.visual.SpatialPuzzleHintBuilder;
import cn.itcast.demo.mylunarcore.assist.visual.VisualFrameTemporalAnalyzer;
import cn.itcast.demo.mylunarcore.battle.BattleContext;
import cn.itcast.demo.mylunarcore.battle.BattleManager;
import cn.itcast.demo.mylunarcore.battle.BattleNettyService;
import cn.itcast.demo.mylunarcore.battle.BattleSnapshotService;
import cn.itcast.demo.mylunarcore.battle.EncounterConfig;
import cn.itcast.demo.mylunarcore.battle.EncounterConfigRepository;
import cn.itcast.demo.mylunarcore.battle.assist.BattleStateVectorSerializer;
import cn.itcast.demo.mylunarcore.battle.assist.HeuristicBattleAssistPolicy;
import cn.itcast.demo.mylunarcore.character.CharacterDevelopmentNettyService;
import cn.itcast.demo.mylunarcore.character.DevelopmentPlanService;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.economy.RewardDistributor;
import cn.itcast.demo.mylunarcore.item.ItemApplicationService;
import cn.itcast.demo.mylunarcore.model.AvatarEntity;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.player.StaminaService;
import cn.itcast.demo.mylunarcore.protocol.CharacterSystemProto;
import cn.itcast.demo.mylunarcore.repo.AvatarRepository;
import cn.itcast.demo.mylunarcore.equipment.EquipmentAffixService;
import cn.itcast.demo.mylunarcore.equipment.EquipmentAffixSnapshotService;
import cn.itcast.demo.mylunarcore.model.AvatarEntity;
import cn.itcast.demo.mylunarcore.model.GameItemEntity;
import cn.itcast.demo.mylunarcore.model.PlayerData;
import cn.itcast.demo.mylunarcore.model.PlayerEntity;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.player.PlayerContextResolver;
import cn.itcast.demo.mylunarcore.protocol.AssistSystemProto;
import cn.itcast.demo.mylunarcore.repo.BattleMonsterWaveRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.OptionalLong;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 六项 AI 沉浸增强能力的端到端集成测试（协议 + 业务链路）。
 */
@DisplayName("AI 沉浸增强集成测试 AssistImmersionIntegration")
class AssistImmersionIntegrationTest {

    private LunarCoreProperties props;
    private PlayerContextResolver resolver;
    private Channel channel;

    @BeforeEach
    void setUp() {
        props = new LunarCoreProperties();
        props.getAiAssist().setEnabled(true);
        props.getAiAssist().setSyncMode(true);
        props.getAiAssist().setVlmEnabled(true);
        props.getAiAssist().setBattleHintEnabled(true);
        props.getAiAssist().setIntentFunnelEnabled(true);
        props.getAiAssist().setAnswerCacheTtlSeconds(120);
        resolver = mock(PlayerContextResolver.class);
        when(resolver.resolveUid(any())).thenReturn(OptionalLong.of(77L));
        when(resolver.resolvePlayerId(any())).thenReturn(77);
        channel = mock(Channel.class);
        when(channel.isActive()).thenReturn(true);
        when(channel.writeAndFlush(any())).thenReturn(mock(ChannelFuture.class));
    }

    @Nested
    @DisplayName("1. 箱庭空间智能 VLM + 锚点链")
    class SpatialVlm {

        @Test
        @DisplayName("机关提问应推送含 anchor_chains 与 sequence_step 的 1100")
        void puzzleUploadPushesAnchorChains() throws Exception {
            VisualAssistService visual = newVisual();
            byte[] jpeg = new byte[]{(byte) 0xFF, (byte) 0xD8, 0x11, 0x22, 0x33};
            visual.handleUpload(
                    AssistSystemProto.UploadScreenshotCsReq.newBuilder()
                            .setJpegBytes(ByteString.copyFrom(jpeg))
                            .setPlaneId(1)
                            .setPosX(40f).setPosZ(25f)
                            .setQuestion("这个机关怎么开")
                            .build(),
                    channel);

            AssistSystemProto.AssistVisualHintScNotify notify = captureVisualHint();
            assertFalse(notify.getAnchorChainsList().isEmpty());
            assertEquals(3, notify.getAnchorChains(0).getAnchorsCount());
            assertEquals(1, notify.getAnchorChains(0).getAnchors(0).getSequenceStep());
            assertTrue(notify.getAnchorChains(0).getAnchors(0).getDirectionHint().contains("花坛"));
            assertTrue(notify.getEstimatedWaitMs() > 0);
            assertFalse(notify.getOverlaysList().isEmpty());
            assertTrue(notify.getOverlays(0).getSequenceStep() >= 1);
        }

        @Test
        @DisplayName("时序帧对比应识别连续截图差异")
        void temporalAnalyzerDetectsMotion() throws InterruptedException {
            VisualFrameTemporalAnalyzer analyzer = new VisualFrameTemporalAnalyzer();
            byte[] f1 = new byte[]{(byte) 0xFF, (byte) 0xD8, 1, 2, 3, 4, 5, 6, 7, 8};
            byte[] f2 = new byte[]{(byte) 0xFF, (byte) 0xD8, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0};
            analyzer.analyzeAndStore(99L, f1, 0, 0, 0);
            Thread.sleep(30);
            var hint = analyzer.analyzeAndStore(99L, f2, 5, 0, 5);
            assertTrue(hint.motionDetected());
            SpatialPuzzleHintBuilder.PuzzleChain chain = new SpatialPuzzleHintBuilder()
                    .build(1, 0, 0, 0, null, hint);
            assertTrue(chain.narrative().contains("位移") || chain.narrative().contains("浮台"));
        }
    }

    @Nested
    @DisplayName("2. 动态战斗策略 + 微观干预")
    class BattleMicro {

        @Test
        @DisplayName("AskBattleHint 应返回 battle_state_summary 与 lock_next_skill")
        void battleHintIncludesStateAndMicro() {
            BattleManager mgr = new BattleManager(mock(BattleSnapshotService.class));
            BattleContext ctx = BattleContext.createNew(9001L, 77, 1, 100, 1_700_000_000L,
                    List.of(new BattleMonsterWaveRepository.WaveConfig(1, 100, 1, "[101]", 5)));
            ctx.setTeamSkillPoints(2);
            ctx.setUltEnergyPercent(92);
            var monsters = ctx.listAliveMonsterIdsInCurrentWave();
            if (!monsters.isEmpty()) {
                ctx.getEntity(monsters.get(0)).addBuffStack(BattleStateVectorSerializer.BUFF_ENRAGE_WARN, 1, 1);
            }
            mgr.put(ctx);

            HeuristicBattleAssistPolicy policy = new HeuristicBattleAssistPolicy(props, featureRepo());
            BattleNettyService battle = newBattleService(mgr, policy, emptyProvider());

            AssistSystemProto.AskBattleHintScRsp hint = battle.handleAskBattleHint(
                    AssistSystemProto.AskBattleHintCsReq.newBuilder().setBattleId(9001L).build(), channel);
            assertEquals(0, hint.getRetcode());
            assertFalse(hint.getBattleStateSummary().isBlank());
            assertTrue(hint.getEstimatedWaitMs() > 0);
            assertTrue(hint.hasSuggestedAutoOverride());
        }

        @Test
        @DisplayName("ApplyAiSuggestion 应写入微观干预队列")
        void applyMicroInterventionQueuesSkill() {
            BattleManager mgr = new BattleManager(mock(BattleSnapshotService.class));
            BattleContext ctx = BattleContext.createNew(9002L, 77, 1, 100, 1_700_000_000L,
                    List.of(new BattleMonsterWaveRepository.WaveConfig(1, 100, 1, "[101]", 5)));
            mgr.put(ctx);

            HeuristicBattleAssistPolicy policy = new HeuristicBattleAssistPolicy(props, featureRepo());
            BattleNettyService battle = newBattleService(mgr, policy, emptyProvider());

            AssistSystemProto.ApplyAiSuggestionScRsp applied = battle.handleApplyAiSuggestion(
                    AssistSystemProto.ApplyAiSuggestionCsReq.newBuilder()
                            .setBattleId(9002L)
                            .setEnableAuto(true)
                            .setOverride(AssistSystemProto.SuggestedAutoOverride.newBuilder()
                                    .setTargetFocus(1)
                                    .setSkillPriority(1)
                                    .setLockNextSkillId(2)
                                    .setLockCasterEntityId(77)
                                    .setAutoRevertAfterAction(true)
                                    .build())
                            .build(),
                    channel);
            assertEquals(0, applied.getRetcode());
            assertTrue(ctx.hasPendingManualUlt());
        }
    }

    @Nested
    @DisplayName("3. 长期记忆 + 情感陪伴")
    class LongTermMemory {

        @Test
        @DisplayName("连败记忆应写入 prompt 并触发治愈系判定")
        void memoryRecordsAndRecalls() {
            AssistLongTermMemoryService memory = new AssistLongTermMemoryService(props,
                    mock(ObjectProvider.class));
            memory.record(77L, "battle_fail", "连败愤怒", "连续失败 3 次", 0.9);
            String block = memory.promptBlock(77L, "battle");
            assertTrue(block.contains("连败愤怒"));
            assertTrue(memory.shouldUseHealingPersona(77L));
        }

        @Test
        @DisplayName("抽卡歪池应写入 gacha_fail 记忆")
        void gachaHookRecordsFail() {
            AssistLongTermMemoryService memory = new AssistLongTermMemoryService(props,
                    mock(ObjectProvider.class));
            AssistGachaMemoryHook hook = new AssistGachaMemoryHook(memory);
            hook.onGachaResult(77L, 11, false, true, "黄泉");
            String block = memory.promptBlock(77L, "gacha");
            assertTrue(block.contains("gacha") || block.contains("banner"));
        }
    }

    @Nested
    @DisplayName("4. 三级漏斗 + estimated_wait_ms")
    class IntentFunnel {

        @Test
        @DisplayName("一级漏斗「怎么选」应 <100ms 返回")
        void tier1ChoiceFast() {
            AssistAnswerCache cache = new AssistAnswerCache(props);
            cache.init();
            AssistIntentFunnelService funnel = new AssistIntentFunnelService(props, cache);
            var hit = funnel.tryTier1Local(1L, "这个选项怎么选", "general", "", "v1", "zh-CN");
            assertTrue(hit.isPresent());
            assertTrue(hit.get().estimatedWaitMs() < 100);
        }

        @Test
        @DisplayName("二级相似缓存应命中相近问句")
        void tier2SimilarCache() {
            AssistAnswerCache cache = new AssistAnswerCache(props);
            cache.init();
            cache.put(1L, "general", "怎么升级角色等级",
                    AssistAnswer.of(0, "打开养成界面升级", "rule", List.of(), List.of()));
            AssistIntentFunnelService funnel = new AssistIntentFunnelService(props, cache);
            var hit = funnel.tryTier2Cache(1L, "怎么升级角色等级呢", "general");
            assertTrue(hit.isPresent());
            assertTrue(hit.get().estimatedWaitMs() < 100);
        }

        @Test
        @DisplayName("远程路径 estimated_wait 应随场景变化")
        void remoteWaitByScene() {
            AssistIntentFunnelService funnel = new AssistIntentFunnelService(props, new AssistAnswerCache(props));
            int battle = funnel.estimateRemoteWaitMs("怎么打这个boss", "battle");
            int nearby = funnel.estimateRemoteWaitMs("附近有什么", "general");
            assertTrue(battle >= nearby);
        }
    }

    @Nested
    @DisplayName("5. AIGC 结构化渲染")
    class StructuredRender {

        @Test
        @DisplayName("配队问题应生成 TEAM_CARD JSON")
        void teamCardRender() {
            PlayerData data = samplePlayerData();
            AssistStructuredRenderService render = new AssistStructuredRenderService();
            var out = render.tryRender("这三个角色能组吗", "general", data);
            assertTrue(out.present());
            assertEquals("TEAM_CARD", out.payload().renderType());
            assertTrue(out.payload().payloadJson().contains("slots"));
        }

        @Test
        @DisplayName("模拟宇宙应生成祝福优先级 JSON")
        void simBlessingRender() {
            AssistStructuredRenderService render = new AssistStructuredRenderService();
            var out = render.tryRender("模拟宇宙祝福怎么拿", "general", samplePlayerData());
            assertTrue(out.present());
            assertEquals("SIM_BLESSING", out.payload().renderType());
            assertTrue(out.payload().payloadJson().contains("priorityTiers"));
        }
    }

    @Nested
    @DisplayName("6. 养成决策 + 装备快照")
    class Cultivation {

        @Test
        @DisplayName("对比养成应给出 expected_dmg 建议")
        void cultivationCompareAdvice() {
            EquipmentAffixService affix = new EquipmentAffixService(new ObjectMapper());
            AssistCultivationAdvisorService advisor = new AssistCultivationAdvisorService(affix);
            var advice = advisor.advise(samplePlayerData(), "先拉真理医生还是托帕");
            assertTrue(advice.present());
            assertTrue(advice.expectedDmgIncreasePercent() >= 5);
            assertFalse(advice.reason().isBlank());
        }

        @Test
        @DisplayName("玩家画像应含 equipmentSnapshot")
        void profileIncludesEquipment() {
            EquipmentAffixSnapshotService snap = new EquipmentAffixSnapshotService(
                    new EquipmentAffixService(new ObjectMapper()));
            AssistPlayerProfileService profile = new AssistPlayerProfileService(snap);
            Map<String, Object> p = profile.buildProfile(samplePlayerData(), List.of());
            assertTrue(p.containsKey("equipmentSnapshot"));
        }

        @Test
        @DisplayName("1046 协议应返回 expected_dmg_increase_percent")
        void protocol1046CarriesExpectedDmg() {
            AvatarRepository avatars = mock(AvatarRepository.class);
            AvatarEntity avatar = new AvatarEntity();
            avatar.setAvatarId(1001);
            avatar.setLevel(20);
            avatar.setPromotion(0);
            when(avatars.findAvatar(77, 1001)).thenReturn(avatar);
            ItemApplicationService items = mock(ItemApplicationService.class);
            when(items.listBagItems(anyInt(), anyInt(), anyInt(), anyInt())).thenReturn(List.of());
            StaminaService stamina = mock(StaminaService.class);
            when(stamina.config()).thenReturn(new StaminaService.StaminaConfig(240, 1, 60_000L, 40, 20, 8, 101,
                    List.of(50), 60));
            when(stamina.snapshot(77)).thenReturn(new StaminaService.StaminaSnapshot(240, 240, 0, 8, 0L, 0, 40));

            GameSessionManager sessions = mock(GameSessionManager.class);
            GameSession session = mock(GameSession.class);
            when(session.getPlayerData()).thenReturn(samplePlayerData());
            when(sessions.getOrNull(77)).thenReturn(session);

            AssistCultivationAdvisorService advisor = new AssistCultivationAdvisorService(
                    new EquipmentAffixService(new ObjectMapper()));
            CharacterDevelopmentNettyService netty = new CharacterDevelopmentNettyService(
                    new DevelopmentPlanService(avatars, items, provider(stamina)),
                    resolver, sessions, advisor);

            CharacterSystemProto.CalculateUpgradeMaterialsScRsp rsp = netty.handleCalculate(
                    CharacterSystemProto.CalculateUpgradeMaterialsCsReq.newBuilder()
                            .setAvatarId(1001).setTargetLevel(80).build(),
                    channel);
            assertEquals(0, rsp.getRetcode());
            assertTrue(rsp.getExpectedDmgIncreasePercent() >= 5);
            assertTrue(rsp.getRecommendedPriorityAvatarId() > 0);
            assertFalse(rsp.getCultivationReason().isBlank());
        }
    }

    @Nested
    @DisplayName("7. 协议映射 Notify/Rsp")
    class ProtocolMapping {

        @Test
        @DisplayName("AssistAnswer 应映射 render 与 estimated_wait 到 AskAiAssistScRsp")
        void askRspCarriesRenderFields() {
            AssistAnswer answer = AssistAnswer.of(0, "阵容海报", "structured-render", List.of(), List.of(),
                    "", "v1", "zh-CN", List.of(), "", true, null, "", 0,
                    2500, "TEAM_CARD", "{\"title\":\"推荐阵容\"}");
            AssistSystemProto.AskAiAssistScRsp rsp = AssistNettyService.toAskRsp(answer);
            assertEquals("TEAM_CARD", rsp.getRenderType());
            assertEquals(2500, rsp.getEstimatedWaitMs());
            assertTrue(rsp.getRenderPayloadJson().contains("推荐阵容"));
        }

        @Test
        @DisplayName("pushAiHintNotify 应携带 render 字段")
        void hintNotifyCarriesRender() throws Exception {
            AssistAnswer answer = AssistAnswer.of(0, "test", "llm", List.of(), List.of(),
                    "", "v1", "zh-CN", List.of(), "", true, null, "tingyun", 0,
                    800, "TEAM_CARD", "{\"slots\":[]}");
            AssistNettyService netty = minimalAssistNetty();
            netty.pushAiHintNotify(channel, "req-x", answer, 77L);

            ArgumentCaptor<Object> cap = ArgumentCaptor.forClass(Object.class);
            verify(channel, atLeastOnce()).writeAndFlush(cap.capture());
            GamePacket pkt = (GamePacket) cap.getAllValues().stream()
                    .filter(o -> o instanceof GamePacket gp && gp.getCmdId() == CmdIds.AI_HINT_SC_NOTIFY)
                    .reduce((a, b) -> b)
                    .orElseThrow();
            AssistSystemProto.AiHintNotify n = AssistSystemProto.AiHintNotify.parseFrom(pkt.getPayload());
            assertEquals("TEAM_CARD", n.getRenderType());
            assertEquals(800, n.getEstimatedWaitMs());
        }
    }

    // --- helpers ---

    private AssistSystemProto.AssistVisualHintScNotify captureVisualHint() throws Exception {
        ArgumentCaptor<Object> cap = ArgumentCaptor.forClass(Object.class);
        verify(channel, atLeastOnce()).writeAndFlush(cap.capture());
        GamePacket pkt = (GamePacket) cap.getAllValues().stream()
                .filter(o -> o instanceof GamePacket gp && gp.getCmdId() == CmdIds.ASSIST_VISUAL_HINT_SC_NOTIFY)
                .findFirst()
                .orElseThrow();
        return AssistSystemProto.AssistVisualHintScNotify.parseFrom(pkt.getPayload());
    }

    private VisualAssistService newVisual() {
        AssistQuotaLimiter quota = mock(AssistQuotaLimiter.class);
        when(quota.tryAcquire(anyLong(), any(), anyInt())).thenReturn(AssistQuotaLimiter.AcquireResult.ok());
        AssistFeatureContentRepository feature = mock(AssistFeatureContentRepository.class);
        when(feature.current()).thenReturn(new AssistFeatureContent(
                1, List.of(),
                List.of(new AssistFeatureContent.PoiLore(
                        "ruins_alpha", 1, List.of("puzzle"),
                        40f, 0f, 25f, 12f, "古代星轨遗迹", "观星祭坛",
                        "按顺序激活符文", "星轨学者")),
                List.of(), Map.of(), List.of(), List.of()));
        AssistPersonaService persona = mock(AssistPersonaService.class);
        when(persona.wrapAnswer(anyLong(), anyString())).thenAnswer(inv -> inv.getArgument(1));
        when(persona.personaId(anyLong())).thenReturn("tingyun");
        return new VisualAssistService(props, resolver, quota,
                new AssistAuditService(), feature, persona, new AssistTtsService(props),
                new VisualFrameTemporalAnalyzer(), new SpatialPuzzleHintBuilder(),
                Executors.newSingleThreadExecutor());
    }

    private static AssistFeatureContentRepository featureRepo() {
        AssistFeatureContentRepository repo = mock(AssistFeatureContentRepository.class);
        when(repo.current()).thenReturn(new AssistFeatureContent(
                1, List.of(), List.of(),
                List.of(new AssistFeatureContent.EnemyWeakness(
                        101, "坚盾卫", List.of("elite"), List.of("break"),
                        List.of(), "先破盾", 0.35)),
                Map.of("1001", List.of("break")), List.of(), List.of()));
        return repo;
    }

    private BattleNettyService newBattleService(BattleManager battleManager,
                                                HeuristicBattleAssistPolicy policy,
                                                ObjectProvider<cn.itcast.demo.mylunarcore.battle.BattleAutoService> autoProvider) {
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
                props,
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

    private AssistNettyService minimalAssistNetty() {
        AssistQuotaLimiter quota = mock(AssistQuotaLimiter.class);
        when(quota.tryAcquire(anyLong(), any(), anyInt())).thenReturn(AssistQuotaLimiter.AcquireResult.ok());
        AiAssistApplicationService app = mock(AiAssistApplicationService.class);
        AssistPersonaService persona = mock(AssistPersonaService.class);
        when(persona.personaId(anyLong())).thenReturn("tingyun");
        when(persona.voiceId(anyLong())).thenReturn("");
        return new AssistNettyService(props, resolver, quota,
                mock(PlayerCoachApplicationService.class), app, mock(GuidePackRepository.class),
                new AssistAuditService(),
                new AssistFeedbackService(mock(cn.itcast.demo.mylunarcore.common.BusinessMetrics.class),
                        mock(cn.itcast.demo.mylunarcore.analytics.AnalyticsEventPublisher.class)),
                mock(OwnedLineupRecommendService.class),
                mock(ExplorePathAdvisor.class), mock(QuestGuidanceService.class),
                Executors.newSingleThreadExecutor(), mock(GameSessionManager.class),
                mock(AssistProactiveCoachService.class),
                emptyProvider(), provider(persona), provider(new AssistTtsService(props)), emptyProvider());
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

    private static PlayerData samplePlayerData() {
        PlayerData data = new PlayerData();
        PlayerEntity player = new PlayerEntity();
        player.setUid(77L);
        player.setLevel(50);
        data.setPlayer(player);
        AvatarEntity a1 = new AvatarEntity();
        a1.setAvatarId(1001);
        a1.setLevel(70);
        a1.setRank(6);
        AvatarEntity a2 = new AvatarEntity();
        a2.setAvatarId(1002);
        a2.setLevel(65);
        a2.setRank(5);
        data.setAvatars(List.of(a1, a2));
        GameItemEntity item = new GameItemEntity();
        item.setItemId(501001);
        item.setType(2);
        item.setEquipAvatarId(1001);
        item.setSubAffixesJson("[{\"id\":1,\"stat\":\"CRIT_DMG\",\"value\":30.0}]");
        item.setMainAffixId(1);
        data.setItems(List.of(item));
        return data;
    }
}
