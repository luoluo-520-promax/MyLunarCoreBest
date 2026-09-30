package cn.itcast.demo.mylunarcore.dialogue;

import cn.itcast.demo.mylunarcore.cutscene.CutsceneTriggerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AVG 对话树 → 分支选择 → 过场触发 → CG 解锁 全流程。
 */
@DisplayName("对话树与过场业务流程")
class DialogueCutsceneFlowTest {

    private DialogueTreeRepository treeRepository;
    private DialogueProgressService progressService;
    private CutsceneTriggerService cutsceneService;
    private DialogueTriggerEngine engine;

    @BeforeEach
    void setUp() {
        ObjectMapper mapper = new ObjectMapper();
        treeRepository = new DialogueTreeRepository(mapper, "data");
        assertTrue(treeRepository.reload(), "应能加载 data/DialogueTrees.json");
        progressService = new DialogueProgressService();
        cutsceneService = new CutsceneTriggerService(mapper, "data");
        assertTrue(cutsceneService.reload(), "应能加载 data/CutsceneConfigs.json");
        engine = new DialogueTriggerEngine(treeRepository, progressService, cutsceneService);
    }

    @Test
    @DisplayName("NPC 启动对话应进入入口节点并带分支选项")
    void startByNpcShouldEnterEntryWithChoices() {
        DialogueTriggerEngine.DialogueStepResult step = engine.startByNpc(1001, "1001");
        assertTrue(step.ok());
        assertEquals(0, step.retcode());
        assertEquals("n1", step.node().nodeId());
        assertEquals(2, step.node().safeChoices().size());
        assertTrue(step.cutsceneId().isBlank());
    }

    @Test
    @DisplayName("选择「准备好了」应触发过场、解锁 CG 并写入任务旗标")
    void chooseAcceptShouldTriggerCutsceneAndUnlockCg() {
        assertTrue(engine.startByNpc(2002, "1001").ok());

        DialogueTriggerEngine.DialogueStepResult step = engine.choose(2002, "1001", "1");
        assertTrue(step.ok());
        assertEquals("n2", step.node().nodeId());
        assertEquals("cs_intro_01", step.cutsceneId());
        assertEquals(10001, step.unlockedCgId());

        CutsceneTriggerService.TriggerResult cut = cutsceneService.trigger(2002, "cs_intro_01");
        assertTrue(cut.ok());
        assertEquals("cutscenes/intro_01.timeline", cut.config().timelineAsset());

        assertTrue(progressService.hasCg(2002, 10001));
        assertTrue(progressService.unlockedCgs(2002).contains(10001));
        assertTrue(progressService.getOrStart(2002, "1001", "n1").flags().contains("intro_accepted"));
    }

    @Test
    @DisplayName("选择「再等等」不应解锁 CG")
    void chooseWaitShouldNotUnlockCg() {
        assertTrue(engine.startByNpc(3003, "1001").ok());
        DialogueTriggerEngine.DialogueStepResult step = engine.choose(3003, "1001", "2");
        assertTrue(step.ok());
        assertEquals("n3", step.node().nodeId());
        assertTrue(step.cutsceneId().isBlank());
        assertEquals(0, step.unlockedCgId());
        assertFalse(progressService.hasCg(3003, 10001));
    }

    @Test
    @DisplayName("未知对话树 / 非法选项应失败")
    void invalidTreeOrChoiceShouldFail() {
        assertEquals(1, engine.startByNpc(1, "").retcode());
        assertEquals(2, engine.startByNpc(1, "missing_tree").retcode());
        assertTrue(engine.startByNpc(1, "1001").ok());
        assertEquals(4, engine.choose(1, "1001", "no_such_choice").retcode());
    }
}
