package cn.itcast.demo.mylunarcore.dialogue;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Collections;
import java.util.List;

/**
 * 对话树的基础节点定义。
 * <p>
 * 业务含义：每个 DialogueNode 对应一个剧情台词节点，可能包含：
 * <ul>
 *   <li>speaker / text：当前节点的说话者与文本；</li>
 *   <li>choices：可选分支（AVG 玩法的关键），用于决定下一节点；</li>
 *   <li>nextNodeId：默认顺序推进的下一节点；</li>
 *   <li>cutsceneId：进入该节点后需要触发的过场动画 ID；</li>
 *   <li>unlockCgId：该节点解锁的 CG 编号（供图鉴/收集系统使用）；</li>
 *   <li>questFlag：该节点可写入的剧情标记，用于任务/场景解锁；</li>
 *   <li>performanceId / performanceScript：剧情表演时间轴 ID；</li>
 *   <li>voiceLineKey / voiceId：语音口型配置键与声线。</li>
 * </ul>
 * 配置来源于 {@link DialogueTreeRepository} 加载的 DialogueTrees.json。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DialogueNode(
        String nodeId,
        String speaker,
        String text,
        List<Choice> choices,
        String nextNodeId,
        String cutsceneId,
        int unlockCgId,
        String questFlag,
        String performanceId,
        String performanceScript,
        String voiceLineKey,
        String voiceId
) {
    public DialogueNode {
        choices = choices == null ? List.of() : List.copyOf(choices);
        speaker = speaker == null ? "" : speaker;
        text = text == null ? "" : text;
        nextNodeId = nextNodeId == null ? "" : nextNodeId;
        cutsceneId = cutsceneId == null ? "" : cutsceneId;
        questFlag = questFlag == null ? "" : questFlag;
        performanceId = performanceId == null ? "" : performanceId;
        performanceScript = performanceScript == null ? "" : performanceScript;
        voiceLineKey = voiceLineKey == null ? "" : voiceLineKey;
        voiceId = voiceId == null ? "" : voiceId;
    }

    /** 兼容旧 8 字段配置/测试构造。 */
    public DialogueNode(String nodeId, String speaker, String text, List<Choice> choices,
                        String nextNodeId, String cutsceneId, int unlockCgId, String questFlag) {
        this(nodeId, speaker, text, choices, nextNodeId, cutsceneId, unlockCgId, questFlag,
                "", "", "", "");
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Choice(String choiceId, String text, String nextNodeId, String requireFlag, String condition,
                         List<String> impactTags) {
        public Choice {
            choiceId = choiceId == null ? "" : choiceId;
            text = text == null ? "" : text;
            nextNodeId = nextNodeId == null ? "" : nextNodeId;
            requireFlag = requireFlag == null ? "" : requireFlag;
            condition = condition == null ? "" : condition;
            impactTags = impactTags == null ? List.of() : List.copyOf(impactTags);
        }

        public Choice(String choiceId, String text, String nextNodeId, String requireFlag) {
            this(choiceId, text, nextNodeId, requireFlag, "", List.of());
        }

        public Choice(String choiceId, String text, String nextNodeId, String requireFlag, String condition) {
            this(choiceId, text, nextNodeId, requireFlag, condition, List.of());
        }
    }

    public List<Choice> safeChoices() {
        return choices == null ? Collections.emptyList() : choices;
    }
}
