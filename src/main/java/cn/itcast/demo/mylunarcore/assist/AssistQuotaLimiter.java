package cn.itcast.demo.mylunarcore.assist;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 按玩家维度维护助手额度：coach、llm、daily 三个 Bucket4j 令牌桶。
 * <p>
 * coach 只扣分钟桶；LLM 先扣日预算再扣分钟桶。拒绝时返回估算重试秒数（1~3600）。
 */
@Component
public class AssistQuotaLimiter {

    /** 额度种类：COACH 便宜路径；LLM 贵路径（叠加 daily）。 */
    public enum QuotaKind {
        COACH, // 枚举常量 COACH，供额度/分级判断使用
        LLM // 枚举常量 LLM，供额度/分级判断使用
    }
    /**
     * 扣费结果。
     */
    public record AcquireResult(boolean allowed, int retryAfterSeconds) {
        /**
         * 放行：allowed=true，无需等待。
         */
        public static AcquireResult ok() {
            return new AcquireResult(true, 0); // 成功放行，retryAfter 置 0
        }
        /**
         * 拒绝：retryAfter 至少为 1 秒，避免客户端立即空转重试。
         */
        public static AcquireResult rejected(int retryAfterSeconds) {
            return new AcquireResult(false, Math.max(1, retryAfterSeconds)); // 钳制最小等待 1 秒
        }
    }
    /**
     * AiAssist 限额配置来源
     */
    private final LunarCoreProperties properties;
    /**
     * uid -> 该玩家的 coach/llm/daily 三桶。
     */
    private final ConcurrentHashMap<Long, PlayerBuckets> uidBuckets = new ConcurrentHashMap<>();
    /**
     * 累计拒绝次数，供监控
     */
    private final AtomicLong rejectCount = new AtomicLong();
    /**
     * 上次空闲回收时间
     */
    private volatile long lastEvictAtMs = System.currentTimeMillis();
    /**
     * AssistQuotaLimiter 构造方法
     */
    public AssistQuotaLimiter(LunarCoreProperties properties) {
        this.properties = properties; // 保存配置引用，建桶与回收时读取
    }
    /**
     * 兼容旧接口：默认按 LLM 贵路径扣费。
     */
    public boolean tryAcquire(long uid) {
        return tryAcquire(uid, QuotaKind.LLM).allowed(); // 只返回是否放行布尔值
    }
    /**
     * 教练提示专用：只扣 coach 分钟桶。
     */
    public boolean tryAcquireCoach(long uid) {
        return tryAcquire(uid, QuotaKind.COACH).allowed(); // 不占用 LLM/daily
    }
    /**
     * 显式 LLM 路径扣费。
     */
    public boolean tryAcquireLlm(long uid) {
        return tryAcquire(uid, QuotaKind.LLM).allowed(); // 与 tryAcquire(uid) 等价
    }
    /**
     * 按种类扣费。 coach：仅 coach 桶；llm：短时重复限流 + daily + llm 分钟桶。
     */
    public AcquireResult tryAcquire(long uid, QuotaKind kind) {
        return tryAcquire(uid, kind, 0);
    }
    /**
     * 查询指定种类当前还需等待多少秒（未建桶返回 1）。
     */
    public int retryAfterSeconds(long uid, QuotaKind kind) {
        if (uid <= 0) { // 若满足 uid <= 0 则走本分支
            return 60; // 非法 uid 与拒绝非法参数一致
        }
        PlayerBuckets buckets = uidBuckets.get(uid); // 不懒创建，避免纯查询造桶
        if (buckets == null) { // 若满足 buckets == null 则走本分支
            return 1; // 尚无桶记录，短等待即可
        }
        Bucket bucket = kind == QuotaKind.COACH ? buckets.coach : buckets.llm; // coach 看教练桶，否则看 llm
        return estimateRetrySeconds(bucket); // 换算 nanos→秒并钳制
    }
    /**
     * 累计拒绝次数。
     */
    public long getRejectCount() {
        return rejectCount.get(); // 原子读监控计数
    }
    /**
     * 按 AiAssist 配置创建桶： coach/llm 按分钟；daily 按天；duplicate 短时重复。
     * 付费/高等级玩家使用 vipDailyLlmBudget，免费玩家使用 freeDailyLlmBudget。
     */
    private PlayerBuckets newPlayerBuckets(int playerLevel) {
        LunarCoreProperties.AiAssistProperties cfg = properties.getAiAssist();
        int coach = cfg.getCoachPerUidPerMinute() > 0
                ? cfg.getCoachPerUidPerMinute()
                : Math.max(1, cfg.getPerUidPerMinute());
        boolean vip = playerLevel >= Math.max(1, cfg.getVipMinLevel());
        int llmBase = cfg.getLlmPerUidPerMinute() > 0
                ? cfg.getLlmPerUidPerMinute()
                : Math.max(1, Math.min(10, cfg.getPerUidPerMinute()));
        int llm = vip ? Math.max(llmBase, llmBase * 2) : llmBase;
        int freeDaily = cfg.getFreeDailyLlmBudget() > 0 ? cfg.getFreeDailyLlmBudget() : 10;
        int vipDaily = cfg.getVipDailyLlmBudget() > 0 ? cfg.getVipDailyLlmBudget() : Math.max(freeDaily, 50);
        int configuredDaily = cfg.getDailyLlmBudget() > 0 ? cfg.getDailyLlmBudget() : Integer.MAX_VALUE / 4;
        int daily = Math.min(configuredDaily, vip ? vipDaily : freeDaily);
        if (cfg.getDailyLlmBudget() <= 0) {
            daily = vip ? vipDaily : freeDaily;
        }
        return new PlayerBuckets(
                newBucket(coach, Duration.ofMinutes(1)),
                newBucket(llm, Duration.ofMinutes(1)),
                newBucket(daily, Duration.ofDays(1)),
                playerLevel
        );
    }

