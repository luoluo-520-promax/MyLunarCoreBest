package cn.itcast.demo.mylunarcore.battle;

import cn.itcast.demo.mylunarcore.common.AppLogger;
import cn.itcast.demo.mylunarcore.common.LogCategory;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.protocol.BattleSystemProto;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * 战斗快照服务：最终态 + 最近 N 条 {@link BattleDeltaRecord}，
 * 断线重连时下发 BattleReplayDeltaScNotify 供客户端快速回放。
 */
@Service
public class BattleSnapshotService {

    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_BATTLE, BattleSnapshotService.class);
    private static final String KEY_PREFIX = "lunar:battle:snap:";

    private final ObjectMapper objectMapper;
    private final LunarCoreProperties properties;
    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final ConcurrentHashMap<Long, String> memoryFallback = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Deque<BattleDeltaRecord>> deltaRings = new ConcurrentHashMap<>();
    private final AtomicLong actionIdGen = new AtomicLong(1);
    private final BlockingQueue<BattleContext> saveQueue = new ArrayBlockingQueue<>(2048);
    private final AtomicBoolean asyncRunning = new AtomicBoolean(false);
    private final LongAdder asyncDropped = new LongAdder();
    private Thread asyncWorker;

    public BattleSnapshotService(ObjectMapper objectMapper,
                                 LunarCoreProperties properties,
                                 ObjectProvider<StringRedisTemplate> redisProvider) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.redisProvider = redisProvider;
    }

    @PostConstruct
    public void startAsyncWorker() {
        if (!asyncRunning.compareAndSet(false, true)) {
            return;
        }
        asyncWorker = new Thread(this::drainSaveLoop, "battle-snapshot-flush");
        asyncWorker.setDaemon(true);
        asyncWorker.start();
    }

    @PreDestroy
    public void stopAsyncWorker() {
        asyncRunning.set(false);
        if (asyncWorker != null) {
            asyncWorker.interrupt();
        }
        BattleContext leftover;
        while ((leftover = saveQueue.poll()) != null) {
            save(leftover);
        }
    }

    /** 热路径：仅入队，后台批量落盘；worker 未启动时同步兜底（单测/早期启动）。 */
    public void saveAsync(BattleContext context) {
        if (!isEnabled() || context == null) {
            return;
        }
        if (!asyncRunning.get()) {
            save(context);
            return;
        }
        if (!saveQueue.offer(context)) {
            asyncDropped.increment();
            // 队列满时同步兜底，避免丢关键快照
            save(context);
        }
    }

    private void drainSaveLoop() {
        while (asyncRunning.get() || !saveQueue.isEmpty()) {
            try {
                BattleContext first = saveQueue.poll(200, TimeUnit.MILLISECONDS);
                if (first != null) {
                    save(first);
                    List<BattleContext> batch = new ArrayList<>(32);
                    saveQueue.drainTo(batch, 31);
                    // 同 battleId 只保留最后一次
                    Map<Long, BattleContext> dedup = new HashMap<>();
                    for (BattleContext c : batch) {
                        if (c != null) {
                            dedup.put(c.getBattleId(), c);
                        }
                    }
                    for (BattleContext c : dedup.values()) {
                        save(c);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("battle_snapshot async flush error: {}", e.toString());
            }
        }
    }

    public long asyncDroppedCount() {
        return asyncDropped.sum();
    }

    public boolean isEnabled() {
        return properties.getBattleSnapshot().isEnabled();
    }

    private int ringSize() {
        return Math.max(3, properties.getBattleSnapshot().getDeltaRingSize());
    }

    /** 记录一次行动增量，供重连回放。 */
    public BattleDeltaRecord recordDelta(long battleId, int casterId, int skillId, int targetId,
                                         int damage, boolean critical, boolean kill, int hpAfter) {
        BattleDeltaRecord rec = new BattleDeltaRecord(
                actionIdGen.getAndIncrement(),
                System.currentTimeMillis(),
                casterId, skillId, targetId, damage, critical, kill, hpAfter);
        deltaRings.compute(battleId, (id, deque) -> {
            Deque<BattleDeltaRecord> ring = deque == null ? new ArrayDeque<>(ringSize() + 1) : deque;
            ring.addLast(rec);
            while (ring.size() > ringSize()) {
                ring.removeFirst();
            }
            return ring;
        });
        return rec;
    }

    public List<BattleDeltaRecord> recentDeltas(long battleId) {
        Deque<BattleDeltaRecord> ring = deltaRings.get(battleId);
        if (ring == null || ring.isEmpty()) {
            return List.of();
        }
        return List.copyOf(ring);
    }

    /** 断线重连：推送最近增量 + 最终快照。 */
    public void pushReplayOnReconnect(long battleId, BattleContext context, Channel channel) {
        if (channel == null || !channel.isActive() || context == null) {
            return;
        }
        BattleSystemProto.BattleReplayDeltaScNotify.Builder b =
                BattleSystemProto.BattleReplayDeltaScNotify.newBuilder()
                        .setBattleId(battleId)
                        .setReconnectAtMs(System.currentTimeMillis());
        for (BattleDeltaRecord d : recentDeltas(battleId)) {
            b.addDeltas(BattleSystemProto.BattleDeltaRecord.newBuilder()
                    .setActionId(d.actionId())
                    .setTimestampMs(d.timestampMs())
                    .setCasterId(d.casterId())
                    .setSkillId(d.skillId())
                    .setTargetId(d.targetId())
                    .setDamage(d.damage())
                    .setCritical(d.critical())
                    .setKill(d.kill())
                    .setHpAfter(d.hpAfter())
                    .build());
        }
        BattleSystemProto.CurrentState.Builder state = BattleSystemProto.CurrentState.newBuilder()
                .setTurn(context.getTurn())
                .setCurrentWave(context.getCurrentWave());
        for (EntityState e : context.getEntities().values()) {
            state.addEntities(BattleSystemProto.EntityState.newBuilder()
                    .setId(e.getId())
                    .setHp(e.getHp())
                    .setDead(e.isDead())
                    .addAllBuffs(e.getBuffStacks().keySet())
                    .build());
        }
        b.setFinalState(state.build());
        channel.writeAndFlush(new GamePacket(CmdIds.BATTLE_REPLAY_DELTA_SC_NOTIFY, b.build().toByteArray()));
    }

    public void save(BattleContext context) {
        if (!isEnabled() || context == null) {
            return;
        }
        try {
            BattleSnapshot snap = fromContext(context);
            String json = objectMapper.writeValueAsString(snap);
            StringRedisTemplate redis = redisIfEnabled();
            if (redis != null) {
                long ttl = Math.max(60L, properties.getBattleSnapshot().getTtlSeconds());
                redis.opsForValue().set(KEY_PREFIX + snap.battleId(), json, Duration.ofSeconds(ttl));
                for (Integer pid : snap.participantPlayerIds()) {
                    redis.opsForValue().set(KEY_PREFIX + "player:" + pid, String.valueOf(snap.battleId()),
                            Duration.ofSeconds(ttl));
                }
            } else {
                memoryFallback.put(snap.battleId(), json);
            }
            log.debug("battle_snapshot saved battleId={} turn={} wave={}", snap.battleId(), snap.turn(), snap.currentWave());
        } catch (Exception e) {
            log.warn("battle_snapshot save failed battleId={}: {}", context.getBattleId(), e.toString());
        }
    }

    public Optional<BattleSnapshot> load(long battleId) {
        if (!isEnabled() || battleId <= 0) {
            return Optional.empty();
        }
        try {
            String json = null;
            StringRedisTemplate redis = redisIfEnabled();
            if (redis != null) {
                json = redis.opsForValue().get(KEY_PREFIX + battleId);
            }
            if (json == null) {
                json = memoryFallback.get(battleId);
            }
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, BattleSnapshot.class));
        } catch (Exception e) {
            log.warn("battle_snapshot load failed battleId={}: {}", battleId, e.toString());
            return Optional.empty();
        }
    }

    public Optional<BattleSnapshot> loadByPlayer(int playerId) {
        if (!isEnabled() || playerId <= 0) {
            return Optional.empty();
        }
        StringRedisTemplate redis = redisIfEnabled();
        if (redis != null) {
            String battleIdStr = redis.opsForValue().get(KEY_PREFIX + "player:" + playerId);
            if (battleIdStr != null) {
                try {
                    return load(Long.parseLong(battleIdStr));
                } catch (NumberFormatException ignored) {
                    return Optional.empty();
                }
            }
        }
        for (Map.Entry<Long, String> e : memoryFallback.entrySet()) {
            try {
                BattleSnapshot snap = objectMapper.readValue(e.getValue(), BattleSnapshot.class);
                if (snap.participantPlayerIds() != null && snap.participantPlayerIds().contains(playerId)) {
                    return Optional.of(snap);
                }
            } catch (Exception ignored) {
                // continue
            }
        }
        return Optional.empty();
    }

    public void remove(long battleId) {
        StringRedisTemplate redis = redisIfEnabled();
        if (redis != null) {
            redis.delete(KEY_PREFIX + battleId);
        }
        memoryFallback.remove(battleId);
        deltaRings.remove(battleId);
    }

    public BattleSnapshot fromContext(BattleContext ctx) {
        Map<Integer, BattleSnapshot.EntitySnap> entities = new HashMap<>();
        for (Map.Entry<Integer, EntityState> e : ctx.getEntities().entrySet()) {
            EntityState s = e.getValue();
            entities.put(e.getKey(), new BattleSnapshot.EntitySnap(
                    s.getId(), s.getHp(), s.isDead(), s.getToughness(), s.isBroken(), s.getSkinId()));
        }
        List<Integer> participants = new ArrayList<>(ctx.getParticipantPlayerIds());
        return new BattleSnapshot(
                ctx.getBattleId(),
                ctx.getPlayerId(),
                participants,
                ctx.getLineupId(),
                ctx.getBattleStageId(),
                ctx.getStartTimeSeconds(),
                ctx.getTurn(),
                ctx.getCurrentWave(),
                ctx.getWaveCount(),
                ctx.isEnded(),
                entities,
                System.currentTimeMillis());
    }

    /**
     * 将快照还原为可继续推进的 {@link BattleContext}（断线重连 / 跨节点迁移 hydrate）。
     */
    public Optional<BattleContext> hydrate(long battleId) {
        return load(battleId).map(BattleContext::fromSnapshot);
    }

    public Optional<BattleContext> hydrateByPlayer(int playerId) {
        return loadByPlayer(playerId).map(BattleContext::fromSnapshot);
    }

    /**
     * 迁移票据载荷：序列化快照 JSON，供目标节点 {@link #importMigrationPayload} 导入。
     */
    public Optional<String> exportMigrationPayload(long battleId) {
        return load(battleId).map(snap -> {
            try {
                return objectMapper.writeValueAsString(snap);
            } catch (Exception e) {
                return null;
            }
        }).filter(s -> s != null && !s.isBlank());
    }

    public Optional<BattleContext> importMigrationPayload(String json) {
        if (json == null || json.isBlank()) {
            return Optional.empty();
        }
        try {
            BattleSnapshot snap = objectMapper.readValue(json, BattleSnapshot.class);
            saveSnapshot(snap);
            return Optional.ofNullable(BattleContext.fromSnapshot(snap));
        } catch (Exception e) {
            log.warn("battle migration import failed: {}", e.toString());
            return Optional.empty();
        }
    }

    private void saveSnapshot(BattleSnapshot snap) {
        try {
            String json = objectMapper.writeValueAsString(snap);
            StringRedisTemplate redis = redisIfEnabled();
            if (redis != null) {
                long ttl = Math.max(60L, properties.getBattleSnapshot().getTtlSeconds());
                redis.opsForValue().set(KEY_PREFIX + snap.battleId(), json, Duration.ofSeconds(ttl));
                for (Integer pid : snap.participantPlayerIds()) {
                    redis.opsForValue().set(KEY_PREFIX + "player:" + pid, String.valueOf(snap.battleId()),
                            Duration.ofSeconds(ttl));
                }
            } else {
                memoryFallback.put(snap.battleId(), json);
            }
        } catch (Exception e) {
            log.warn("battle_snapshot re-save failed: {}", e.toString());
        }
    }

    private StringRedisTemplate redisIfEnabled() {
        if (!properties.getRedis().isEnabled()) {
            return null;
        }
        return redisProvider.getIfAvailable();
    }
}
