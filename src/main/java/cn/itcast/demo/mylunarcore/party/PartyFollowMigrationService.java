package cn.itcast.demo.mylunarcore.party;

import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.protocol.HallSystemProto;
import cn.itcast.demo.mylunarcore.scene.SceneContext;
import cn.itcast.demo.mylunarcore.scene.SceneManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 跨节点组队「跟随迁移」：队长邀请成功后，若队员不在队长权威节点，
 * 经 {@link PartyMigrateSagaService} 事务状态机序列化并下发 {@code PartyFollowMigrateScNotify}，
 * 失败时补偿回滚，避免成员丢失。
 */
@Service
public class PartyFollowMigrationService {

    private static final Logger log = LoggerFactory.getLogger(PartyFollowMigrationService.class);
    private static final String TICKET_REDIS_PREFIX = "lunar:party:pfm:";

    public record EntitySnapshot(long uid, int planeId, int floorId, float x, float y, float z,
                                 String ownerNodeId, String payloadJson) {}

    private final PartyService partyService;
    private final GameSessionManager sessionManager;
    private final SceneManager sceneManager;
    private final PartyMigrateSagaService sagaService;
    private final StringRedisTemplate redis;
    private final Map<String, EntitySnapshot> pendingByTicket = new ConcurrentHashMap<>();

    public PartyFollowMigrationService(PartyService partyService,
                                       ObjectProvider<GameSessionManager> sessionProvider,
                                       ObjectProvider<SceneManager> sceneProvider,
                                       ObjectProvider<PartyMigrateSagaService> sagaProvider,
                                       ObjectProvider<StringRedisTemplate> redisProvider) {
        this.partyService = partyService;
        this.sessionManager = sessionProvider == null ? null : sessionProvider.getIfAvailable();
        this.sceneManager = sceneProvider == null ? null : sceneProvider.getIfAvailable();
        this.sagaService = sagaProvider == null ? null : sagaProvider.getIfAvailable();
        this.redis = redisProvider == null ? null : redisProvider.getIfAvailable();
    }

    /** 兼容旧构造（单测）。 */
    public PartyFollowMigrationService(PartyService partyService,
                                       ObjectProvider<GameSessionManager> sessionProvider,
                                       ObjectProvider<SceneManager> sceneProvider) {
        this(partyService, sessionProvider, sceneProvider, null, null);
    }

    /**
     * 邀请成功后调用：若队员会话不在本节点，走 Saga 序列化并推送跟随迁移通知。
     *
     * @return true=已发起跟随迁移或本节点无需迁移
     */
    public boolean followAfterInvite(PartyService.Party party, long memberUid) {
        if (party == null || memberUid <= 0) {
            return false;
        }
        if (sessionManager != null) {
            GameSession local = sessionManager.getOrNull(memberUid);
            if (local != null && local.getChannel() != null && local.getChannel().isActive()) {
                return true;
            }
        }
        PartyMigrateSagaService.MigrateTxn txn = null;
        try {
            if (sagaService != null) {
                txn = sagaService.begin(party.partyId(), memberUid, party.ownerNodeId());
                txn = sagaService.advance(txn.txnId(), MigrateState.SERIALIZING, null);
                sagaService.notifyLeader(party.leaderUid(), txn, 25, "serializing");
            }
            EntitySnapshot snap = capture(memberUid, party.ownerNodeId());
            String ticket = "pfm-" + UUID.randomUUID();
            pendingByTicket.put(ticket, snap);
            storeTicketRedis(ticket, snap);
            if (sagaService != null && txn != null) {
                txn = sagaService.advance(txn.txnId(), MigrateState.TRANSFERRING, ticket);
                sagaService.notifyLeader(party.leaderUid(), txn, 60, "transferring");
            }
            HallSystemProto.PartyFollowMigrateScNotify notify =
                    HallSystemProto.PartyFollowMigrateScNotify.newBuilder()
                            .setPartyId(party.partyId())
                            .setLeaderUid(party.leaderUid())
                            .setMemberUid(memberUid)
                            .setTargetNodeId(party.ownerNodeId() == null ? "" : party.ownerNodeId())
                            .setTargetPlaneId(snap.planeId())
                            .setTargetFloorId(snap.floorId())
                            .setMigrateTicket(ticket)
                            .setReason("follow_invite")
                            .build();
            GamePacket packet = new GamePacket(CmdIds.PARTY_FOLLOW_MIGRATE_SC_NOTIFY, notify.toByteArray());
            if (sessionManager != null) {
                GameSession leader = sessionManager.getOrNull(party.leaderUid());
                if (leader != null) {
                    leader.send(packet);
                }
                GameSession member = sessionManager.getOrNull(memberUid);
                if (member != null) {
                    member.send(packet);
                }
            }
            if (sagaService != null && txn != null) {
                sagaService.advance(txn.txnId(), MigrateState.CONFIRMED, ticket);
                sagaService.notifyLeader(party.leaderUid(), txn, 100, "confirmed");
            }
            log.info("party_follow_migrate party={} member={} targetNode={} ticket={} txn={}",
                    party.partyId(), memberUid, party.ownerNodeId(), ticket,
                    txn == null ? "" : txn.txnId());
            return true;
        } catch (Exception e) {
            if (sagaService != null && txn != null) {
                sagaService.rollback(txn.txnId(), "exception:" + e.getClass().getSimpleName());
                sagaService.notifyLeader(party.leaderUid(), txn, 0, "rollback");
            }
            log.error("party_follow_migrate_failed party={} member={}", party.partyId(), memberUid, e);
            return false;
        }
    }

