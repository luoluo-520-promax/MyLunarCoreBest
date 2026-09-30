package cn.itcast.demo.mylunarcore.security;

import cn.itcast.demo.mylunarcore.tx.DistributedLockService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Redis 侧订单重放防护组件。
 * <p>
 * 作用：
 * 1) 以「业务类型 + 请求流水号（nonce / channelTx）」作为唯一键，通过 Redis 的 SET NX（setIfAbsent）
 *    原子占坑 + TTL 自动过期，实现跨进程（多节点）幂等防重放：第一次提交返回 true 放行业务，
 *    重复提交（同样的 key 已存在）返回 false 直接拒绝。
 * 2) 提供 {@link #withIapGrantLock(String, java.util.function.Supplier)} 在 IAP 订单发货（发奖）路径
 *    外面套一层分布式锁，避免同一个 channelTx（渠道交易号）在并发请求下被重复扣款 / 重复发奖。
 * <p>
 * 本类仅在开启 Redis 时生效（配合 {@code @ConditionalOnProperty} 控制装配）；
 * 当 Redis 未启用或锁服务不可用时，退化为“直接放行 / 直接执行”，保证主流程不被防重放模块阻断。
 */
@Component
@ConditionalOnProperty(prefix = "lunarcore.redis", name = "enabled", havingValue = "true")
public class RedisBizReplayGuard {

    /** Redis 字符串模板，用于执行 SET NX / 分布式锁等 Redis 命令。 */
    private final StringRedisTemplate redis;

    /**
     * 分布式锁服务（抽象接口）。通过 {@link ObjectProvider} 注入：
     * 若容器中存在实现（如 Redisson 或本地实现）则取用，否则为 null，走降级逻辑。
     */
    private final DistributedLockService lockService;

    /**
     * 重放防护 key 的存活时间（秒）。从配置键 {@code mylunarcore.iap.replay-ttl-seconds} 读取，
     * 默认 604800 秒（7 天），保证一次交易在其生命周期内始终受到防重放保护。
     */
    private final long ttlSeconds;

    /**
     * 构造器。
     *
     * @param redis              Redis 客户端模板
     * @param lockServiceProvider 分布式锁服务的可选提供者（可能不存在，因此用 ObjectProvider 延迟解析）
     * @param ttlSeconds         重放 key 的 TTL（秒），配置键 {@code mylunarcore.iap.replay-ttl-seconds}，
     *                           缺省 604800（7 天）
     */
    public RedisBizReplayGuard(StringRedisTemplate redis,
                               ObjectProvider<DistributedLockService> lockServiceProvider,
                               @Value("${mylunarcore.iap.replay-ttl-seconds:604800}") long ttlSeconds) {
        this.redis = redis;
        // 锁服务存在才使用，避免因缺少锁实现导致整个防重放组件装配失败
        this.lockService = lockServiceProvider.getIfAvailable();
        // 强制 TTL 下限为 60 秒，防止配置错误（如配成 0 或负数）导致 key 立即失效
        this.ttlSeconds = Math.max(60L, ttlSeconds);
    }

    /**
     * 尝试为指定业务占用一次防重放凭证。
     *
     * @param bizType      业务类型，用于构造隔离前缀，例如 "iap"、"gacha" 等，避免不同类型互相影响
     * @param nonceOrTxId  请求流水号 / 渠道交易号，作为唯一性判断依据
     * @return true 表示首次占用成功（可继续执行业务）；false 表示 key 已存在，属于重放请求
     */
    public boolean tryAcquire(String bizType, String nonceOrTxId) {
        // 无流水号时不做防重放（兼容旧客户端不携带 nonce 的情况），直接放行
        if (nonceOrTxId == null || nonceOrTxId.isBlank()) {
            return true;
        }
        // 构造 Redis key：lunar:replay:{bizType}:{流水号}，trim 去除两侧空白避免同一笔因空格不同被误判
        String key = "lunar:replay:" + bizType + ":" + nonceOrTxId.trim();
        // SET key 1 NX EX {ttl}：只有 key 不存在时才会写入成功（返回 true），已存在则返回 false（重放）
        Boolean ok = redis.opsForValue().setIfAbsent(key, "1", Duration.ofSeconds(ttlSeconds));
        // 将 Boolean 包装类安全拆箱：必须严格等于 TRUE 才算首次占坑成功
        return Boolean.TRUE.equals(ok);
    }

    /**
     * 在 IAP 发货（发奖）分布式锁内执行给定动作，避免同一个 channelTx 并发双扣/双发。
     *
     * @param channelTx 渠道交易号（如苹果/谷歌的收据交易号），作为锁的互斥维度
     * @param action    锁内执行的业务逻辑（通常是「校验订单 -> 发货 -> 记账」整体）
     * @param <T>       业务逻辑返回类型
     * @return 业务逻辑的执行结果
     */
    public <T> T withIapGrantLock(String channelTx, java.util.function.Supplier<T> action) {
        // 构造互斥锁 key：lunar:lock:iap:{channelTx}；channelTx 为 null 时使用 "unknown" 作为统一占位
        String lockKey = "lunar:lock:iap:" + (channelTx == null ? "unknown" : channelTx.trim());
        // 无锁服务实现（例如本地单机未装配分布式锁）时直接执行，不做并发互斥，属降级策略
        if (lockService == null) {
            return action.get();
        }
        // 等待 3 秒 / 租约 10 秒：3 秒内拿不到锁即抛 LockAcquireException；
        // 10 秒租约防止持有者崩溃后锁被永久占用（Redisson 会自动续期）
        return lockService.withLock(lockKey, 3, 10, TimeUnit.SECONDS, action);
    }
}
