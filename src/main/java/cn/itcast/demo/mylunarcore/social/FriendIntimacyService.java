package cn.itcast.demo.mylunarcore.social;

import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 好友亲密度：一起战斗 / 互赠体力 / 访问家园提升，解锁头像框与称号。
 */
@Service
public class FriendIntimacyService {

    public enum Action {
        BATTLE_TOGETHER(5),
        STAMINA_GIFT(3),
        HOME_VISIT(2);

        private final int delta;

        Action(int delta) {
            this.delta = delta;
        }

        public int delta() {
            return delta;
        }
    }

    public record IntimacySnapshot(int playerA, int playerB, int points, int level, String title, String frameId) {}

    private final ConcurrentHashMap<Long, AtomicInteger> points = new ConcurrentHashMap<>();
    private final GameSessionManager sessionManager;

    public FriendIntimacyService(ObjectProvider<GameSessionManager> sessionProvider) {
        this.sessionManager = sessionProvider == null ? null : sessionProvider.getIfAvailable();
    }

    public IntimacySnapshot add(int playerA, int playerB, Action action) {
        if (playerA <= 0 || playerB <= 0 || playerA == playerB || action == null) {
            return null;
        }
        long key = pairKey(playerA, playerB);
        int total = points.computeIfAbsent(key, k -> new AtomicInteger()).addAndGet(action.delta());
        IntimacySnapshot snap = snapshot(playerA, playerB, total);
        push(playerA, snap);
        push(playerB, snap);
        return snap;
    }

    public IntimacySnapshot get(int playerA, int playerB) {
        AtomicInteger a = points.get(pairKey(playerA, playerB));
        int total = a == null ? 0 : a.get();
        return snapshot(playerA, playerB, total);
    }

    public int levelOf(int points) {
        if (points >= 500) {
            return 5;
        }
        if (points >= 200) {
            return 4;
        }
        if (points >= 80) {
            return 3;
        }
        if (points >= 30) {
            return 2;
        }
        if (points >= 10) {
            return 1;
        }
        return 0;
    }

    private IntimacySnapshot snapshot(int a, int b, int total) {
        int level = levelOf(total);
        String title = switch (level) {
            case 5 -> "魂牵梦萦";
            case 4 -> "形影不离";
            case 3 -> "默契搭档";
            case 2 -> "相识相惜";
            case 1 -> "初识友人";
            default -> "";
        };
        String frame = level >= 3 ? "frame_intimacy_" + level : "";
        return new IntimacySnapshot(a, b, total, level, title, frame);
    }

    private void push(int playerId, IntimacySnapshot snap) {
        if (sessionManager == null || snap == null) {
            return;
        }
        String json = "{\"friendUid\":" + (snap.playerA() == playerId ? snap.playerB() : snap.playerA())
                + ",\"points\":" + snap.points() + ",\"level\":" + snap.level()
                + ",\"title\":\"" + snap.title() + "\",\"frameId\":\"" + snap.frameId() + "\"}";
        GameSession s = sessionManager.getOrNull(playerId);
        if (s != null) {
            s.send(new GamePacket(CmdIds.FRIEND_INTIMACY_SC_NOTIFY, json.getBytes(StandardCharsets.UTF_8)));
        }
    }

    private static long pairKey(int a, int b) {
        int lo = Math.min(a, b);
        int hi = Math.max(a, b);
        return ((long) lo << 32) | (hi & 0xffffffffL);
    }

    public Map<String, Object> describe(int playerA, int playerB) {
        IntimacySnapshot s = get(playerA, playerB);
        return Map.of(
                "points", s.points(),
                "level", s.level(),
                "title", s.title(),
                "frameId", s.frameId());
    }
}
