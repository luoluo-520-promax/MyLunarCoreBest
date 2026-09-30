package cn.itcast.demo.mylunarcore.player;

import cn.itcast.demo.mylunarcore.assist.AssistNettyService;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.protocol.AssistSystemProto;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 体力即将溢出的智能提醒：≥80% 且近期不活跃时通过 AiHintNotify 下发。
 * <p>
 * 场景感知：主线剧情 / 对话树中推迟推送，回到 HALL 或 SCENE（野外）后再发；
 * 文案采用列车长叙事风格，降低系统弹窗冰冷感。
 */
@Service
public class StaminaOverflowHintService {

    private static final long COOLDOWN_MS = 30 * 60_000L;

    private final StaminaService staminaService;
    private final GameSessionManager sessionManager;
    private final AssistNettyService assistNettyService;
    private final Map<Integer, Long> lastHintAt = new ConcurrentHashMap<>();
    /** 因剧情/对话推迟的提醒队列 */
    private final Map<Integer, Long> deferredUntil = new ConcurrentHashMap<>();

    public StaminaOverflowHintService(StaminaService staminaService,
                                      ObjectProvider<GameSessionManager> sessionProvider,
                                      ObjectProvider<AssistNettyService> assistProvider) {
        this.staminaService = staminaService;
        this.sessionManager = sessionProvider == null ? null : sessionProvider.getIfAvailable();
        this.assistNettyService = assistProvider == null ? null : assistProvider.getIfAvailable();
    }

    /** 供单测直接调用。体力≥80% 且最近不活跃时提醒。 */
    public boolean maybeHint(int playerId, long nowMs, long lastActiveMs) {
        return maybeHint(playerId, nowMs, lastActiveMs, null);
    }

    public boolean maybeHint(int playerId, long nowMs, long lastActiveMs, PlayerSessionState state) {
        StaminaService.StaminaSnapshot snap = staminaService.snapshot(playerId);
        if (snap.max() <= 0 || snap.current() * 100 < snap.max() * 80) {
            return false;
        }
        Long last = lastHintAt.get(playerId);
        if (last != null && nowMs - last < COOLDOWN_MS) {
            return false;
        }
        boolean likelyOfflineSoon = lastActiveMs <= 0 || nowMs - lastActiveMs >= 10 * 60_000L;
        if (!likelyOfflineSoon) {
            return false;
        }
        PlayerSessionState effective = state;
        if (effective == null && sessionManager != null) {
            GameSession session = sessionManager.getOrNull(playerId);
            if (session != null) {
                effective = session.getSessionState();
            }
        }
        if (shouldDefer(effective)) {
            deferredUntil.put(playerId, nowMs);
            return false;
        }
        deferredUntil.remove(playerId);
        lastHintAt.put(playerId, nowMs);
        String answer = narrativeHint(snap.current(), snap.max());
        if (sessionManager != null) {
            GameSession session = sessionManager.getOrNull(playerId);
            if (session != null && session.getChannel() != null && session.getChannel().isActive()) {
                AssistSystemProto.AiHintNotify notify = AssistSystemProto.AiHintNotify.newBuilder()
                        .setRequestId("stamina-" + playerId + "-" + nowMs)
                        .setAnswer(answer)
                        .setSource("conductor")
                        .setHintType("stamina_overflow")
                        .setForbidExternalBrowser(true)
                        .setDisclaimer("来自列车长的贴心提醒")
                        .build();
                session.getChannel().writeAndFlush(new GamePacket(CmdIds.AI_HINT_SC_NOTIFY, notify.toByteArray()));
            }
        }
        return true;
    }

    public static boolean shouldDefer(PlayerSessionState state) {
        return state == PlayerSessionState.STORY || state == PlayerSessionState.DIALOGUE
                || state == PlayerSessionState.BATTLE;
    }

    public static String narrativeHint(int current, int max) {
        return "列车长轻拍你的肩膀：「开拓者，体力快满啦（" + current + "/" + max
                + "）——要不要先去材料本逛一圈？风景再好看，溢出的体力可带不走哟。」";
    }

    @Scheduled(fixedDelay = 60_000L)
    public void tickOnlinePlayers() {
        if (sessionManager == null) {
            return;
        }
        long now = System.currentTimeMillis();
        for (GameSession session : sessionManager.snapshotSessions()) {
            if (session == null) {
                continue;
            }
            int pid = (int) session.getUid();
            // 曾推迟且现已回到可推送状态 → 优先补发
            if (deferredUntil.containsKey(pid) && !shouldDefer(session.getSessionState())) {
                maybeHint(pid, now, 0L, session.getSessionState());
                continue;
            }
            maybeHint(pid, now, session.getLastActiveMillis(), session.getSessionState());
        }
    }
}
