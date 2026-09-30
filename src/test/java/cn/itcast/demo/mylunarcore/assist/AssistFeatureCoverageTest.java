package cn.itcast.demo.mylunarcore.assist;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.model.QuestProgressEntity;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 探索导航 / 环境解说 / 渐进任务 / 世界观百科。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code AssistFeatureCoverageTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("探索导航 / 环境解说 / 渐进任务 / 世界观百科")
class AssistFeatureCoverageTest {

    private static final Logger log = LoggerFactory.getLogger(AssistFeatureCoverageTest.class);

    /**
     * 验证点：探索路径应按任务给出航点与沿途资源提示。
     * <p>测试方法 {@code explorePathShouldMarkWaypointsAndResources}：
     * <ul>
     *   <li>{@code assertEquals(0, advice.retcode());}</li>
     *   <li>{@code assertEquals(3, advice.waypoints().size());}</li>
     *   <li>{@code assertFalse(advice.resourceHints().isEmpty());}</li>
     *   <li>{@code assertTrue(advice.summary().contains("埋点") || advice.summary().contains("航点"));}</li>
     * </ul>
     */
    @Test
    @DisplayName("探索路径应按任务给出航点与沿途资源提示")
    void explorePathShouldMarkWaypointsAndResources() {
        ExplorePathAdvisor advisor = new ExplorePathAdvisor(repoWithDemoContent());
        ExplorePathAdvisor.PathAdvice advice = advisor.advise(10001, 1);
        log.info("探索路径校验: retcode={}, routeId={}, waypointCount={}, resources={}, summary={}",
                advice.retcode(), advice.routeId(), advice.waypoints().size(),
                advice.resourceHints(), advice.summary());
        assertEquals(0, advice.retcode());
        assertEquals(3, advice.waypoints().size());
        assertFalse(advice.resourceHints().isEmpty());
        assertTrue(advice.summary().contains("埋点") || advice.summary().contains("航点"));
    }

    /**
     * 验证点：进入 POI 半径应触发环境解说，冷却期内不重复。
     * <p>测试方法 {@code environmentNarrationShouldFireOncePerCooldown}：
     * <ul>
     *   <li>{@code assertTrue(first.isPresent());}</li>
     *   <li>{@code assertTrue(first.get().message().contains("星轨学者") || first.get().message().contains("遗迹"));}</li>
     *   <li>{@code assertTrue(second.isEmpty());}</li>
     * </ul>
     */
    @Test
    @DisplayName("进入 POI 半径应触发环境解说，冷却期内不重复")
    void environmentNarrationShouldFireOncePerCooldown() {
        LunarCoreProperties props = new LunarCoreProperties();
        props.getAiAssist().setEnabled(true);
        props.getAiAssist().setEnvironmentNarrationCooldownSeconds(60);
        EnvironmentNarrationService service = new EnvironmentNarrationService(repoWithDemoContent(), props);
        Optional<EnvironmentNarrationService.Narration> first =
                service.maybeNarrate(9L, 1, 40f, 0f, 25f);
        Optional<EnvironmentNarrationService.Narration> second =
                service.maybeNarrate(9L, 1, 40f, 0f, 25f);
        log.info("环境解说校验: firstPresent={}, message={}, secondPresent={}",
                first.isPresent(), first.map(EnvironmentNarrationService.Narration::message).orElse(null),
                second.isPresent());
        assertTrue(first.isPresent());
        assertTrue(first.get().message().contains("星轨学者") || first.get().message().contains("遗迹"));
        assertTrue(second.isEmpty());
    }

