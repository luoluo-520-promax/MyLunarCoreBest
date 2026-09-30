package cn.itcast.demo.mylunarcore.activity;

import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 活动流程 DSL：STEP_START → STEP_PLAY → STEP_SETTLE → STEP_REWARD，
 * 通过 {@link CmdIds#ACTIVITY_FLOW_SC_NOTIFY} 指导客户端泛用 UI 容器渲染。
 */
@Service
public class ActivityFlowDslService {

    public enum FlowStep {
        STEP_START,
        STEP_PLAY,
        STEP_SETTLE,
        STEP_REWARD
    }

    public record FlowState(int activityId, int playerId, FlowStep step, String panelId,
                            Map<String, Object> attrs, long updatedAtMs) {}

    private final ConcurrentHashMap<String, FlowState> flows = new ConcurrentHashMap<>();
    private final ActivityTemplateService templateService;
    private final GameSessionManager sessionManager;

    public ActivityFlowDslService(ActivityTemplateService templateService,
                                  ObjectProvider<GameSessionManager> sessionProvider) {
        this.templateService = templateService;
        this.sessionManager = sessionProvider == null ? null : sessionProvider.getIfAvailable();
    }

    public FlowState start(int playerId, int activityId, String panelId) {
        FlowState state = new FlowState(activityId, playerId, FlowStep.STEP_START,
                panelId == null ? "generic_activity" : panelId,
                Map.of("hint", "start"), System.currentTimeMillis());
        flows.put(key(playerId, activityId), state);
        push(state);
        return advance(playerId, activityId, FlowStep.STEP_PLAY, Map.of("hint", "play"));
    }

    public FlowState advance(int playerId, int activityId, FlowStep next, Map<String, Object> attrs) {
        String k = key(playerId, activityId);
        FlowState cur = flows.get(k);
        if (cur == null) {
            cur = new FlowState(activityId, playerId, FlowStep.STEP_START, "generic_activity",
                    Map.of(), System.currentTimeMillis());
        }
        if (!canAdvance(cur.step(), next)) {
            return cur;
        }
        Map<String, Object> merged = new LinkedHashMap<>(cur.attrs());
        if (attrs != null) {
            merged.putAll(attrs);
        }
        FlowState updated = new FlowState(activityId, playerId, next, cur.panelId(),
                Map.copyOf(merged), System.currentTimeMillis());
        flows.put(k, updated);
        push(updated);
        return updated;
    }

    /** 结算并进入发奖；可选用 Groovy 脚本算分。 */
    public FlowState settle(int playerId, int activityId, String scriptId, Map<String, Object> ctx) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (scriptId != null && !scriptId.isBlank()) {
            ActivityTemplateService.OpResult r = templateService.invokeScript(scriptId, playerId, activityId, ctx);
            payload.put("scriptOk", r.success());
            payload.put("scriptRet", r.retcode());
            payload.putAll(r.payload());
        }
        payload.put("hint", "settle");
        FlowState settled = advance(playerId, activityId, FlowStep.STEP_SETTLE, payload);
        return advance(playerId, activityId, FlowStep.STEP_REWARD, Map.of("hint", "reward"));
    }

    public FlowState get(int playerId, int activityId) {
        return flows.get(key(playerId, activityId));
    }

    public List<FlowState> snapshot(int playerId) {
        List<FlowState> out = new ArrayList<>();
        String prefix = playerId + ":";
        flows.forEach((k, v) -> {
            if (k.startsWith(prefix)) {
                out.add(v);
            }
        });
        return out;
    }

    private void push(FlowState state) {
        if (sessionManager == null) {
            return;
        }
        StringBuilder attrs = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : state.attrs().entrySet()) {
            if (!first) {
                attrs.append(',');
            }
            first = false;
            attrs.append('"').append(e.getKey()).append("\":\"").append(String.valueOf(e.getValue())).append('"');
        }
        attrs.append('}');
        String json = "{\"activityId\":" + state.activityId() + ",\"step\":\"" + state.step().name()
                + "\",\"panelId\":\"" + state.panelId() + "\",\"attrs\":" + attrs + "}";
        GameSession s = sessionManager.getOrNull(state.playerId());
        if (s != null) {
            s.send(new GamePacket(CmdIds.ACTIVITY_FLOW_SC_NOTIFY, json.getBytes(StandardCharsets.UTF_8)));
        }
    }

    private static boolean canAdvance(FlowStep cur, FlowStep next) {
        if (cur == null || next == null) {
            return false;
        }
        return switch (cur) {
            case STEP_START -> next == FlowStep.STEP_PLAY;
            case STEP_PLAY -> next == FlowStep.STEP_SETTLE || next == FlowStep.STEP_PLAY;
            case STEP_SETTLE -> next == FlowStep.STEP_REWARD;
            case STEP_REWARD -> false;
        };
    }

    private static String key(int playerId, int activityId) {
        return playerId + ":" + activityId;
    }
}
