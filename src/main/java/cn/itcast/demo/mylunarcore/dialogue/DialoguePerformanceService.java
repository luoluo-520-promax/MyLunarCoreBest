package cn.itcast.demo.mylunarcore.dialogue;

import cn.itcast.demo.mylunarcore.assist.AssistTtsService;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 剧情表演指令集：进入对话节点时下发 Timeline，驱动表情/动作/语音/镜头对齐播放。
 */
@Service
public class DialoguePerformanceService {

    private static final Logger log = LoggerFactory.getLogger(DialoguePerformanceService.class);

    public record PerformancePush(String performanceId, String timelineJson, String voiceId,
                                  int estimatedDurationMs, String audioKey) {}

    private final DialoguePerformanceScriptRepository scriptRepository;
    private final DialogueVoiceConfigRepository voiceRepository;
    private final ObjectMapper objectMapper;
    private final GameSessionManager sessionManager;
    private final AssistTtsService ttsService;

    public DialoguePerformanceService(DialoguePerformanceScriptRepository scriptRepository,
                                      DialogueVoiceConfigRepository voiceRepository,
                                      ObjectMapper objectMapper,
                                      ObjectProvider<GameSessionManager> sessionProvider,
                                      ObjectProvider<AssistTtsService> ttsProvider) {
        this.scriptRepository = scriptRepository;
        this.voiceRepository = voiceRepository;
        this.objectMapper = objectMapper;
        this.sessionManager = sessionProvider == null ? null : sessionProvider.getIfAvailable();
        this.ttsService = ttsProvider == null ? null : ttsProvider.getIfAvailable();
    }

    /**
     * 进入节点时推送表演时间轴；无脚本时仅尝试语音口型。
     */
    public PerformancePush pushOnEnterNode(int playerId, String treeId, DialogueNode node) {
        if (node == null) {
            return null;
        }
        String performanceId = firstNonBlank(node.performanceId(), node.performanceScript());
        DialoguePerformanceScriptRepository.Script script =
                performanceId == null ? null : scriptRepository.find(performanceId);

        String lineKey = firstNonBlank(node.voiceLineKey(),
                (treeId == null ? "" : "tree_" + treeId + "_" + node.nodeId()));
        DialogueVoiceConfigRepository.VoiceEntry voice = voiceRepository.find(lineKey);
        String voiceId = voice != null ? voice.voiceId() : firstNonBlank(node.voiceId(), "");
        String audioKey = voice != null ? voice.audioKey() : "";
        int durationMs = script != null ? script.estimatedDurationMs()
                : (voice != null ? voice.durationMs() : estimateTextDuration(node.text()));

        // 非关键台词且无配置音频：轻量 TTS 补位（仅生成元数据，不强制下发音频块）
        if ((audioKey == null || audioKey.isBlank()) && ttsService != null
                && node.text() != null && !node.text().isBlank()) {
            try {
                AssistTtsService.TtsClip clip = ttsService.synthesize(playerId, node.text(),
                        voiceId == null || voiceId.isBlank() ? "default" : voiceId);
                if (clip != null && clip.voiceId() != null) {
                    voiceId = clip.voiceId();
                    audioKey = "tts:" + voiceId;
                }
            } catch (Exception e) {
                log.debug("tts fallback skipped: {}", e.getMessage());
            }
        }

        String timelineJson = script != null
                ? scriptRepository.toTimelineJson(script)
                : buildVoiceOnlyTimeline(voice, durationMs);

        PerformancePush push = new PerformancePush(
                performanceId == null ? "" : performanceId,
                timelineJson,
                voiceId == null ? "" : voiceId,
                durationMs,
                audioKey == null ? "" : audioKey);
        notifyClient(playerId, node.nodeId(), push, voice);
        return push;
    }

    /** 选项 impact 反馈：插入 1–2 秒收尾动作（点头/微笑）。 */
    public void pushChoiceAck(int playerId, String treeId, DialogueNode.Choice choice) {
        if (choice == null || choice.impactTags() == null || choice.impactTags().isEmpty()) {
            return;
        }
        DialoguePerformanceScriptRepository.Script ack = scriptRepository.find("perf_affinity_ack");
        if (ack == null) {
            return;
        }
        PerformancePush push = new PerformancePush(
                "perf_affinity_ack",
                scriptRepository.toTimelineJson(ack),
                "",
                ack.estimatedDurationMs(),
                "");
        notifyClient(playerId, "choice_ack", push, null);
    }

    private void notifyClient(int playerId, String nodeId, PerformancePush push,
                              DialogueVoiceConfigRepository.VoiceEntry voice) {
        if (sessionManager == null || push == null) {
            return;
        }
        GameSession session = sessionManager.getOrNull(playerId);
        if (session == null) {
            return;
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("nodeId", nodeId == null ? "" : nodeId);
            body.put("performanceId", push.performanceId());
            body.put("timelineJson", push.timelineJson());
            body.put("voiceId", push.voiceId());
            body.put("audioKey", push.audioKey());
            body.put("estimatedDurationMs", push.estimatedDurationMs());
            if (voice != null) {
                body.put("lipSyncTimestamps", voice.lipSyncTimestamps());
                body.put("durationMs", voice.durationMs());
            }
            byte[] payload = objectMapper.writeValueAsString(body).getBytes(StandardCharsets.UTF_8);
            session.send(new GamePacket(CmdIds.DIALOGUE_PERFORMANCE_SC_NOTIFY, payload));
        } catch (Exception e) {
            log.debug("dialogue performance notify failed: {}", e.getMessage());
        }
    }

    private String buildVoiceOnlyTimeline(DialogueVoiceConfigRepository.VoiceEntry voice, int durationMs) {
        try {
            Map<String, Object> beat = new LinkedHashMap<>();
            beat.put("type", "PLAY_VOICE");
            beat.put("start_time", 0);
            Map<String, Object> payload = new LinkedHashMap<>();
            if (voice != null) {
                payload.put("lineKey", voice.lineKey());
                payload.put("audioKey", voice.audioKey());
                payload.put("lipSyncTimestamps", voice.lipSyncTimestamps());
            } else {
                payload.put("lipSyncMode", "random");
            }
            payload.put("durationMs", durationMs);
            beat.put("payload", payload);
            return objectMapper.writeValueAsString(java.util.List.of(beat));
        } catch (Exception e) {
            return "[]";
        }
    }

    private static int estimateTextDuration(String text) {
        if (text == null || text.isBlank()) {
            return 1200;
        }
        return Math.min(12_000, Math.max(1200, text.length() * 120));
    }

    private static String firstNonBlank(String a, String b) {
        if (a != null && !a.isBlank()) {
            return a;
        }
        if (b != null && !b.isBlank()) {
            return b;
        }
        return null;
    }
}
