package cn.itcast.demo.mylunarcore.party;

import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * 跨节点组队迁移 Saga：任一节点失败则补偿回滚，并向成员推送进度通知。
 */
@Service
public class PartyMigrateSagaService {

    private static final Logger log = LoggerFactory.getLogger(PartyMigrateSagaService.class);
    private static final String REDIS_KEY_PREFIX = "lunar:party:migrate:";
    private static final Duration TTL = Duration.ofMinutes(5);

    public record MigrateTxn(String txnId, String partyId, long memberUid, String targetNodeId,
                             MigrateState state, String ticket, String compensationHint,
                             long updatedAtMs) {}

    private final Map<String, MigrateTxn> localTxns = new ConcurrentHashMap<>();
    private final GameSessionManager sessionManager;
    private final StringRedisTemplate redis;
    private final Consumer<MigrateTxn> onConfirmed;
    private final Consumer<MigrateTxn> onRollback;
    private final ObjectProvider<MigrateFallbackService> fallbackProvider;

    public PartyMigrateSagaService(ObjectProvider<GameSessionManager> sessionProvider,
                                   ObjectProvider<StringRedisTemplate> redisProvider) {
        this(sessionProvider, redisProvider, null, null, null);
    }

    public PartyMigrateSagaService(ObjectProvider<GameSessionManager> sessionProvider,
                                   ObjectProvider<StringRedisTemplate> redisProvider,
                                   Consumer<MigrateTxn> onConfirmed,
                                   Consumer<MigrateTxn> onRollback) {
        this(sessionProvider, redisProvider, onConfirmed, onRollback, null);
    }

    public PartyMigrateSagaService(ObjectProvider<GameSessionManager> sessionProvider,
                                   ObjectProvider<StringRedisTemplate> redisProvider,
                                   Consumer<MigrateTxn> onConfirmed,
                                   Consumer<MigrateTxn> onRollback,
                                   ObjectProvider<MigrateFallbackService> fallbackProvider) {
        this.sessionManager = sessionProvider == null ? null : sessionProvider.getIfAvailable();
        this.redis = redisProvider == null ? null : redisProvider.getIfAvailable();
        this.onConfirmed = onConfirmed;
        this.onRollback = onRollback;
        this.fallbackProvider = fallbackProvider;
    }

    /** 开启迁移事务，进入 INIT；受 MigrateFallbackService 熔断与超时守护。 */
    public MigrateTxn begin(String partyId, long memberUid, String targetNodeId) {
        MigrateFallbackService fallback = fallback();
        if (fallback != null) {
            MigrateFallbackService.AdmitResult admit = fallback.tryAdmitNewMigrate();
            if (!admit.admitted()) {
                String txnId = "pm-rejected-" + UUID.randomUUID();
                MigrateTxn rejected = new MigrateTxn(txnId, partyId == null ? "" : partyId, memberUid,
                        targetNodeId == null ? "" : targetNodeId,
                        MigrateState.ROLLBACK, "", admit.reason(), System.currentTimeMillis());
                pushProgress(rejected, 0, "rejected:" + admit.reason());
                return rejected;
            }
        }
        String txnId = "pm-" + UUID.randomUUID();
        MigrateTxn txn = new MigrateTxn(txnId, partyId == null ? "" : partyId, memberUid,
                targetNodeId == null ? "" : targetNodeId,
                MigrateState.INIT, "", "", System.currentTimeMillis());
        persist(txn);
        pushProgress(txn, 0, "init");
        if (fallback != null) {
            fallback.armTimeout(txnId, memberUid);
        }
        return txn;
    }

    private MigrateFallbackService fallback() {
        return fallbackProvider == null ? null : fallbackProvider.getIfAvailable();
    }

    public MigrateTxn advance(String txnId, MigrateState next, String ticketOrHint) {
        MigrateTxn cur = get(txnId);
        if (cur == null) {
            return null;
        }
        if (!cur.state().canAdvanceTo(next)) {
            log.warn("party_migrate_illegal_transition txn={} {} -> {}", txnId, cur.state(), next);
            return rollback(txnId, "illegal_transition:" + cur.state() + "->" + next);
        }
        String ticket = cur.ticket();
        String hint = cur.compensationHint();
        if (next == MigrateState.SERIALIZING || next == MigrateState.TRANSFERRING) {
            if (ticketOrHint != null && !ticketOrHint.isBlank()) {
                ticket = ticketOrHint;
            }
        }
        if (next == MigrateState.ROLLBACK && ticketOrHint != null) {
            hint = ticketOrHint;
        }
        MigrateTxn updated = new MigrateTxn(cur.txnId(), cur.partyId(), cur.memberUid(), cur.targetNodeId(),
                next, ticket, hint, System.currentTimeMillis());
        persist(updated);
        int percent = switch (next) {
            case SERIALIZING -> 25;
            case TRANSFERRING -> 67;
            case CONFIRMED -> 100;
            default -> 0;
        };
        pushProgress(updated, percent, next.name().toLowerCase());
        MigrateFallbackService fallback = fallback();
        if (next == MigrateState.CONFIRMED) {
            if (fallback != null) {
                fallback.markSuccess(txnId);
            }
            if (onConfirmed != null) {
                onConfirmed.accept(updated);
            }
        }
        return updated;
    }

