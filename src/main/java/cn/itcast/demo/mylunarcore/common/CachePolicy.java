package cn.itcast.demo.mylunarcore.common;

/**
 * 本地缓存策略约定（Caffeine）。
 * <ul>
 *   <li><b>可缓存</b>：静态配置（Banner/Shop/GuidePack/CoachTips）、Assist 问答短 TTL</li>
 *   <li><b>可缓存但必须失效</b>：PlayerData（断线/写库后 invalidate）；TTL 建议加随机抖动防雪崩</li>
 *   <li><b>禁止缓存</b>：钱包余额、库存实时扣减、IAP 订单状态、公会战进行中比分</li>
 * </ul>
 * 配置热更后应调用 {@link ConfigCacheVersion#bump()}；灰度配置请统一经 {@link ConfigGrayReader}/{@link UnifiedConfigAccessor}。
 */
public final class CachePolicy {

    private CachePolicy() {
    }

    public static final String WALLET_MUST_READ_DB = "wallet";
    public static final String CONFIG_MAY_CACHE = "static-config";
    public static final String PLAYER_CACHE_WITH_INVALIDATE = "player-data";

    /** TTL 抖动：在 baseMs 上叠加 [0, jitterMs) 随机，降低同时过期击穿。 */
    public static long jitteredTtlMs(long baseMs, long jitterMs) {
        long base = Math.max(0L, baseMs);
        long jitter = Math.max(0L, jitterMs);
        if (jitter == 0L) {
            return base;
        }
        return base + (long) (Math.random() * jitter);
    }
}
