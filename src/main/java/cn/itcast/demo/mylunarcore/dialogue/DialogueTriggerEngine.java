package cn.itcast.demo.mylunarcore.dialogue;

import cn.itcast.demo.mylunarcore.affinity.AffinityService;
import cn.itcast.demo.mylunarcore.common.AppLogger;
import cn.itcast.demo.mylunarcore.common.LogCategory;
import cn.itcast.demo.mylunarcore.cutscene.CutsceneTriggerService;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.player.PlayerSessionState;
import cn.itcast.demo.mylunarcore.protocol.SceneSystemProto;
import org.slf4j.Logger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 对话树触发引擎。
 * <p>
 * 业务含义：负责把“玩家触发剧情”这件事串成完整的 AVG 流程：
 * <ul>
 *   <li>从 NPC / 场景 / 任务入口启动某条对话树；</li>
 *   <li>支持对话分支选择、顺序推进与结束标记；</li>
 *   <li>对接过场动画（cutscene），在节点触发时自动播放；</li>
 *   <li>对接 CG / 剧情标记（questFlag）与好感度系统（Affinity），形成剧情驱动闭环。</li>
 * </ul>
 * 与传统“点一句台词就结束”的对话模块不同，这里强调的是可回放、可分支、可解锁的剧情流。
 */
