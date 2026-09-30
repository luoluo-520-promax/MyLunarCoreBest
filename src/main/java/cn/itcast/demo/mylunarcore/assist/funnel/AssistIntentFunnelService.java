package cn.itcast.demo.mylunarcore.assist.funnel;

import cn.itcast.demo.mylunarcore.assist.AssistAnswer;
import cn.itcast.demo.mylunarcore.assist.AssistAnswerCache;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 三级漏斗：一级本地超轻意图（&lt;100ms）、二级 Jaccard 缓存（&lt;50ms）、三级远程大模型。
 */
@Component
public class AssistIntentFunnelService {

    private static final Pattern NEARBY = Pattern.compile("附近|周围|旁边|有什么");
    private static final Pattern DIALOGUE = Pattern.compile("对话|说什么|聊什么|台词");
    private static final Pattern CHOICE = Pattern.compile("选哪个|怎么选|选项");

    public enum Tier {
        LOCAL_FAST, CACHE, REMOTE
    }

    public record FunnelResult(Tier tier, Optional<AssistAnswer> answer, int estimatedWaitMs) {
    }

    private final LunarCoreProperties properties;
    private final AssistAnswerCache answerCache;

    public AssistIntentFunnelService(LunarCoreProperties properties, AssistAnswerCache answerCache) {
        this.properties = properties;
        this.answerCache = answerCache;
    }

    /**
     * 一级：本地 0.5B 规则代理，处理超简单意图。
     */
    public Optional<FunnelResult> tryTier1Local(long uid, String question, String scene,
                                                String disclaimer, String strategyVersion, String locale) {
        if (!properties.getAiAssist().isIntentFunnelEnabled()) {
            return Optional.empty();
        }
        String q = question == null ? "" : question.trim();
        if (q.isEmpty()) {
            return Optional.empty();
        }
        String resolvedScene = scene == null || scene.isBlank() ? "general" : scene.trim().toLowerCase(Locale.ROOT);
        if (NEARBY.matcher(q).find()) {
            AssistAnswer a = AssistAnswer.of(0,
                    "附近可能有可调查点、宝箱或机关；走近后看屏幕高亮圈，或上传截图让我标出。",
                    "local-tier1", List.of(), List.of("funnel:nearby"),
                    disclaimer, strategyVersion, locale);
            return Optional.of(new FunnelResult(Tier.LOCAL_FAST, Optional.of(a), 80));
        }
        if (DIALOGUE.matcher(q).find()) {
            AssistAnswer a = AssistAnswer.of(0,
                    "靠近 NPC 或调查点触发对话；若卡对话树，告诉我当前选项文字。",
                    "local-tier1", List.of(), List.of("funnel:dialogue"),
                    disclaimer, strategyVersion, locale);
            return Optional.of(new FunnelResult(Tier.LOCAL_FAST, Optional.of(a), 60));
        }
        if (CHOICE.matcher(q).find()) {
            AssistAnswer a = AssistAnswer.of(0,
                    "选项通常影响支线或奖励；优先选带「主线」「继续」或金色标记的条目。",
                    "local-tier1", List.of(), List.of("funnel:choice"),
                    disclaimer, strategyVersion, locale);
            return Optional.of(new FunnelResult(Tier.LOCAL_FAST, Optional.of(a), 70));
        }
        return Optional.empty();
    }

    /**
     * 二级：高相似缓存命中。
     */
    public Optional<FunnelResult> tryTier2Cache(long uid, String question, String scene) {
        Optional<AssistAnswer> hit = answerCache.getSimilar(uid, scene, question,
                properties.getAiAssist().getFunnelSimilarCacheThreshold());
        if (hit.isPresent()) {
            return Optional.of(new FunnelResult(Tier.CACHE, hit, 40));
        }
        return Optional.empty();
    }

    public int estimateRemoteWaitMs(String question, String scene) {
        String q = question == null ? "" : question.toLowerCase(Locale.ROOT);
        String s = scene == null ? "" : scene.toLowerCase(Locale.ROOT);
        if (q.contains("截图") || q.contains("画面") || s.contains("battle")) {
            return 3000;
        }
        if (q.contains("配队") || q.contains("阵容") || q.contains("组吗")) {
            return 2500;
        }
        if (q.contains("养成") || q.contains("先拉") || s.contains("growth")) {
            return 2200;
        }
        return Math.max(800, (int) properties.getAiAssist().getLlmTimeoutMs());
    }
}
