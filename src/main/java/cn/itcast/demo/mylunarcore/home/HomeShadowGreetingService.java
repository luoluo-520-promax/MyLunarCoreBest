package cn.itcast.demo.mylunarcore.home;

import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.player.StaminaOverflowService;
import cn.itcast.demo.mylunarcore.protocol.HomeSystemProto;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 好友家园虚影点赞/赠礼：对方上线收到 Notify，并回馈微量储备体力。
 */
@Service
public class HomeShadowGreetingService {

    public static final int RESERVE_GIFT = 5;
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    public record Result(int retcode, int hostPlayerId, int reserveGifted) {}

    public record Pending(long fromUid, String fromName, String greetingType, int reserve) {}

    private final Map<String, Boolean> dailyDone = new ConcurrentHashMap<>();
    private final Map<Integer, List<Pending>> pending = new ConcurrentHashMap<>();
    private final GameSessionManager sessionManager;
    private final ObjectProvider<StaminaOverflowService> overflowProvider;

    public HomeShadowGreetingService(GameSessionManager sessionManager,
                                     ObjectProvider<StaminaOverflowService> overflowProvider) {
        this.sessionManager = sessionManager;
        this.overflowProvider = overflowProvider;
    }

    /**
     * @return retcode 0成功 3今日已问候 4目标无效
     */
    public Result greet(int visitorId, String visitorName, int hostPlayerId, long silhouetteUid, String greetingType) {
        if (hostPlayerId <= 0 || visitorId <= 0 || visitorId == hostPlayerId) {
            return new Result(4, hostPlayerId, 0);
        }
        long target = silhouetteUid > 0 ? silhouetteUid : hostPlayerId;
        String day = LocalDate.now(ZONE).toString();
        String key = day + ":" + visitorId + "->" + target;
        if (dailyDone.putIfAbsent(key, Boolean.TRUE) != null) {
            return new Result(3, hostPlayerId, 0);
        }
        String type = greetingType == null || greetingType.isBlank() ? "like" : greetingType.trim();
        int gift = "gift".equalsIgnoreCase(type) ? RESERVE_GIFT : Math.max(1, RESERVE_GIFT / 2);
        StaminaOverflowService overflow = overflowProvider == null ? null : overflowProvider.getIfAvailable();
        if (overflow != null) {
            overflow.addReserve((int) target, gift);
        }
        Pending p = new Pending(visitorId, visitorName == null ? ("旅人" + visitorId) : visitorName, type, gift);
        GameSession online = sessionManager.getOrNull((int) target);
        if (online != null) {
            pushNotify(online, p);
        } else {
            pending.computeIfAbsent((int) target, k -> new CopyOnWriteArrayList<>()).add(p);
        }
        return new Result(0, hostPlayerId, gift);
    }

    /** 登录时冲刷离线期间收到的虚影问候。 */
    public void flushPending(int playerId, GameSession session) {
        List<Pending> list = pending.remove(playerId);
        if (list == null || session == null) {
            return;
        }
        for (Pending p : new ArrayList<>(list)) {
            pushNotify(session, p);
        }
    }

    private void pushNotify(GameSession session, Pending p) {
        HomeSystemProto.FriendShadowGreetingNotify notify =
                HomeSystemProto.FriendShadowGreetingNotify.newBuilder()
                        .setFromPlayerUid(p.fromUid())
                        .setFromName(p.fromName())
                        .setGreetingType(p.greetingType())
                        .setReserveStamina(p.reserve())
                        .setFloatText(p.fromName() + " 向你的虚影打了个招呼，储备体力 +" + p.reserve())
                        .build();
        session.send(new GamePacket(CmdIds.FRIEND_SHADOW_GREETING_SC_NOTIFY, notify.toByteArray()));
    }
}
