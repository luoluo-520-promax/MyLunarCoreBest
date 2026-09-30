package cn.itcast.demo.mylunarcore.center;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 跨节点迁移会话票据：源节点签发，目标节点消费后恢复场景。
 * <p>
 * Redis 启用时使用 {@code SET key payload PX ttl} + {@code GETDEL} 核销，可跨 JVM；
 * 否则回退进程内 ConcurrentHashMap（仅单机有效）。
 */
@Component
public class MigrationTicketService {

    private static final Logger log = LoggerFactory.getLogger(MigrationTicketService.class);

    public record TicketPayload(long playerUid, int planeId, int floorId, int entryId,
                                float posX, float posY, float posZ, long expireAtMillis) {
    }

    private final TicketStore store;

    /** 单测 / 无 Spring 场景：纯内存。 */
    public MigrationTicketService() {
        this.store = new MemoryTicketStore(120_000L);
    }

    @Autowired
    public MigrationTicketService(ObjectProvider<StringRedisTemplate> redisProvider,
                                  LunarCoreProperties properties) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        LunarCoreProperties.RedisProperties redisProps = properties.getRedis();
        long ttlMs = Math.max(5_000L, redisProps.getTicketTtlSeconds() * 1000L);
        if (redisProps.isEnabled() && redis != null) {
            this.store = new RedisTicketStore(redis, redisProps.getTicketKeyPrefix(), ttlMs);
            log.info("MigrationTicketService using Redis backend, ttlMs={}", ttlMs);
        } else {
            this.store = new MemoryTicketStore(ttlMs);
            log.info("MigrationTicketService using in-memory backend, ttlMs={}", ttlMs);
        }
    }

    public String issue(long playerUid, int planeId, int floorId, int entryId,
                        float posX, float posY, float posZ) {
        String ticket = UUID.randomUUID().toString().replace("-", "");
        long expireAt = System.currentTimeMillis() + store.ttlMs();
        TicketPayload payload = new TicketPayload(playerUid, planeId, floorId, entryId,
                posX, posY, posZ, expireAt);
        store.put(ticket, payload);
        return ticket;
    }

    public TicketPayload consume(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return null;
        }
        TicketPayload payload = store.remove(ticket);
        if (payload == null) {
            return null;
        }
        if (System.currentTimeMillis() > payload.expireAtMillis()) {
            return null;
        }
        return payload;
    }

    interface TicketStore {
        long ttlMs();

        void put(String ticket, TicketPayload payload);

        TicketPayload remove(String ticket);
    }

    static final class MemoryTicketStore implements TicketStore {
        private final Map<String, TicketPayload> tickets = new ConcurrentHashMap<>();
        private final long ttlMs;

        MemoryTicketStore(long ttlMs) {
            this.ttlMs = ttlMs;
        }

        @Override
        public long ttlMs() {
            return ttlMs;
        }

        @Override
        public void put(String ticket, TicketPayload payload) {
            tickets.put(ticket, payload);
        }

        @Override
        public TicketPayload remove(String ticket) {
            return tickets.remove(ticket);
        }
    }

    static final class RedisTicketStore implements TicketStore {
        private final StringRedisTemplate redis;
        private final String keyPrefix;
        private final long ttlMs;

        RedisTicketStore(StringRedisTemplate redis, String keyPrefix, long ttlMs) {
            this.redis = redis;
            this.keyPrefix = keyPrefix == null || keyPrefix.isBlank() ? "lunar:mig:ticket:" : keyPrefix;
            this.ttlMs = ttlMs;
        }

        @Override
        public long ttlMs() {
            return ttlMs;
        }

        @Override
        public void put(String ticket, TicketPayload payload) {
            String key = keyPrefix + ticket;
            redis.opsForValue().set(key, encode(payload), Duration.ofMillis(ttlMs));
        }

        @Override
        public TicketPayload remove(String ticket) {
            String key = keyPrefix + ticket;
            String raw = redis.opsForValue().getAndDelete(key);
            if (raw == null) {
                // 兼容旧 Redis：无 GETDEL 时回退 GET+DEL
                raw = redis.opsForValue().get(key);
                if (raw != null) {
                    redis.delete(key);
                }
            }
            return raw == null ? null : decode(raw);
        }

        static String encode(TicketPayload p) {
            return p.playerUid() + "|" + p.planeId() + "|" + p.floorId() + "|" + p.entryId() + "|"
                    + p.posX() + "|" + p.posY() + "|" + p.posZ() + "|" + p.expireAtMillis();
        }

        static TicketPayload decode(String raw) {
            String[] parts = raw.split("\\|", -1);
            if (parts.length < 8) {
                return null;
            }
            try {
                return new TicketPayload(
                        Long.parseLong(parts[0]),
                        Integer.parseInt(parts[1]),
                        Integer.parseInt(parts[2]),
                        Integer.parseInt(parts[3]),
                        Float.parseFloat(parts[4]),
                        Float.parseFloat(parts[5]),
                        Float.parseFloat(parts[6]),
                        Long.parseLong(parts[7]));
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }
}
