package cn.itcast.demo.mylunarcore.player;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 玩家会话玩法状态机：非法迁移直接拒绝。
 */
public final class PlayerSessionStateMachine {

    private static final Map<PlayerSessionState, Set<PlayerSessionState>> ALLOWED = new EnumMap<>(PlayerSessionState.class);

    static {
        ALLOWED.put(PlayerSessionState.HALL, EnumSet.of(
                PlayerSessionState.SCENE, PlayerSessionState.MATCHING, PlayerSessionState.BATTLE,
                PlayerSessionState.CHALLENGE, PlayerSessionState.ROGUE,
                PlayerSessionState.STORY, PlayerSessionState.DIALOGUE));
        ALLOWED.put(PlayerSessionState.SCENE, EnumSet.of(
                PlayerSessionState.HALL, PlayerSessionState.BATTLE, PlayerSessionState.CHALLENGE,
                PlayerSessionState.ROGUE, PlayerSessionState.MATCHING,
                PlayerSessionState.STORY, PlayerSessionState.DIALOGUE));
        ALLOWED.put(PlayerSessionState.MATCHING, EnumSet.of(
                PlayerSessionState.HALL, PlayerSessionState.BATTLE, PlayerSessionState.CHALLENGE,
                PlayerSessionState.SCENE));
        ALLOWED.put(PlayerSessionState.BATTLE, EnumSet.of(
                PlayerSessionState.HALL, PlayerSessionState.SCENE, PlayerSessionState.CHALLENGE));
        ALLOWED.put(PlayerSessionState.CHALLENGE, EnumSet.of(
                PlayerSessionState.HALL, PlayerSessionState.SCENE, PlayerSessionState.BATTLE));
        ALLOWED.put(PlayerSessionState.ROGUE, EnumSet.of(
                PlayerSessionState.HALL, PlayerSessionState.SCENE));
        ALLOWED.put(PlayerSessionState.STORY, EnumSet.of(
                PlayerSessionState.HALL, PlayerSessionState.SCENE, PlayerSessionState.DIALOGUE));
        ALLOWED.put(PlayerSessionState.DIALOGUE, EnumSet.of(
                PlayerSessionState.HALL, PlayerSessionState.SCENE, PlayerSessionState.STORY));
    }

    private PlayerSessionStateMachine() {
    }

    public static boolean canTransition(PlayerSessionState from, PlayerSessionState to) {
        if (from == null || to == null) {
            return false;
        }
        if (from == to) {
            return true;
        }
        Set<PlayerSessionState> next = ALLOWED.get(from);
        return next != null && next.contains(to);
    }

    public static void transition(GameSession session, PlayerSessionState to) {
        if (session == null || to == null) {
            throw new IllegalArgumentException("session/to required");
        }
        PlayerSessionState from = session.getSessionState();
        if (!canTransition(from, to)) {
            throw new IllegalStateException("illegal session transition: " + from + " -> " + to);
        }
        session.setSessionState(to);
    }

    public static boolean tryTransition(GameSession session, PlayerSessionState to) {
        if (session == null || to == null) {
            return false;
        }
        PlayerSessionState from = session.getSessionState();
        if (!canTransition(from, to)) {
            return false;
        }
        session.setSessionState(to);
        return true;
    }
}