    /**
     * 带玩家等级的扣费入口：高等级/VIP 使用更大日预算。
     */
    public AcquireResult tryAcquire(long uid, QuotaKind kind, int playerLevel) {
        return tryAcquire(uid, kind, playerLevel, false);
    }

    /**
     * @param voiceInput true 时按配置减免 LLM 配额（优先鼓励语音），仅扣 minute 桶一半概率跳过 daily。
     */
    public AcquireResult tryAcquire(long uid, QuotaKind kind, int playerLevel, boolean voiceInput) {
        if (uid <= 0 || kind == null) {
            return AcquireResult.rejected(60);
        }
        maybeEvictIdle();
        PlayerBuckets buckets = uidBuckets.computeIfAbsent(uid, ignored -> newPlayerBuckets(playerLevel));
        if (buckets.playerLevel != playerLevel && playerLevel > 0) {
            buckets = newPlayerBuckets(playerLevel);
            uidBuckets.put(uid, buckets);
        }
        buckets.touch();
        if (kind == QuotaKind.COACH) {
            if (buckets.coach.tryConsume(1)) {
                return AcquireResult.ok();
            }
            rejectCount.incrementAndGet();
            return AcquireResult.rejected(estimateRetrySeconds(buckets.coach));
        }
        boolean discount = voiceInput && properties.getAiAssist().isVoiceQuotaDiscountEnabled();
        if (discount) {
            // 语音：跳过 daily 扣减，仅扣 minute 桶，鼓励 ASR 使用
            if (!buckets.llm.tryConsume(1)) {
                rejectCount.incrementAndGet();
                return AcquireResult.rejected(estimateRetrySeconds(buckets.llm));
            }
            return AcquireResult.ok();
        }
        if (!buckets.daily.tryConsume(1)) {
            rejectCount.incrementAndGet();
            return AcquireResult.rejected(estimateRetrySeconds(buckets.daily));
        }
        if (!buckets.llm.tryConsume(1)) {
            rejectCount.incrementAndGet();
            return AcquireResult.rejected(estimateRetrySeconds(buckets.llm));
        }
        return AcquireResult.ok();
    }