@Service
public class DialogueTriggerEngine {

    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_SCENE, DialogueTriggerEngine.class);

    /**
     * 对话步骤结果：
     * <ul>
     *   <li>ok 表示是否成功进入/推进剧情；</li>
     *   <li>retcode 为协议层错误码（0 成功，非 0 表示失败原因）；</li>
     *   <li>node 为当前节点内容（供客户端展示）；</li>
     *   <li>cutsceneId 为本节点触发的过场 ID（如果有）；</li>
     *   <li>unlockedCgId 为本节点解锁的 CG 编号（供图鉴系统收集）。</li>
     * </ul>
     */
    public record DialogueStepResult(boolean ok, int retcode, DialogueNode node, String cutsceneId, int unlockedCgId) {
        public static DialogueStepResult fail(int retcode) {
            return new DialogueStepResult(false, retcode, null, "", 0);
        }
    }

    /** 对话树仓储：负责读取 DialogueTrees.json 并按 treeId / nodeId 查询节点。 */
    private final DialogueTreeRepository treeRepository;
    /** 对话进度服务：记录已播放树、当前节点、分支选择历史、CG 解锁与剧情 flag。 */
    private final DialogueProgressService progressService;
    /** 过场触发服务：对话节点上的 cutsceneId 会交给它播放。 */
    private final CutsceneTriggerService cutsceneTriggerService;
    /** 好感度服务（可选）：某些分支会因为选择而增加 NPC 好感。 */
    private final AffinityService affinityService;
    private final GameSessionManager sessionManager;
    private final DialoguePerformanceService performanceService;

    public DialogueTriggerEngine(DialogueTreeRepository treeRepository,
                                 DialogueProgressService progressService,
                                 CutsceneTriggerService cutsceneTriggerService) {
        this(treeRepository, progressService, cutsceneTriggerService, null, null, null);
    }

    public DialogueTriggerEngine(DialogueTreeRepository treeRepository,
                                 DialogueProgressService progressService,
                                 CutsceneTriggerService cutsceneTriggerService,
                                 ObjectProvider<AffinityService> affinityProvider) {
        this(treeRepository, progressService, cutsceneTriggerService, affinityProvider, null, null);
    }

    public DialogueTriggerEngine(DialogueTreeRepository treeRepository,
                                 DialogueProgressService progressService,
                                 CutsceneTriggerService cutsceneTriggerService,
                                 ObjectProvider<AffinityService> affinityProvider,
                                 ObjectProvider<GameSessionManager> sessionProvider) {
        this(treeRepository, progressService, cutsceneTriggerService, affinityProvider, sessionProvider, null);
    }

    public DialogueTriggerEngine(DialogueTreeRepository treeRepository,
                                 DialogueProgressService progressService,
                                 CutsceneTriggerService cutsceneTriggerService,
                                 ObjectProvider<AffinityService> affinityProvider,
                                 ObjectProvider<GameSessionManager> sessionProvider,
                                 ObjectProvider<DialoguePerformanceService> performanceProvider) {
        this.treeRepository = treeRepository;
        this.progressService = progressService;
        this.cutsceneTriggerService = cutsceneTriggerService;
        this.affinityService = affinityProvider == null ? null : affinityProvider.getIfAvailable();
        this.sessionManager = sessionProvider == null ? null : sessionProvider.getIfAvailable();
        this.performanceService = performanceProvider == null ? null : performanceProvider.getIfAvailable();
    }

    /**
     * 从 NPC dialogueId（约定即 treeId）启动一条对话树。
     * <p>若该树已经播放过，则直接返回“已看过”结果，供客户端隐藏入口或快进处理。
     */
    public DialogueStepResult startByNpc(int playerId, String dialogueId) {
        if (dialogueId == null || dialogueId.isBlank()) {
            return DialogueStepResult.fail(1);
        }
        DialogueTreeRepository.DialogueTree tree = treeRepository.findTree(dialogueId);
        if (tree == null) {
            return DialogueStepResult.fail(2);
        }
        // 已看过：同步跳过标记，客户端可据此隐藏或快进
        if (progressService.hasPlayed(playerId, tree.treeId())) {
            log.info("dialogue_skip_seen playerId={} treeId={}", playerId, tree.treeId());
            return new DialogueStepResult(true, 10, null, "", 0);
        }
        DialogueNode entry = treeRepository.findNode(tree.treeId(), tree.entryNodeId());
        if (entry == null) {
            return DialogueStepResult.fail(3);
        }
        progressService.getOrStart(playerId, tree.treeId(), entry.nodeId());
        log.info("dialogue_start playerId={} treeId={} nodeId={}", playerId, tree.treeId(), entry.nodeId());
        return applySideEffects(playerId, tree.treeId(), entry);
    }

    /**
     * 选择分支或推进到下一节点。
     * <p>逻辑：读取当前进度 → 找到当前节点 → 若提供 choiceId 则校验分支可选性并跳转到分支目标；
     * 若未指定 choiceId，则按 nextNodeId 默认推进。
     * 该方法同时记录分支历史、剧情 flag、CG 解锁，并在必要时将对话标记为已播放完毕。
     */
    public DialogueStepResult choose(int playerId, String treeId, String choiceId) {
        DialogueProgressService.Progress progress = progressService.getOrStart(playerId, treeId, "");
        DialogueNode current = treeRepository.findNode(treeId, progress.currentNodeId());
        if (current == null) {
            return DialogueStepResult.fail(2);
        }
        if (!current.safeChoices().isEmpty()) {
            progressService.markSavepoint(playerId, treeId, current.nodeId());
        }
        String nextId = current.nextNodeId();
        DialogueConditionEvaluator.EvalContext ctx = DialogueConditionEvaluator.EvalContext.ofFlags(progress.flags());
        if (choiceId != null && !choiceId.isBlank()) {
            nextId = current.safeChoices().stream()
                    .filter(c -> choiceId.equals(c.choiceId()))
                    .filter(c -> choiceAllowed(c, ctx))
                    .map(DialogueNode.Choice::nextNodeId)
                    .findFirst()
                    .orElse("");
        }
        if (nextId == null || nextId.isBlank()) {
            progressService.markPlayed(playerId, treeId);
            log.info("dialogue_end playerId={} treeId={} reason=no_next", playerId, treeId);
            return DialogueStepResult.fail(4);
        }
        DialogueNode next = treeRepository.findNode(treeId, nextId);
        if (next == null) {
            return DialogueStepResult.fail(3);
        }
        progressService.recordChoiceAndAdvance(playerId, treeId, current.nodeId(), choiceId,
                next.nodeId(), next.questFlag(), next.unlockCgId());
        log.info("dialogue_branch playerId={} treeId={} from={} choice={} to={}",
                playerId, treeId, current.nodeId(), choiceId, next.nodeId());
        applyAffinitySideEffect(playerId, treeId, choiceId, next.questFlag());
        pushEnvironmentFeedback(playerId, treeId, current, choiceId);
        pushChoicePerformanceAck(playerId, treeId, current, choiceId);
        markDialogueState(playerId);
        if (next.safeChoices().isEmpty()
                && (next.nextNodeId() == null || next.nextNodeId().isBlank())) {
            progressService.markPlayed(playerId, treeId);
            clearDialogueState(playerId);
        }
        return applySideEffects(playerId, treeId, next);
    }

    /** impact_tags 含 +Affinity 时下发场景环境联动，触发 NPC 微表情 / 爱心粒子 / BGM 变奏。 */
    private void pushEnvironmentFeedback(int playerId, String treeId, DialogueNode current, String choiceId) {
        if (sessionManager == null || current == null || choiceId == null || choiceId.isBlank()) {
            return;
        }
        DialogueNode.Choice chosen = current.safeChoices().stream()
                .filter(c -> choiceId.equals(c.choiceId()))
                .findFirst()
                .orElse(null);
        if (chosen == null || chosen.impactTags() == null || chosen.impactTags().isEmpty()) {
            return;
        }
        boolean affinity = false;
        for (String tag : chosen.impactTags()) {
            if (tag != null && tag.toLowerCase(Locale.ROOT).contains("affinity")) {
                affinity = true;
                break;
            }
        }
        if (!affinity) {
            return;
        }
        GameSession session = sessionManager.getOrNull(playerId);
        if (session == null || session.getChannel() == null || !session.getChannel().isActive()) {
            return;
        }
        List<String> tags = new ArrayList<>(chosen.impactTags());
        SceneSystemProto.SceneEnvironmentModifyScNotify notify =
                SceneSystemProto.SceneEnvironmentModifyScNotify.newBuilder()
                        .setEffectType("npc_micro_expression")
                        .setNpcId(treeId == null ? "" : treeId)
                        .setExpression("smile")
                        .setParticleId("heart_burst")
                        .setBgmVariationId("affinity_up_shift")
                        .addAllImpactTags(tags)
                        .setDurationMs(1_800)
                        .setTargetEntityId(0)
                        .setEmotionId("happy")
                        .build();
        session.getChannel().writeAndFlush(new GamePacket(
                CmdIds.SCENE_ENVIRONMENT_MODIFY_SC_NOTIFY, notify.toByteArray()));
    }

    /** impact_tags 触发时插入 1–2 秒收尾表演（点头/微笑）。 */
    private void pushChoicePerformanceAck(int playerId, String treeId, DialogueNode current, String choiceId) {
        if (performanceService == null || current == null || choiceId == null || choiceId.isBlank()) {
            return;
        }
        DialogueNode.Choice chosen = current.safeChoices().stream()
                .filter(c -> choiceId.equals(c.choiceId()))
                .findFirst()
                .orElse(null);
        if (chosen != null) {
            performanceService.pushChoiceAck(playerId, treeId, chosen);
        }
    }

    private void markDialogueState(int playerId) {
        if (sessionManager == null) {
            return;
        }
        GameSession session = sessionManager.getOrNull(playerId);
        if (session != null) {
            session.setSessionState(PlayerSessionState.DIALOGUE);
        }
    }

    private void clearDialogueState(int playerId) {
        if (sessionManager == null) {
            return;
        }
        GameSession session = sessionManager.getOrNull(playerId);
        if (session != null && session.getSessionState() == PlayerSessionState.DIALOGUE) {
            session.setSessionState(PlayerSessionState.SCENE);
        }
    }

    /** 判断当前分支选项是否允许被选中（requireFlag + condition 双层校验）。 */
    private static boolean choiceAllowed(DialogueNode.Choice c, DialogueConditionEvaluator.EvalContext ctx) {
        if (!c.requireFlag().isBlank() && !ctx.flags().contains(c.requireFlag())) {
            return false;
        }
        return DialogueConditionEvaluator.evaluate(c.condition(), ctx);
    }

    /**
     * 选择分支后的好感度副作用：
     * <ul>
     *   <li>若 questFlag 约定为 affinity:npcId:delta，则按该格式精确加好感；</li>
     *   <li>否则默认按树 ID + choiceId 记一个轻量好感值（5），便于埋点驱动后续内容。</li>
     * </ul>
     */
    private void applyAffinitySideEffect(int playerId, String treeId, String choiceId, String questFlag) {
        if (affinityService == null) {
            return;
        }
        try {
            if (questFlag != null && questFlag.startsWith("affinity:")) {
                String[] parts = questFlag.split(":");
                String npcId = parts.length > 1 ? parts[1] : treeId;
                int delta = parts.length > 2 ? Integer.parseInt(parts[2]) : 15;
                affinityService.addFromDialogue(playerId, npcId, choiceId, delta);
            } else if (choiceId != null && !choiceId.isBlank()) {
                affinityService.addFromDialogue(playerId, treeId, choiceId, 5);
            }
        } catch (Exception e) {
            log.debug("affinity side-effect skipped: {}", e.getMessage());
        }
    }

    /**
     * 客户端主动跳过某条对话树。
     * 适用于已看过剧情、或运营允许跳过的可重复剧情；这里只做进度标记，不做剧情逻辑回放。
     */
    public DialogueStepResult skip(int playerId, String treeId) {
        if (treeId == null || treeId.isBlank()) {
            return DialogueStepResult.fail(1);
        }
        progressService.markPlayed(playerId, treeId);
        return new DialogueStepResult(true, 0, null, "", 0);
    }

    /**
     * 对节点做统一的副作用处理：播放 cutscene、返回 unlockCgId。
     * 该方法保证“节点内容展示”与“节点副作用”在同一个返回路径里完成。
     */
    private DialogueStepResult applySideEffects(int playerId, String treeId, DialogueNode node) {
        String cutsceneId = "";
        if (node.cutsceneId() != null && !node.cutsceneId().isBlank()) {
            cutsceneTriggerService.trigger(playerId, node.cutsceneId());
            cutsceneId = node.cutsceneId();
        }
        if (performanceService != null) {
            performanceService.pushOnEnterNode(playerId, treeId, node);
        }
        return new DialogueStepResult(true, 0, node, cutsceneId, node.unlockCgId());
    }

    /** 断线后从选项前 SAVEPOINT 恢复。 */
    public DialogueStepResult resumeFromBranch(int playerId, String treeId) {
        DialogueProgressService.Progress p = progressService.resumeFromSavepoint(playerId, treeId);
        if (p == null) {
            return DialogueStepResult.fail(2);
        }
        DialogueNode node = treeRepository.findNode(treeId, p.currentNodeId());
        if (node == null) {
            return DialogueStepResult.fail(3);
        }
        return applySideEffects(playerId, treeId, node);
    }

    public record StoryTreeSnapshot(boolean ok, int retcode, String treeId, String title,
                                    List<StoryNodeSnap> nodes) {
        public static StoryTreeSnapshot fail(int retcode) {
            return new StoryTreeSnapshot(false, retcode, "", "", List.of());
        }
    }

    public record StoryNodeSnap(String nodeId, boolean unlocked, boolean current, boolean savepoint,
                                List<String> outgoingChoiceIds) {}

    /** 剧情树全景缩略：仅节点 ID 与锁/解锁状态。 */
    public StoryTreeSnapshot storyTreeSnapshot(int playerId, String treeId) {
        if (treeId == null || treeId.isBlank()) {
            return StoryTreeSnapshot.fail(2);
        }
        DialogueTreeRepository.DialogueTree tree = treeRepository.findTree(treeId);
        if (tree == null) {
            return StoryTreeSnapshot.fail(2);
        }
        DialogueProgressService.Progress progress = progressService.find(playerId, treeId);
        String current = progress == null ? tree.entryNodeId() : progress.currentNodeId();
        String savepoint = progress == null ? "" : progress.savepointNodeId();
        java.util.Set<String> visited = new java.util.LinkedHashSet<>();
        if (progress != null) {
            for (DialogueProgressService.ChoiceRecord rec : progress.choiceHistory()) {
                if (rec.nodeId() != null && !rec.nodeId().isBlank()) {
                    visited.add(rec.nodeId());
                }
            }
            if (progress.currentNodeId() != null && !progress.currentNodeId().isBlank()) {
                visited.add(progress.currentNodeId());
            }
        }
        visited.add(tree.entryNodeId());
        List<StoryNodeSnap> snaps = new java.util.ArrayList<>();
        for (DialogueNode node : tree.nodes()) {
            if (node == null || node.nodeId() == null || node.nodeId().isBlank()) {
                continue;
            }
            List<String> choices = new java.util.ArrayList<>();
            for (DialogueNode.Choice c : node.safeChoices()) {
                choices.add(c.choiceId());
            }
            boolean unlocked = visited.contains(node.nodeId()) || node.nodeId().equals(tree.entryNodeId());
            snaps.add(new StoryNodeSnap(node.nodeId(), unlocked,
                    node.nodeId().equals(current), node.nodeId().equals(savepoint), List.copyOf(choices)));
        }
        return new StoryTreeSnapshot(true, 0, tree.treeId(), tree.title(), List.copyOf(snaps));
    }
}
