// 在线玩家 PlayerData 的进程内 Caffeine 缓存
package cn.itcast.demo.mylunarcore.player;

import cn.itcast.demo.mylunarcore.common.CachePolicy;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.model.PlayerData;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * 玩家聚合数据（{@link PlayerData}）的本地热缓存。
 * <p>
 * 主要服务于 {@link PlayerDataAsyncLoadService} 的 TIMER 定时全量同步：
 * 命中缓存时可跳过数据库查询，降低 Tick 周期内对 MySQL 的压力。
 * 玩家离线时由 {@link ConnectionLifecycleService} 调用 {@link #invalidate(long)} 清除条目。
 */
@Component
public class PlayerDataCacheService {

    /** 全局配置，读取 sync.cacheEnabled、sync.cacheTtlMs 等开关。 */
    private final LunarCoreProperties properties;

    /** Caffeine 缓存实例，key 为 uid，value 为 PlayerData 深拷贝快照。 */
    private Cache<Long, PlayerData> cache;

    /**
     * 构造器注入 LunarCoreProperties。
     */
    public PlayerDataCacheService(LunarCoreProperties properties) {
        this.properties = properties;
    }

    /**
     * Bean 初始化后构建 Caffeine 缓存：最大 10000 条目，可选写后 TTL 过期。
     */
    @PostConstruct
    void init() {
        long ttlMs = Math.max(0L, properties.getSync().getCacheTtlMs());
        // 抖动 0–30s，降低大量玩家同时过期导致的缓存雪崩
        long jittered = CachePolicy.jitteredTtlMs(ttlMs, ttlMs > 0 ? 30_000L : 0L);
        Caffeine<Object, Object> builder = Caffeine.newBuilder().maximumSize(10_000);
        if (jittered > 0) {
            builder.expireAfterWrite(Duration.ofMillis(jittered));
        }
        this.cache = builder.build();
    }

    /**
     * 查询 uid 对应的缓存条目（不触发加载）。
     * 缓存全局关闭（cacheEnabled=false）时始终返回 empty。
     *
     * @param uid 玩家 uid
     * @return 缓存中的 PlayerData；未命中或缓存关闭为 empty
     */
    public Optional<PlayerData> get(long uid) {
        if (!properties.getSync().isCacheEnabled()) {
            return Optional.empty();
        }
        return Optional.ofNullable(cache.getIfPresent(uid));
    }

    /**
     * 将 PlayerData 写入缓存。
     * 缓存关闭或 data 为 null 时静默跳过。
     *
     * @param uid  玩家 uid
     * @param data 待缓存的聚合数据（调用方通常传入 deepCopy 避免外部修改污染缓存）
     */
    public void put(long uid, PlayerData data) {
        if (!properties.getSync().isCacheEnabled() || data == null) {
            return;
        }
        cache.put(uid, data);
    }

    /**
     * 使指定玩家的缓存条目失效。
     * 玩家登出/断连时调用，防止离线后仍命中过期数据。
     *
     * @param uid 玩家 uid
     */
    public void invalidate(long uid) {
        cache.invalidate(uid);
    }

    /**
     * 清空全部缓存条目。
     * 可用于热更配置或运维手动刷新场景。
     */
    public void invalidateAll() {
        cache.invalidateAll();
    }
}
