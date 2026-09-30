package cn.itcast.demo.mylunarcore.assist;

import cn.itcast.demo.mylunarcore.assist.memory.AssistLongTermMemoryService;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 对话情感标记：负面切换安慰风格，并推送 {@link CmdIds#ASSIST_EMOTION_SC_NOTIFY} 调整气泡颜色。
 */
@Service
public class AssistEmotionNotifyService {

    public enum Emotion {
        POSITIVE("positive", "#7CFC98"),
        NEUTRAL("neutral", "#E8E8E8"),
        NEGATIVE("negative", "#FFB4A2");

        private final String tag;
        private final String bubbleColor;

        Emotion(String tag, String bubbleColor) {
            this.tag = tag;
            this.bubbleColor = bubbleColor;
        }

        public String tag() {
            return tag;
        }

        public String bubbleColor() {
            return bubbleColor;
        }
    }

    private final AssistLongTermMemoryService memoryService;
    private final GameSessionManager sessionManager;

    public AssistEmotionNotifyService(AssistLongTermMemoryService memoryService,
                                      ObjectProvider<GameSessionManager> sessionProvider) {
        this.memoryService = memoryService;
        this.sessionManager = sessionProvider == null ? null : sessionProvider.getIfAvailable();
    }

    public Emotion analyze(String text) {
        if (text == null || text.isBlank()) {
            return Emotion.NEUTRAL;
        }
        String t = text.toLowerCase(Locale.ROOT);
        if (containsAny(t, "难受", "生气", "失望", "破防", "讨厌", "差", "坑", "sad", "angry", "hate")) {
            return Emotion.NEGATIVE;
        }
        if (containsAny(t, "开心", "喜欢", "感谢", "棒", "好评", "happy", "love", "thanks")) {
            return Emotion.POSITIVE;
        }
        return Emotion.NEUTRAL;
    }

    public Emotion notify(long uid, String utterance) {
        Emotion e = analyze(utterance);
        memoryService.record(uid, "dialog", e.tag(), "emotion:" + e.tag(), e == Emotion.NEGATIVE ? 1.2 : 0.6);
        if (sessionManager == null) {
            return e;
        }
        String style = e == Emotion.NEGATIVE ? "comfort" : (e == Emotion.POSITIVE ? "celebrate" : "default");
        String json = "{\"emotion\":\"" + e.tag() + "\",\"bubbleColor\":\"" + e.bubbleColor()
                + "\",\"style\":\"" + style + "\"}";
        GameSession s = sessionManager.getOrNull(uid);
        if (s != null) {
            s.send(new GamePacket(CmdIds.ASSIST_EMOTION_SC_NOTIFY, json.getBytes(StandardCharsets.UTF_8)));
        }
        return e;
    }

    private static boolean containsAny(String text, String... keys) {
        for (String k : keys) {
            if (text.contains(k)) {
                return true;
            }
        }
        return false;
    }
}