    public MigrateTxn rollback(String txnId, String reason) {
        MigrateTxn cur = get(txnId);
        if (cur == null) {
            return null;
        }
        if (cur.state().isTerminal() && cur.state() != MigrateState.ROLLBACK) {
            return cur;
        }
        MigrateTxn rolled = new MigrateTxn(cur.txnId(), cur.partyId(), cur.memberUid(), cur.targetNodeId(),
                MigrateState.ROLLBACK, cur.ticket(),
                reason == null ? "failed" : reason, System.currentTimeMillis());
        persist(rolled);
        pushProgress(rolled, 0, "rollback:" + rolled.compensationHint());
        MigrateFallbackService fallback = fallback();
        if (fallback != null) {
            fallback.markFailure(txnId);
        }
        if (onRollback != null) {
            onRollback.accept(rolled);
        }
        log.warn("party_migrate_rollback txn={} member={} reason={}", txnId, rolled.memberUid(), reason);
        return rolled;
    }

    public MigrateTxn get(String txnId) {
        if (txnId == null || txnId.isBlank()) {
            return null;
        }
        MigrateTxn local = localTxns.get(txnId);
        if (local != null) {
            return local;
        }
        if (redis == null) {
            return null;
        }
        try {
            String raw = redis.opsForValue().get(REDIS_KEY_PREFIX + txnId);
            return decode(raw);
        } catch (Exception e) {
            log.debug("party_migrate redis get failed txn={}", txnId, e);
            return null;
        }
    }

    private void persist(MigrateTxn txn) {
        localTxns.put(txn.txnId(), txn);
        if (redis == null) {
            return;
        }
        try {
            redis.opsForValue().set(REDIS_KEY_PREFIX + txn.txnId(), encode(txn), TTL);
        } catch (Exception e) {
            log.warn("party_migrate redis persist failed txn={}", txn.txnId(), e);
        }
        if (txn.state().isTerminal()) {
            // 终态保留短 TTL 便于对端查询，本地可稍后清理
            localTxns.put(txn.txnId(), txn);
        }
    }

    private void pushProgress(MigrateTxn txn, int percent, String phase) {
        if (sessionManager == null) {
            return;
        }
        int pct = Math.max(0, Math.min(100, percent));
        String uiHint = pct <= 0
                ? ("rollback".equals(phase) || phase.startsWith("rollback") || phase.startsWith("rejected")
                ? "迁移失败，正在回滚" : "正在准备迁移队伍数据")
                : ("正在迁移队伍数据 " + pct + "%");
        // 轻量 JSON 载荷，避免强依赖尚未生成的 Proto 字段
        String json = "{\"txnId\":\"" + txn.txnId() + "\",\"partyId\":\"" + escape(txn.partyId())
                + "\",\"memberUid\":" + txn.memberUid() + ",\"state\":\"" + txn.state().name()
                + "\",\"percent\":" + pct
                + ",\"phase\":\"" + escape(phase) + "\",\"ticket\":\"" + escape(txn.ticket())
                + "\",\"uiHint\":\"" + escape(uiHint) + "\"}";
        GamePacket packet = new GamePacket(CmdIds.PARTY_MIGRATE_PROGRESS_SC_NOTIFY, json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        notifyUid(txn.memberUid(), packet);
        // 队长侧也可感知进度（partyId 本身不存 leader，由调用方在 follow 时额外推送）
    }

    public void notifyLeader(long leaderUid, MigrateTxn txn, int percent, String phase) {
        if (sessionManager == null || leaderUid <= 0) {
            return;
        }
        String json = "{\"txnId\":\"" + txn.txnId() + "\",\"partyId\":\"" + escape(txn.partyId())
                + "\",\"memberUid\":" + txn.memberUid() + ",\"state\":\"" + txn.state().name()
                + "\",\"percent\":" + percent + ",\"phase\":\"" + escape(phase) + "\"}";
        notifyUid(leaderUid, new GamePacket(CmdIds.PARTY_MIGRATE_PROGRESS_SC_NOTIFY,
                json.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private void notifyUid(long uid, GamePacket packet) {
        GameSession s = sessionManager.getOrNull(uid);
        if (s != null) {
            s.send(packet);
        }
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String encode(MigrateTxn t) {
        return t.txnId() + "|" + t.partyId() + "|" + t.memberUid() + "|" + t.targetNodeId()
                + "|" + t.state().name() + "|" + t.ticket() + "|" + t.compensationHint() + "|" + t.updatedAtMs();
    }

    private static MigrateTxn decode(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] p = raw.split("\\|", -1);
        if (p.length < 8) {
            return null;
        }
        try {
            return new MigrateTxn(p[0], p[1], Long.parseLong(p[2]), p[3],
                    MigrateState.valueOf(p[4]), p[5], p[6], Long.parseLong(p[7]));
        } catch (Exception e) {
            return null;
        }
    }
}