    /**
     * 同一问题短时限流（1 分钟内最多 N 次）；questionKey 建议用归一化问句哈希。
     */
    public AcquireResult tryAcquireSameQuestion(long uid, String questionKey) {
        if (uid <= 0) {
            return AcquireResult.rejected(60);
        }
        String qk = questionKey == null ? "" : questionKey.trim();
        if (qk.isEmpty()) {
            return AcquireResult.ok();
        }
        maybeEvictIdle();
        PlayerBuckets buckets = uidBuckets.computeIfAbsent(uid, ignored -> newPlayerBuckets(0));
        buckets.touch();
        String fullKey = uid + "|" + qk;
        Bucket dup = buckets.questionBuckets.computeIfAbsent(fullKey, ignored -> {
            LunarCoreProperties.AiAssistProperties cfg = properties.getAiAssist();
            int dupMax = Math.max(1, cfg.getDuplicateQuestionMaxPerWindow());
            int dupWindow = Math.max(10, cfg.getDuplicateQuestionWindowSeconds());
            return newBucket(dupMax, Duration.ofSeconds(dupWindow));
        });
        if (dup.tryConsume(1)) {
            return AcquireResult.ok();
        }
        rejectCount.incrementAndGet();
        return AcquireResult.rejected(estimateRetrySeconds(dup));
    }
    /**
     * 最多每 60 秒扫一次：删除 lastAccess 早于 cutoff 的玩家桶。
     */
    private void maybeEvictIdle() {
        long now = System.currentTimeMillis(); // 当前时间
        if (now - lastEvictAtMs < 60_000L) { // 若满足 now - lastEvictAtMs < 60_000L 则走本分支
            return; // 距上次回收不足 60 秒，跳过
        }
        /**
         * 同步块：防止并发重复回收空闲额度桶
         */
        synchronized (this) {
            if (now - lastEvictAtMs < 60_000L) { // 若满足 now - lastEvictAtMs < 60_000L 则走本分支
                return; // 双重检查，避免并发重复扫表
            }
            lastEvictAtMs = now; // 标记本次回收时间
            int idleMinutes = Math.max(5, properties.getAiAssist().getQuotaIdleEvictMinutes()); // 空闲阈值至少 5 分钟
            long cutoff = now - idleMinutes * 60_000L; // 早于此时间戳视为空闲
            Iterator<Map.Entry<Long, PlayerBuckets>> it = uidBuckets.entrySet().iterator(); // 可删除迭代器
            while (it.hasNext()) { // 当 (it.hasNext()) { 时继续处理
                Map.Entry<Long, PlayerBuckets> e = it.next(); // 取出一个玩家桶
                if (e.getValue().lastAccessMs < cutoff) { // 若满足 e.getValue().lastAccessMs < cutoff 则走本分支
                    it.remove(); // 删除空闲玩家的三桶以释放内存
                }
            }
        }
    }
    /**
     * 根据桶 refill 等待纳秒估算重试秒数，结果钳制在 [1, 3600]。
     */
    private static int estimateRetrySeconds(Bucket bucket) {
        long nanos = bucket.estimateAbilityToConsume(1).getNanosToWaitForRefill(); // 等到能再扣 1 令牌的纳秒
        if (nanos <= 0) { // 若满足 nanos <= 0 则走本分支
            return 1; // 已可立即重试，仍返回 1 避免 0
        }
        long seconds = (nanos + 999_999_999L) / 1_000_000_000L; // 向上取整到秒
        return (int) Math.min(3600L, Math.max(1L, seconds)); // 最多提示等 1 小时
    }
    /**
     * 创建容量=permits、周期=period 的贪心补充令牌桶。
     */
    private static Bucket newBucket(int permits, Duration period) {
        int capacity = Math.max(1, permits); // 容量至少 1
        Bandwidth limit = Bandwidth.classic(capacity, Refill.greedy(capacity, period)); // 经典限流：满容量贪心补充
        return Bucket.builder().addLimit(limit).build(); // 构建单限制桶
    }

    /** 单个玩家的配额桶与最近访问时间。 */
    private static final class PlayerBuckets {
        private final Bucket coach;
        private final Bucket llm;
        private final Bucket daily;
        private final ConcurrentHashMap<String, Bucket> questionBuckets = new ConcurrentHashMap<>();
        private final int playerLevel;
        private volatile long lastAccessMs = System.currentTimeMillis();

        private PlayerBuckets(Bucket coach, Bucket llm, Bucket daily, int playerLevel) {
            this.coach = coach;
            this.llm = llm;
            this.daily = daily;
            this.playerLevel = playerLevel;
        }

        private void touch() {
            lastAccessMs = System.currentTimeMillis();
        }
    }
}
