package cn.itcast.demo.mylunarcore.assist;

import cn.itcast.demo.mylunarcore.assist.rl.LocalRlPolicyService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 战斗场景多模态上下文融合：队伍/敌方/回合/Buff + 本地 RL 决策（&lt;50ms）。
 */
@Service
public class AssistBattleRlAdvisor {

    private final LocalRlPolicyService rl;
    private final ObjectMapper objectMapper;

    public AssistBattleRlAdvisor(ObjectProvider<LocalRlPolicyService> rlProvider,
                                 ObjectProvider<ObjectMapper> mapperProvider) {
        this.rl = rlProvider == null ? null : rlProvider.getIfAvailable();
        this.objectMapper = mapperProvider == null || mapperProvider.getIfAvailable() == null
                ? new ObjectMapper() : mapperProvider.getIfAvailable();
    }

    public Optional<AssistAnswer> tryAdvise(long uid, String question, String scene, String battleContextJson) {
        if (rl == null) {
            return Optional.empty();
        }
        String resolved = scene == null ? "" : scene.trim().toLowerCase(Locale.ROOT);
        boolean battleScene = resolved.contains("battle") || looksLikeBattleQuestion(question);
        if (!battleScene) {
            return Optional.empty();
        }
        LocalRlPolicyService.BattleState state = parseState(question, battleContextJson);
        LocalRlPolicyService.RlAdvice advice = rl.decide(state);
        if (advice.latencyMs() > 50) {
            // 超时仍返回，但标记为慢路径，便于监控
        }
        String answer = advice.reason() + "（建议动作：" + advice.action() + "，Q="
                + String.format(Locale.ROOT, "%.2f", advice.qValue()) + "）";
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("rlAction", advice.action());
        meta.put("qValue", advice.qValue());
        meta.put("latencyMs", advice.latencyMs());
        meta.put("uid", uid);
        return Optional.of(AssistAnswer.of(0, answer, "local-rl", List.of(),
                List.of("rl:" + advice.action())));
    }

    public void onFeedback(String action, boolean useful) {
        if (rl != null) {
            rl.recordFeedback(action, useful);
        }
    }

    private LocalRlPolicyService.BattleState parseState(String question, String battleContextJson) {
        Map<String, Object> raw = new LinkedHashMap<>();
        if (battleContextJson != null && !battleContextJson.isBlank()) {
            try {
                raw.putAll(objectMapper.readValue(battleContextJson, new TypeReference<>() {}));
            } catch (Exception ignored) {
                // fall through
            }
        }
        if (raw.isEmpty() && question != null && question.trim().startsWith("{")) {
            try {
                raw.putAll(objectMapper.readValue(question.trim(), new TypeReference<>() {}));
            } catch (Exception ignored) {
                // ignore
            }
        }
        List<String> team = stringList(raw.get("team"));
        List<String> enemies = stringList(raw.get("enemies"));
        int remainTurns = intVal(raw.get("remainTurns"), 3);
        @SuppressWarnings("unchecked")
        Map<String, Integer> buffs = raw.get("buffs") instanceof Map<?, ?> m
                ? (Map<String, Integer>) m : Map.of();
        boolean ultimateReady = Boolean.TRUE.equals(raw.get("ultimateReady"))
                || (question != null && question.contains("大招"));
        double hp = doubleVal(raw.get("teamHpRatio"), 0.7);
        return new LocalRlPolicyService.BattleState(team, enemies, remainTurns, buffs, ultimateReady, hp);
    }

    private static boolean looksLikeBattleQuestion(String q) {
        if (q == null) {
            return false;
        }
        String s = q.toLowerCase(Locale.ROOT);
        return s.contains("大招") || s.contains("终结技") || s.contains("何时放")
                || s.contains("ultimate") || s.contains("battle") || s.contains("回合");
    }

    private static List<String> stringList(Object v) {
        if (!(v instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object o : list) {
            if (o != null) {
                out.add(String.valueOf(o));
            }
        }
        return out;
    }

    private static int intVal(Object v, int dft) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return v == null ? dft : Integer.parseInt(String.valueOf(v));
        } catch (Exception e) {
            return dft;
        }
    }

    private static double doubleVal(Object v, double dft) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return v == null ? dft : Double.parseDouble(String.valueOf(v));
        } catch (Exception e) {
            return dft;
        }
    }
}
