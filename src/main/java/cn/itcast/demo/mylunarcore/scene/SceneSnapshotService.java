package cn.itcast.demo.mylunarcore.scene;

import cn.itcast.demo.mylunarcore.common.AppLogger;
import cn.itcast.demo.mylunarcore.common.LogCategory;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 场景会话快照：切服前序列化，目标节点/重连后恢复坐标与场景键。
 */
@Service
public class SceneSnapshotService {

    private static final Logger log = AppLogger.logger(LogCategory.SYSTEM, SceneSnapshotService.class);
    private static final String KEY_PREFIX = "lunar:scene:snap:";

    private final ObjectMapper objectMapper;
    private final LunarCoreProperties properties;
    private final ObjectProvider<StringRedisTemplate> redisProvider;
    private final ConcurrentHashMap<Long, String> memoryFallback = new ConcurrentHashMap<>();

    public SceneSnapshotService(ObjectMapper objectMapper,
                                LunarCoreProperties properties,
                                ObjectProvider<StringRedisTemplate> redisProvider) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.redisProvider = redisProvider;
    }

    public void save(SceneContext ctx) {
        if (ctx == null) {
            return;
        }
        try {
            SceneSnapshot snap = new SceneSnapshot(
                    ctx.getPlayerUid(),
                    ctx.getPlaneId(),
                    ctx.getFloorId(),
                    ctx.getZoneId(),
                    ctx.getEntryId(),
                    ctx.getPlayerPos().getX(),
                    ctx.getPlayerPos().getY(),
                    ctx.getPlayerPos().getZ(),
                    0,
                    List.of(),
                    System.currentTimeMillis());
            String json = objectMapper.writeValueAsString(snap);
            StringRedisTemplate redis = redisIfEnabled();
            long ttl = Math.max(60L, properties.getRedis().getTicketTtlSeconds());
            if (redis != null) {
                redis.opsForValue().set(KEY_PREFIX + snap.playerUid(), json, Duration.ofSeconds(ttl));
            } else {
                memoryFallback.put(snap.playerUid(), json);
            }
            log.debug("scene_snapshot saved uid={} plane={} floor={}", snap.playerUid(), snap.planeId(), snap.floorId());
        } catch (Exception e) {
            log.warn("scene_snapshot save failed uid={}: {}", ctx.getPlayerUid(), e.toString());
        }
    }

    public Optional<SceneSnapshot> load(long playerUid) {
        try {
            String json = null;
            StringRedisTemplate redis = redisIfEnabled();
            if (redis != null) {
                json = redis.opsForValue().get(KEY_PREFIX + playerUid);
            }
            if (json == null) {
                json = memoryFallback.get(playerUid);
            }
            if (json == null || json.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(objectMapper.readValue(json, SceneSnapshot.class));
        } catch (Exception e) {
            log.warn("scene_snapshot load failed uid={}: {}", playerUid, e.toString());
            return Optional.empty();
        }
    }

    public void remove(long playerUid) {
        StringRedisTemplate redis = redisIfEnabled();
        if (redis != null) {
            redis.delete(KEY_PREFIX + playerUid);
        }
        memoryFallback.remove(playerUid);
    }

    private StringRedisTemplate redisIfEnabled() {
        if (!properties.getRedis().isEnabled()) {
            return null;
        }
        return redisProvider.getIfAvailable();
    }
}
