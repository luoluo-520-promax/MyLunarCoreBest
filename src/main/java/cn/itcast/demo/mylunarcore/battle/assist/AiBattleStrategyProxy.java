package cn.itcast.demo.mylunarcore.battle.assist;

import cn.itcast.demo.mylunarcore.battle.BattleContext;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 策略代理实验开关：在启发式建议之上输出可一键采纳的行动指令 JSON
 * （优先破盾/集火等），由客户端确认后发送战斗指令包，避免外挂直连。
 */
@Component
public class AiBattleStrategyProxy {

    private final HeuristicBattleAssistPolicy heuristic;
    private final LunarCoreProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();

    public AiBattleStrategyProxy(HeuristicBattleAssistPolicy heuristic, LunarCoreProperties properties) {
        this.heuristic = heuristic;
        this.properties = properties;
    }

    public boolean isStrategyProxyEnabled() {
        return properties.getAiAssist().isBattleStrategyProxyEnabled();
    }

    /**
     * 生成行动指令；未开启时返回空 map。
     * 字段：skillId / targetIds / priority(focus|break_shield) / confirmRequired=true
     */
    public Map<String, Object> buildActionCommand(BattleContext context, int actorEntityId) {
        Map<String, Object> cmd = new LinkedHashMap<>();
        if (!isStrategyProxyEnabled() || !heuristic.isEnabledFor(context)) {
            cmd.put("enabled", false);
            return cmd;
        }
        BattleAssistPolicy.Suggestion s = heuristic.suggest(context, actorEntityId);
        cmd.put("enabled", true);
        cmd.put("skillId", s.skillId());
        cmd.put("targetIds", s.targetIds() == null ? List.of() : s.targetIds());
        cmd.put("priority", inferPriority(s));
        cmd.put("confirmRequired", true);
        cmd.put("reason", s.reason());
        cmd.put("weaknessAdvice", s.weaknessAdvice());
        cmd.put("switchAdvice", s.switchAdvice());
        return cmd;
    }

    public String buildActionCommandJson(BattleContext context, int actorEntityId) {
        try {
            return mapper.writeValueAsString(buildActionCommand(context, actorEntityId));
        } catch (Exception e) {
            return "{\"enabled\":false}";
        }
    }

    private static String inferPriority(BattleAssistPolicy.Suggestion s) {
        String reason = s.reason() == null ? "" : s.reason().toLowerCase();
        String weak = s.weaknessAdvice() == null ? "" : s.weaknessAdvice().toLowerCase();
        if (reason.contains("break") || weak.contains("破盾") || weak.contains("break")) {
            return "break_shield";
        }
        return "focus";
    }
}
