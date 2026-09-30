package cn.itcast.demo.mylunarcore.assist.rl;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 本地强化学习策略服务：基于战斗状态启发式（可替换为离线训练的 DQN/决策树权重）。
 * 目标响应 &lt;50ms，覆盖「何时放大招」等常见建议。
 */
@Service
public class LocalRlPolicyService {

    public record BattleState(List<String> team, List<String> enemies, int remainTurns,
                              Map<String, Integer> buffStacks, boolean ultimateReady, double teamHpRatio) {}

    public record RlAdvice(String action, String reason, double qValue, long latencyMs) {}

    private final Map<String, AtomicLong> actionHits = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> feedbackUseful = new ConcurrentHashMap<>();

    public RlAdvice decide(BattleState state) {
        long start = System.nanoTime();
        if (state == null) {
            return new RlAdvice("observe", "缺少战斗上下文", 0.0, ms(start));
        }
        double qUltimate = scoreUltimate(state);
        double qDefend = scoreDefend(state);
        double qFocus = scoreFocus(state);
        String action;
        double q;
        String reason;
        if (qUltimate >= qDefend && qUltimate >= qFocus) {
            action = "cast_ultimate";
            q = qUltimate;
            reason = state.ultimateReady()
                    ? "敌方血线可收割且大招就绪，建议本回合释放终结技"
                    : "大招即将就绪，优先攒能并保护输出位";
        } else if (qDefend >= qFocus) {
            action = "defend_heal";
            q = qDefend;
            reason = "队伍生存压力高，建议防御/治疗后再输出";
        } else {
            action = "focus_fire";
            q = qFocus;
            reason = "集火残血单位可缩短战斗回合";
        }
        actionHits.computeIfAbsent(action, k -> new AtomicLong()).incrementAndGet();
        return new RlAdvice(action, reason, q, ms(start));
    }

    public void recordFeedback(String action, boolean useful) {
        if (action == null || action.isBlank()) {
            return;
        }
        String key = action.toLowerCase(Locale.ROOT);
        if (useful) {
            feedbackUseful.computeIfAbsent(key, k -> new AtomicLong()).incrementAndGet();
        }
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("actionHits", copyCounts(actionHits));
        m.put("feedbackUseful", copyCounts(feedbackUseful));
        return m;
    }

    private static double scoreUltimate(BattleState s) {
        double score = s.ultimateReady() ? 0.7 : 0.2;
        if (s.remainTurns() > 0 && s.remainTurns() <= 2) {
            score += 0.25;
        }
        if (s.teamHpRatio() >= 0.5) {
            score += 0.1;
        }
        return score;
    }

    private static double scoreDefend(BattleState s) {
        double score = 0.3;
        if (s.teamHpRatio() < 0.35) {
            score += 0.5;
        }
        Integer shield = s.buffStacks() == null ? null : s.buffStacks().get("shield");
        if (shield != null && shield > 0) {
            score -= 0.2;
        }
        return score;
    }

    private static double scoreFocus(BattleState s) {
        double score = 0.4;
        if (s.enemies() != null && s.enemies().size() == 1) {
            score += 0.3;
        }
        return score;
    }

    private static long ms(long startNs) {
        return Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
    }

    private static Map<String, Long> copyCounts(Map<String, AtomicLong> src) {
        Map<String, Long> out = new LinkedHashMap<>();
        src.forEach((k, v) -> out.put(k, v.get()));
        return out;
    }
}