    /**
     * 验证点：任务迷路提示应按求助次数渐进升级。
     * <p>测试方法 {@code questGuidanceShouldEscalateByAskCount}：
     * <ul>
     *   <li>{@code when(progress.list(7)).thenReturn(List.of(q));}</li>
     *   <li>{@code when(questConfig.find(10001)).thenReturn(new QuestConfigRepository.QuestConfig(}</li>
     *   <li>{@code assertEquals(1, g1.stage());}</li>
     *   <li>{@code assertEquals(2, g2.stage());}</li>
     *   <li>{@code assertEquals(3, g3.stage());}</li>
     *   <li>{@code assertTrue(g1.message().contains("方向") || g1.message().contains("东"));}</li>
     * </ul>
     */
    @Test
    @DisplayName("任务迷路提示应按求助次数渐进升级")
    void questGuidanceShouldEscalateByAskCount() {
        QuestProgressEntity q = new QuestProgressEntity();
        q.setQuestId(10001);
        q.setStatus(CoachRuleEngine.QUEST_STATUS_IN_PROGRESS);
        QuestProgressApplicationService progress = mock(QuestProgressApplicationService.class);
        when(progress.list(7)).thenReturn(List.of(q));
        QuestConfigRepository questConfig = mock(QuestConfigRepository.class);
        when(questConfig.find(10001)).thenReturn(new QuestConfigRepository.QuestConfig(
                10001, "示范主线", "desc", List.of(), List.of()));

        LunarCoreProperties props = new LunarCoreProperties();
        props.getAiAssist().setQuestGuidanceMaxStage(3);
        QuestGuidanceService service = new QuestGuidanceService(
                repoWithDemoContent(), progress, questConfig, props);

        QuestGuidanceService.Guidance g1 = service.guide(7L, "我迷路了");
        QuestGuidanceService.Guidance g2 = service.guide(7L, "还是找不到");
        QuestGuidanceService.Guidance g3 = service.guide(7L, "再给点提示");
        log.info("渐进任务提示校验: s1={}, s2={}, s3={}, m1={}, m3={}",
                g1.stage(), g2.stage(), g3.stage(), g1.message(), g3.message());
        assertEquals(1, g1.stage());
        assertEquals(2, g2.stage());
        assertEquals(3, g3.stage());
        assertTrue(g1.message().contains("方向") || g1.message().contains("东"));
        assertTrue(g3.message().contains("调查") || g3.message().contains("交互"));
    }

    /**
     * 验证点：世界观百科应以角色口吻回答势力/历史。
     * <p>测试方法 {@code worldLoreShouldAnswerInPersona}：
     * <ul>
     *   <li>{@code assertTrue(ans.isPresent());}</li>
     *   <li>{@code assertTrue(ans.get().answer().contains("星轨学者") || ans.get().answer().contains("星轨同盟"));}</li>
     *   <li>{@code assertEquals("lore-local", ans.get().source());}</li>
     * </ul>
     */
    @Test
    @DisplayName("世界观百科应以角色口吻回答势力/历史")
    void worldLoreShouldAnswerInPersona() {
        WorldLoreService lore = new WorldLoreService(repoWithDemoContent());
        Optional<WorldLoreService.LoreAnswer> ans = lore.answer("星轨同盟是什么势力？");
        log.info("世界观百科校验: present={}, persona={}, answer={}, cited={}",
                ans.isPresent(),
                ans.map(WorldLoreService.LoreAnswer::persona).orElse(null),
                ans.map(WorldLoreService.LoreAnswer::answer).orElse(null),
                ans.map(WorldLoreService.LoreAnswer::citedIds).orElse(null));
        assertTrue(ans.isPresent());
        assertTrue(ans.get().answer().contains("星轨学者") || ans.get().answer().contains("星轨同盟"));
        assertEquals("lore-local", ans.get().source());
    }

    private static AssistFeatureContentRepository repoWithDemoContent() {
        AssistFeatureContent content = new AssistFeatureContent(
                1,
                List.of(new AssistFeatureContent.ExploreRoute(
                        "quest_main_demo", 10001, 1, "主线示范路线",
                        List.of(
                                new AssistFeatureContent.Waypoint(10, 0, 5, "任务起点", "quest"),
                                new AssistFeatureContent.Waypoint(28, 0, 18, "中途补给点", "resource"),
                                new AssistFeatureContent.Waypoint(45, 0, 30, "目标区域", "objective")
                        ),
                        List.of(new AssistFeatureContent.ResourceHint("沿途可采集星尘矿", 1))
                )),
                List.of(new AssistFeatureContent.PoiLore(
                        "ruins_alpha", 1, List.of("ruins"), 40, 0, 25, 12,
                        "古代星轨遗迹", "这里曾是观星者观测星轨的祭坛。",
                        "石板上的符文顺序与天上星座方向一致。", "星轨学者"
                )),
                List.of(),
                Map.of(),
                List.of(new AssistFeatureContent.LoreEntry(
                        "faction_astral", "faction", "星轨同盟",
                        List.of("同盟", "星轨"), "星轨学者",
                        "致力于守护星轨观测站的学术与防卫同盟。",
                        "同盟主张以观测与记录对抗遗忘。"
                )),
                List.of(new AssistFeatureContent.QuestHintLadder(
                        10001,
                        List.of(
                                new AssistFeatureContent.HintStage(1, "方向提示", "沿着主路向东，留意发光符文的方向。"),
                                new AssistFeatureContent.HintStage(2, "目标提示", "你需要抵达古代星轨遗迹附近并调查祭坛。"),
                                new AssistFeatureContent.HintStage(3, "行动提示", "与遗迹中的调查点交互，完成当前任务目标。")
                        )
                ))
        );
        AssistFeatureContentRepository repo = mock(AssistFeatureContentRepository.class);
        when(repo.current()).thenReturn(content);
        return repo;
    }
}
