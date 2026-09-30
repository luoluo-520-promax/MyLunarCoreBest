package cn.itcast.demo.mylunarcore.assist;

import cn.itcast.demo.mylunarcore.common.ActivityConfigService;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.economy.ShopConfigRepository;
import cn.itcast.demo.mylunarcore.gacha.GachaConfigService;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.quest.QuestConfigRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("AI 助手增强能力单测")
class AssistEnhancementFeaturesTest {

    @Test
    @DisplayName("战斗建议可映射为 Auto 策略覆盖")
    void autoSuggestionFromBattleAdvice() {
        SuggestedAutoOverride o = AiAutoSuggestionFactory.fromBattleAdvice("优先集火精英", "破盾弱点", "");
        assertTrue(o.isPresent());
        assertEquals(1, o.targetFocus());
        assertEquals(1, o.skillPriority());
    }

    @Test
    @DisplayName("UI_INTERACTION：强化光锥应返回深链")
    void uiInteractionLightCone() {
        ExplorePathAdvisor explore = mock(ExplorePathAdvisor.class);
        when(explore.advise(0, 1)).thenReturn(new ExplorePathAdvisor.PathAdvice(
                0, "r1", "路线", List.of(), List.of(), "摘要"));
        QuestGuidanceService quest = mock(QuestGuidanceService.class);
        IntentExecuteHandler handler = new IntentExecuteHandler(explore, quest);
        Optional<IntentExecuteHandler.ExecuteResult> r = handler.tryExecute(1L, "去强化光锥");
        assertTrue(r.isPresent());
        assertEquals(IntentExecuteHandler.IntentKind.UI_INTERACTION, r.get().kind());
        assertEquals("OPEN_LIGHT_CONE", r.get().uiAction());
    }

    @Test
    @DisplayName("TTS 本地占位应产出 opus 块")
    void ttsLocalPlaceholder() {
        LunarCoreProperties props = new LunarCoreProperties();
        props.getAiAssist().setTtsEnabled(true);
        AssistTtsService tts = new AssistTtsService(props);
        AssistTtsService.TtsClip clip = tts.synthesize(1L, "你好贵客", "tingyun_zh");
        assertTrue(clip.hasAudio());
        assertEquals("opus", clip.format());
    }

    @Test
    @DisplayName("人设包装应附加角色署名")
    void personaWrap() {
        LunarCoreProperties props = new LunarCoreProperties();
        props.getAiAssist().setPersonaEnabled(true);
        AssistPersonaRepository repo = mock(AssistPersonaRepository.class);
        when(repo.current()).thenReturn(new AssistPersonaConfig(1, "tingyun", List.of(
                new AssistPersonaConfig.PersonaEntry("tingyun", "停云", 1102, "tingyun_zh", 0,
                        "温柔", "贵客请听停云一言——")
        ), Map.of()));
        GameSessionManager sessions = mock(GameSessionManager.class);
        when(sessions.getOrNull(1L)).thenReturn(null);
        AssistPersonaService svc = new AssistPersonaService(props, repo, sessions);
        String wrapped = svc.wrapAnswer(1L, "建议先破盾");
        assertTrue(wrapped.contains("停云"));
        assertTrue(wrapped.contains("贵客"));
    }

    @Test
    @DisplayName("公告灌库后可被 RAG 检索")
    void ragAnnouncementIngest() {
        List<String> parts = RagKnowledgeService.chunkMarkdown("第一段\n\n第二段很长很长", 20);
        assertFalse(parts.isEmpty());

        LunarCoreProperties props = new LunarCoreProperties();
        props.getAiAssist().setRagFreshnessBoost(0.30);
        props.getAiAssist().setRagFreshnessDays(30);
        props.getAiAssist().setRagMinScore(0.5);

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
        when(lore.toKnowledgeChunks()).thenReturn(List.of());

        RagKnowledgeService rag = new RagKnowledgeService(
                activities, quests, gacha, shop, usage, props, lore);
        rag.init();
        int n = rag.ingestAnnouncement("v3.2", "版本更新", "## 新 BOSS\n\n机制：先破盾再爆发",
                System.currentTimeMillis());
        assertTrue(n >= 1);
        List<RagKnowledgeService.KnowledgeChunk> hit = rag.search("新 BOSS 破盾", "announce", 5);
        assertFalse(hit.isEmpty());
    }
}
