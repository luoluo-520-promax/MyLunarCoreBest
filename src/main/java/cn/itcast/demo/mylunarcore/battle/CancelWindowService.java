package cn.itcast.demo.mylunarcore.battle;

import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 取消后摇窗口：普攻命中后短窗内可接更高优先级动作；通过 {@link CmdIds#CANCEL_WINDOW_SC_NOTIFY} 下发。
 * 优先级复用 {@link PlayerInputBufferService.CancelTier}：闪避 &gt; 终结技 &gt; 战技 &gt; 普攻。
 */
@Service
public class CancelWindowService {

    /** 普攻命中后默认可接战技的窗口（毫秒） */
    public static final int DEFAULT_BASIC_CANCEL_MS = 100;

    public record CancelWindow(long battleId, int entityId, int fromTier, int windowMs,
                               List<Integer> allowTiers, long openAtMs, long closeAtMs) {}

    private final Map<String, CancelWindow> windows = new ConcurrentHashMap<>();
    private final GameSessionManager sessionManager;

    public CancelWindowService(ObjectProvider<GameSessionManager> sessionProvider) {
        this.sessionManager = sessionProvider == null ? null : sessionProvider.getIfAvailable();
    }

    private static String key(long battleId, int entityId) {
        return battleId + ":" + entityId;
    }

    /**
     * 开启取消窗口并推送客户端。
     *
     * @param fromTier  当前完成动作的优先级（如 BASIC=10）
     * @param windowMs  窗口时长；普攻默认 100ms
     */
    public CancelWindow open(long battleId, int entityId, int playerId, int fromTier, int windowMs) {
        long now = System.currentTimeMillis();
        int ms = windowMs <= 0 ? DEFAULT_BASIC_CANCEL_MS : windowMs;
        List<Integer> allow = List.of(
                PlayerInputBufferService.CancelTier.SKILL.priority(),
                PlayerInputBufferService.CancelTier.ULT.priority(),
                PlayerInputBufferService.CancelTier.DODGE.priority());
        CancelWindow w = new CancelWindow(battleId, entityId, fromTier, ms, allow, now, now + ms);
        windows.put(key(battleId, entityId), w);
        push(playerId, w);
        return w;
    }

    public boolean isOpen(long battleId, int entityId, long nowMs) {
        CancelWindow w = windows.get(key(battleId, entityId));
        return w != null && nowMs >= w.openAtMs() && nowMs <= w.closeAtMs();
    }

    public boolean canCancelWith(long battleId, int entityId, int incomingTier, long nowMs) {
        CancelWindow w = windows.get(key(battleId, entityId));
        if (w == null || nowMs > w.closeAtMs()) {
            return false;
        }
        return incomingTier > w.fromTier() && w.allowTiers().contains(incomingTier);
    }

    public CancelWindow get(long battleId, int entityId) {
        return windows.get(key(battleId, entityId));
    }

    private void push(int playerId, CancelWindow w) {
        if (sessionManager == null || playerId <= 0) {
            return;
        }
        GameSession s = sessionManager.getOrNull(playerId);
        if (s == null) {
            return;
        }
        String json = "{\"battleId\":" + w.battleId()
                + ",\"entityId\":" + w.entityId()
                + ",\"fromTier\":" + w.fromTier()
                + ",\"windowMs\":" + w.windowMs()
                + ",\"allowTiers\":" + w.allowTiers()
                + ",\"closeAtMs\":" + w.closeAtMs() + "}";
        s.send(new GamePacket(CmdIds.CANCEL_WINDOW_SC_NOTIFY, json.getBytes(StandardCharsets.UTF_8)));
    }
}
