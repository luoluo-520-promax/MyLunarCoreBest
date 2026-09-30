package cn.itcast.demo.mylunarcore.assist;

import cn.itcast.demo.mylunarcore.common.AppLogger;
import cn.itcast.demo.mylunarcore.common.LogCategory;
import org.slf4j.Logger;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 记录助手问答的来源分布、缓存命中、延迟与配额拒绝，供运营与排障。
 * <p>
 * 日志只输出 uidHash，不落明文 uid。
 */
@Component
public class AssistAuditService {
    /**
     * 日志记录器
     */
    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_ASSIST, AssistAuditService.class);
    /**
     * 总问答次数
     */
    private final AtomicLong askCount = new AtomicLong();
    /**
     * source 归类为 llm
     */
    private final AtomicLong llmHitCount = new AtomicLong();
    /**
     * rule 或 fallback
     */
    private final AtomicLong ruleFallbackCount = new AtomicLong();
    /**
     * 安全拦截
     */
    private final AtomicLong blockedCount = new AtomicLong();
    /**
     * retcode!=0
     */
    private final AtomicLong errorCount = new AtomicLong();
    /**
     * 缓存命中
     */
    private final AtomicLong cacheHitCount = new AtomicLong();
    /**
     * 本地攻略 FAQ
     */
    private final AtomicLong guideLocalCount = new AtomicLong();
    /**
     * 远程 ai-assist-service
     */
    private final AtomicLong remoteCount = new AtomicLong();
    /**
     * 限流拒绝
     */
    private final AtomicLong quotaRejectCount = new AtomicLong();
    /**
     * 延迟累加（毫秒）
     */
    private final LongAdder latencySumMs = new LongAdder();
    /**
     * 有延迟样本数
     */
    private final AtomicLong latencySamples = new AtomicLong();
    /**
     * 分桶计数
     */
    private final ConcurrentHashMap<String, AtomicLong> sourceCounters = new ConcurrentHashMap<>();
    /**
     * 兼容旧调用：无延迟、非缓存、无引用数。
     */
    public void record(long uid, String scene, String source, int retcode, String question, String answerSnippet) {
        record(uid, scene, source, retcode, question, answerSnippet, -1L, false, 0); // latencyMs=-1 表示不计入均值
    }
    /**
     * 完整审计记录：分桶、延迟、缓存标记与截断日志。
     */
    public void record(long uid,
                       String scene,
                       String source,
                       int retcode, // 续写 record 的参数列表
                       String question,
                       String answerSnippet,
                       long latencyMs, // 续写 record 的参数列表
                       boolean cacheHit, // 续写 record 的参数列表
                       int citedCount) {
        askCount.incrementAndGet(); // 总请求 +1
        if (retcode != 0) { // 若满足 retcode != 0 则走本分支
            errorCount.incrementAndGet(); // 非成功码计入错误
        }
        String src = source == null ? "" : source; // 防御 null source
        String bucket = classifySource(src); // 归一到 llm/rule/remote/...
        sourceCounters.computeIfAbsent(bucket, k -> new AtomicLong()).incrementAndGet(); // 分桶 +1
        if (cacheHit || src.endsWith("-cache")) { // 若满足 cacheHit || src.endsWith("-cache") 则走本分支
            cacheHitCount.incrementAndGet(); // 显式标记或 source 后缀均算缓存命中
        }
        switch (bucket) { // 按 (bucket) { 分派处理
            case "llm" -> llmHitCount.incrementAndGet(); // 本地/直连 LLM
            case "rule", "fallback" -> ruleFallbackCount.incrementAndGet(); // 规则或兜底
            case "blocked" -> blockedCount.incrementAndGet(); // 安全拦截
            case "guide-local" -> guideLocalCount.incrementAndGet(); // 本地 FAQ
            case "remote" -> remoteCount.incrementAndGet(); // 远程服务
            default -> { // 匹配分支 default -> { 的场景预算/来源处理
                // 其他来源只保留在 sourceCounters，不额外加专用计数器
            }
        }
        if (latencyMs >= 0) { // 若满足 latencyMs >= 0 则走本分支
            latencySumMs.add(latencyMs); // 累加延迟
            latencySamples.incrementAndGet(); // 样本数 +1
        }
        log.info("ai_audit uidHash={}, scene={}, source={}, retcode={}, latencyMs={}, cacheHit={}, citedCount={}, qLen={}, aLen={}, qSnippet={}, aSnippet={}",
                Integer.toHexString(Long.hashCode(uid)), // 仅打哈希
                scene == null ? "" : scene, // 场景名
                src, // 原始 source
                retcode, // 业务码
                latencyMs, // 耗时
                cacheHit || src.endsWith("-cache"), // 是否缓存
                citedCount, // 引用配置条数
                question == null ? 0 : question.length(), // 问题长度
                answerSnippet == null ? 0 : answerSnippet.length(), // 答案长度
                truncate(question, 40), // 问题摘要
                truncate(answerSnippet, 60)); // 答案摘要
    }
    /**
     * 记录配额拒绝（不计入 askCount）。
     */
    public void recordQuotaReject(long uid, String kind) {
        quotaRejectCount.incrementAndGet(); // 限流拒绝 +1
        log.info("ai_quota_reject uidHash={}, kind={}", Integer.toHexString(Long.hashCode(uid)), kind); // kind=COACH/LLM
    }
    /**
     * 读取问答总次数
     */
    public long getAskCount() {
        return askCount.get(); // 总问答
    }
    /**
     * 读取 LLM 命中次数
     */
    public long getLlmHitCount() {
        return llmHitCount.get(); // LLM 命中
    }
    /**
     * 读取规则兜底次数
     */
    public long getRuleFallbackCount() {
        return ruleFallbackCount.get(); // 规则/兜底
    }
    /**
     * 读取安全拦截次数
     */
    public long getBlockedCount() {
        return blockedCount.get(); // 拦截次数
    }
    /**
     * 读取失败调用次数
     */
    public long getErrorCount() {
        return errorCount.get(); // 错误码次数
    }
    /**
     * 读取缓存命中次数
     */
    public long getCacheHitCount() {
        return cacheHitCount.get(); // 缓存命中
    }
    /**
     * 读取本地攻略命中次数
     */
    public long getGuideLocalCount() {
        return guideLocalCount.get(); // 本地攻略
    }
    /**
     * 读取远程调用次数
     */
    public long getRemoteCount() {
        return remoteCount.get(); // 远程调用成功路径计数
    }
    /**
     * 读取配额拒绝次数
     */
    public long getQuotaRejectCount() {
        return quotaRejectCount.get(); // 限流拒绝
    }
    /**
     * 平均延迟毫秒；无样本返回 0。
     */
    public double averageLatencyMs() {
        long n = latencySamples.get(); // 样本数
        if (n <= 0) { // 若满足 n <= 0 则走本分支
            return 0; // 避免除零
        }
        return latencySumMs.sum() * 1.0 / n; // 总和/样本
    }
    /**
     * 按归一化后的 source 桶取计数。
     */
    public long sourceCount(String prefix) {
        AtomicLong c = sourceCounters.get(classifySource(prefix)); // 先归一再查
        return c == null ? 0 : c.get(); // 无键视为 0
    }
    /**
     * 将来源串归一：去掉 -cache 后缀；remote、guide-local、llm、rule、fallback、blocked、error、accepted 等前缀归桶。
     */
    static String classifySource(String source) {
        if (source == null || source.isBlank()) { // 若满足 source == null || source.isBlank() 则走本分支
            return "unknown"; // 空来源
        }
        String s = source.toLowerCase(Locale.ROOT); // 统一小写
        if (s.endsWith("-cache")) { // 若满足 s.endsWith("-cache") 则走本分支
            s = s.substring(0, s.length() - 6); // 剥掉缓存后缀再归类
        }
        if (s.startsWith("remote")) { // 若满足 s.startsWith("remote") 则走本分支
            return "remote"; // 远程服务族
        }
        if (s.startsWith("guide-local")) { // 若满足 s.startsWith("guide-local") 则走本分支
            return "guide-local"; // 本地 FAQ 族
        }
        if (s.startsWith("llm")) { // 若满足 s.startsWith("llm") 则走本分支
            return "llm"; // LLM 族
        }
        if (s.startsWith("rule")) { // 若满足 s.startsWith("rule") 则走本分支
            return "rule"; // 规则族
        }
        if (s.startsWith("fallback")) { // 若满足 s.startsWith("fallback") 则走本分支
            return "fallback"; // 兜底族
        }
        if (s.startsWith("blocked")) { // 若满足 s.startsWith("blocked") 则走本分支
            return "blocked"; // 拦截族
        }
        if (s.startsWith("error")) { // 若满足 s.startsWith("error") 则走本分支
            return "error"; // 错误族
        }
        if (s.startsWith("accepted")) { // 若满足 s.startsWith("accepted") 则走本分支
            return "accepted"; // 异步已受理族
        }
        return s; // 其他原样作为桶名
    }
    /**
     * 截断字符串供日志，换行替换为空格。
     */
    private static String truncate(String s, int max) {
        if (s == null) { // 若满足 s == null 则走本分支
            return ""; // null 当空串
        }
        String t = s.replace('\n', ' ').replace('\r', ' '); // 单行化
        return t.length() <= max ? t : t.substring(0, max) + "…"; // 超长加省略号
    }
}
