package cn.itcast.demo.mylunarcore.player;

import cn.itcast.demo.mylunarcore.center.MigrationTicketService;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 场景切换加载过渡态：签发 LoadingTicket，加载完成前拒绝移动/开战，避免 MoveSpeedGuard 误伤。
 * 单机切图与多节点迁移共用 {@link MigrationTicketService} 票据语义。
 * <p>
 * 若目标 Plane 已后台预加载，则进入 handshake 模式：票据仅作校验握手，TTL 更短。
 */
@Service
public class PlayerLoadingStateService {

    public record LoadingTicket(String ticket, long playerUid, int planeId, int floorId,
                                long expireAtMillis, boolean handshakeOnly, String transitionType) {
        public LoadingTicket(String ticket, long playerUid, int planeId, int floorId, long expireAtMillis) {
            this(ticket, playerUid, planeId, floorId, expireAtMillis, false, "BLACK");
        }

        public LoadingTicket(String ticket, long playerUid, int planeId, int floorId, long expireAtMillis,
                             boolean handshakeOnly) {
            this(ticket, playerUid, planeId, floorId, expireAtMillis, handshakeOnly,
                    handshakeOnly ? "FADE" : "BLACK");
        }
    }

    private static final long DEFAULT_TTL_MS = 120_000L;
    private static final long HANDSHAKE_TTL_MS = 30_000L;

    private final MigrationTicketService migrationTicketService;
    private final Map<Long, LoadingState> loadingByUid = new ConcurrentHashMap<>();

    public PlayerLoadingStateService(MigrationTicketService migrationTicketService) {
        this.migrationTicketService = migrationTicketService;
    }

    /**
     * 开始切图：签发迁移/加载票据，并标记玩家进入 LOADING。
     */
    public LoadingTicket beginLoading(long playerUid, int planeId, int floorId, int entryId,
                                      float posX, float posY, float posZ) {
        return beginLoading(playerUid, planeId, floorId, entryId, posX, posY, posZ, false);
    }

    /**
     * @param handshakeOnly true=预加载已命中，LoadingTicket 仅作校验握手
     */
    public LoadingTicket beginLoading(long playerUid, int planeId, int floorId, int entryId,
                                      float posX, float posY, float posZ, boolean handshakeOnly) {
        String transitionType = handshakeOnly ? "DISSOLVE" : "BLACK";
        return beginLoading(playerUid, planeId, floorId, entryId, posX, posY, posZ, handshakeOnly, transitionType);
    }

    public LoadingTicket beginLoading(long playerUid, int planeId, int floorId, int entryId,
                                      float posX, float posY, float posZ, boolean handshakeOnly,
                                      String transitionType) {
        String ticket = migrationTicketService.issue(playerUid, planeId, floorId, entryId, posX, posY, posZ);
        String type = normalizeTransition(transitionType, handshakeOnly);
        boolean soft = isSoftTransition(type);
        long ttl = (handshakeOnly || soft) ? HANDSHAKE_TTL_MS : DEFAULT_TTL_MS;
        long expireAt = System.currentTimeMillis() + ttl;
        loadingByUid.put(playerUid, new LoadingState(ticket, planeId, floorId, expireAt, handshakeOnly, type));
        return new LoadingTicket(ticket, playerUid, planeId, floorId, expireAt, handshakeOnly, type);
    }

    /** FADE / DISSOLVE / RIFT 均为软过渡（预加载命中）；其余按 BLACK。 */
    static boolean isSoftTransition(String type) {
        if (type == null) {
            return false;
        }
        String t = type.trim().toUpperCase();
        return "FADE".equals(t) || "DISSOLVE".equals(t) || "RIFT".equals(t);
    }

    static String normalizeTransition(String transitionType, boolean handshakeOnly) {
        if (transitionType == null || transitionType.isBlank()) {
            return handshakeOnly ? "DISSOLVE" : "BLACK";
        }
        String t = transitionType.trim().toUpperCase();
        if (isSoftTransition(t)) {
            return t;
        }
        return "BLACK";
    }

    /** 客户端加载完成：核销票据并解除 LOADING。 */
    public boolean completeLoading(long playerUid, String ticket) {
        return completeLoadingResult(playerUid, ticket).ok();
    }

    public CompleteResult completeLoadingResult(long playerUid, String ticket) {
        if (playerUid <= 0 || ticket == null || ticket.isBlank()) {
            return CompleteResult.fail();
        }
        LoadingState state = loadingByUid.get(playerUid);
        if (state == null) {
            boolean ok = migrationTicketService.consume(ticket) != null
                    || peekAndClearIfExpired(playerUid);
            return ok ? CompleteResult.ok(false, "BLACK") : CompleteResult.fail();
        }
        if (!ticket.equals(state.ticket)) {
            return CompleteResult.fail();
        }
        if (System.currentTimeMillis() > state.expireAtMillis) {
            loadingByUid.remove(playerUid);
            return CompleteResult.fail();
        }
        migrationTicketService.consume(ticket);
        loadingByUid.remove(playerUid);
        return CompleteResult.ok(state.handshakeOnly, state.transitionType);
    }

    /** EnterScene 成功后若仍带加载标记，可在场景就绪时清掉（兼容未发 complete 的旧客户端）。 */
    public void markSceneReady(long playerUid) {
        loadingByUid.remove(playerUid);
    }

    public boolean isLoading(long playerUid) {
        LoadingState state = loadingByUid.get(playerUid);
        if (state == null) {
            return false;
        }
        if (System.currentTimeMillis() > state.expireAtMillis) {
            loadingByUid.remove(playerUid);
            return false;
        }
        return true;
    }

    public boolean isHandshakeOnly(long playerUid) {
        LoadingState state = loadingByUid.get(playerUid);
        return state != null && state.handshakeOnly
                && System.currentTimeMillis() <= state.expireAtMillis;
    }

    public void clear(long playerUid) {
        loadingByUid.remove(playerUid);
    }

    private boolean peekAndClearIfExpired(long playerUid) {
        LoadingState state = loadingByUid.get(playerUid);
        if (state != null && System.currentTimeMillis() > state.expireAtMillis) {
            loadingByUid.remove(playerUid);
        }
        return false;
    }

    public record CompleteResult(boolean ok, boolean handshakeOnly, String transitionType) {
        static CompleteResult fail() {
            return new CompleteResult(false, false, "BLACK");
        }

        static CompleteResult ok(boolean handshakeOnly) {
            return ok(handshakeOnly, handshakeOnly ? "FADE" : "BLACK");
        }

        static CompleteResult ok(boolean handshakeOnly, String transitionType) {
            return new CompleteResult(true, handshakeOnly,
                    transitionType == null || transitionType.isBlank() ? "BLACK" : transitionType);
        }
    }

    private record LoadingState(String ticket, int planeId, int floorId, long expireAtMillis,
                                boolean handshakeOnly, String transitionType) {}
}
