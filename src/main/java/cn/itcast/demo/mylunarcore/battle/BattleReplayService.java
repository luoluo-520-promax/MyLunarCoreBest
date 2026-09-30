package cn.itcast.demo.mylunarcore.battle;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 战斗行动回放：记录 ActionTick 序列；重连时打包掉线期间行动供客户端快进。
 */
@Service
public class BattleReplayService {

    private static final Logger log = LoggerFactory.getLogger(BattleReplayService.class);
    private static final String KEY_PREFIX = "lunar:battle:replay:";

    public record ActionTick(long seq, int actorPlayerId, String actionType, String payloadJson, long serverTimeMs) {}

    public record ReplayPacket(long battleId, long fromSeq, long toSeq, List<ActionTick> actions,
                               BattleSnapshot latestSnapshot) {}

    private final ObjectMapper objectMapper;
    private final BattleSnapshotService snapshotService;
    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final ConcurrentHashMap<Long, CopyOnWriteArrayList<ActionTick>> memory = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, ConcurrentHashMap<Integer, Long>> lastSeenSeq = new ConcurrentHashMap<>();

    public BattleReplayService(ObjectMapper objectMapper,
                               BattleSnapshotService snapshotService,
                               ObjectProvider<StringRedisTemplate> redisProvider) {
        this.objectMapper = objectMapper;
        this.snapshotService = snapshotService;
        this.redisProvider = redisProvider;
    }

    public void append(long battleId, int actorPlayerId, String actionType, Object payload) {
        if (battleId <= 0) {
            return;
        }
        CopyOnWriteArrayList<ActionTick> list = memory.computeIfAbsent(battleId, k -> new CopyOnWriteArrayList<>());
        long seq = list.size() + 1L;
        String json;
        try {
            json = payload == null ? "{}" : objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            json = "{}";
        }
        ActionTick tick = new ActionTick(seq, actorPlayerId, actionType == null ? "" : actionType,
                json, System.currentTimeMillis());
        list.add(tick);
        persistTail(battleId, list);
    }

    /** 标记玩家当前已看到的序列（断线前）。 */
    public void markSeen(long battleId, int playerId, long seq) {
        lastSeenSeq.computeIfAbsent(battleId, k -> new ConcurrentHashMap<>()).put(playerId, seq);
    }

    public Optional<ReplayPacket> buildReconnectPacket(long battleId, int playerId) {
        List<ActionTick> all = loadActions(battleId);
        if (all.isEmpty()) {
            return Optional.empty();
        }
        ConcurrentHashMap<Integer, Long> seen = lastSeenSeq.get(battleId);
        long from = seen == null ? 0L : seen.getOrDefault(playerId, 0L);
        List<ActionTick> missed = new ArrayList<>();
        for (ActionTick t : all) {
            if (t.seq() > from) {
                missed.add(t);
            }
        }
        BattleSnapshot snap = snapshotService.load(battleId).orElse(null);
        long to = all.get(all.size() - 1).seq();
        markSeen(battleId, playerId, to);
        return Optional.of(new ReplayPacket(battleId, from, to, missed, snap));
    }

    public void clear(long battleId) {
        memory.remove(battleId);
        lastSeenSeq.remove(battleId);
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis != null) {
            try {
                redis.delete(KEY_PREFIX + battleId);
            } catch (Exception ignored) {
            }
        }
    }

    private void persistTail(long battleId, List<ActionTick> list) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return;
        }
        try {
            int from = Math.max(0, list.size() - 200);
            List<ActionTick> tail = list.subList(from, list.size());
            redis.opsForValue().set(KEY_PREFIX + battleId, objectMapper.writeValueAsString(tail),
                    Duration.ofHours(2));
        } catch (Exception e) {
            log.debug("replay persist failed: {}", e.getMessage());
        }
    }

    private List<ActionTick> loadActions(long battleId) {
        CopyOnWriteArrayList<ActionTick> local = memory.get(battleId);
        if (local != null && !local.isEmpty()) {
            return List.copyOf(local);
        }
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (redis == null) {
            return List.of();
        }
        try {
            String json = redis.opsForValue().get(KEY_PREFIX + battleId);
            if (json == null || json.isBlank()) {
                return List.of();
            }
            ActionTick[] arr = objectMapper.readValue(json, ActionTick[].class);
            return arr == null ? List.of() : List.of(arr);
        } catch (Exception e) {
            return List.of();
        }
    }
}
