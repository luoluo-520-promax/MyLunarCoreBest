package cn.itcast.demo.mylunarcore.center;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全局在线表：Redis SET(uid) 或进程内集合。
 */
@Component
public class OnlinePresenceService {

    private static final Logger log = LoggerFactory.getLogger(OnlinePresenceService.class);

    private final PresenceStore store;

    public OnlinePresenceService() {
        this.store = new MemoryPresenceStore();
    }

    @Autowired
    public OnlinePresenceService(ObjectProvider<StringRedisTemplate> redisProvider,
                                 LunarCoreProperties properties) {
        StringRedisTemplate redis = redisProvider.getIfAvailable();
        if (properties.getRedis().isEnabled() && redis != null) {
            this.store = new RedisPresenceStore(redis, properties.getRedis().getOnlineKey());
            log.info("OnlinePresenceService using Redis key={}", properties.getRedis().getOnlineKey());
        } else {
            this.store = new MemoryPresenceStore();
            log.info("OnlinePresenceService using in-memory set");
        }
    }

    public void markOnline(long uid) {
        store.add(uid);
    }

    public void markOffline(long uid) {
        store.remove(uid);
    }

    public boolean isOnline(long uid) {
        return store.contains(uid);
    }

    public long onlineCount() {
        return store.size();
    }

    interface PresenceStore {
        void add(long uid);

        void remove(long uid);

        boolean contains(long uid);

        long size();
    }

    static final class MemoryPresenceStore implements PresenceStore {
        private final Set<Long> online = ConcurrentHashMap.newKeySet();

        @Override
        public void add(long uid) {
            online.add(uid);
        }

        @Override
        public void remove(long uid) {
            online.remove(uid);
        }

        @Override
        public boolean contains(long uid) {
            return online.contains(uid);
        }

        @Override
        public long size() {
            return online.size();
        }
    }

    static final class RedisPresenceStore implements PresenceStore {
        private final StringRedisTemplate redis;
        private final String key;

        RedisPresenceStore(StringRedisTemplate redis, String key) {
            this.redis = redis;
            this.key = (key == null || key.isBlank()) ? "lunar:online:uids" : key;
        }

        @Override
        public void add(long uid) {
            redis.opsForSet().add(key, Long.toString(uid));
        }

        @Override
        public void remove(long uid) {
            redis.opsForSet().remove(key, Long.toString(uid));
        }

        @Override
        public boolean contains(long uid) {
            Boolean member = redis.opsForSet().isMember(key, Long.toString(uid));
            return Boolean.TRUE.equals(member);
        }

        @Override
        public long size() {
            Long size = redis.opsForSet().size(key);
            return size == null ? 0L : size;
        }
    }
}