    public EntitySnapshot consumeTicket(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return null;
        }
        EntitySnapshot local = pendingByTicket.remove(ticket);
        if (local != null) {
            deleteTicketRedis(ticket);
            return local;
        }
        return loadAndDeleteTicketRedis(ticket);
    }

    private void storeTicketRedis(String ticket, EntitySnapshot snap) {
        if (redis == null) {
            return;
        }
        try {
            redis.opsForValue().set(TICKET_REDIS_PREFIX + ticket,
                    snap.uid() + "|" + snap.planeId() + "|" + snap.floorId() + "|"
                            + snap.x() + "|" + snap.y() + "|" + snap.z() + "|"
                            + (snap.ownerNodeId() == null ? "" : snap.ownerNodeId()),
                    Duration.ofMinutes(5));
        } catch (Exception e) {
            log.warn("party_pfm ticket redis store failed ticket={}", ticket, e);
        }
    }

    private EntitySnapshot loadAndDeleteTicketRedis(String ticket) {
        if (redis == null) {
            return null;
        }
        try {
            String key = TICKET_REDIS_PREFIX + ticket;
            String raw = redis.opsForValue().get(key);
            redis.delete(key);
            if (raw == null || raw.isBlank()) {
                return null;
            }
            String[] p = raw.split("\\|", -1);
            if (p.length < 7) {
                return null;
            }
            long uid = Long.parseLong(p[0]);
            int plane = Integer.parseInt(p[1]);
            int floor = Integer.parseInt(p[2]);
            float x = Float.parseFloat(p[3]);
            float y = Float.parseFloat(p[4]);
            float z = Float.parseFloat(p[5]);
            String node = p[6];
            String json = "{\"uid\":" + uid + ",\"plane\":" + plane + ",\"floor\":" + floor
                    + ",\"x\":" + x + ",\"y\":" + y + ",\"z\":" + z + "}";
            return new EntitySnapshot(uid, plane, floor, x, y, z, node, json);
        } catch (Exception e) {
            log.warn("party_pfm ticket redis load failed ticket={}", ticket, e);
            return null;
        }
    }

    private void deleteTicketRedis(String ticket) {
        if (redis == null) {
            return;
        }
        try {
            redis.delete(TICKET_REDIS_PREFIX + ticket);
        } catch (Exception ignored) {
            // ignore
        }
    }

    private EntitySnapshot capture(long uid, String ownerNodeId) {
        int plane = 0;
        int floor = 0;
        float x = 0;
        float y = 0;
        float z = 0;
        if (sceneManager != null) {
            SceneContext ctx = sceneManager.getByPlayerUid(uid);
            if (ctx != null && ctx.getPlayerPos() != null) {
                plane = ctx.getPlaneId();
                floor = ctx.getFloorId();
                x = ctx.getPlayerPos().getX();
                y = ctx.getPlayerPos().getY();
                z = ctx.getPlayerPos().getZ();
            }
        }
        String json = "{\"uid\":" + uid + ",\"plane\":" + plane + ",\"floor\":" + floor
                + ",\"x\":" + x + ",\"y\":" + y + ",\"z\":" + z + "}";
        return new EntitySnapshot(uid, plane, floor, x, y, z,
                ownerNodeId == null ? partyService.localNodeId() : ownerNodeId, json);
    }
}
