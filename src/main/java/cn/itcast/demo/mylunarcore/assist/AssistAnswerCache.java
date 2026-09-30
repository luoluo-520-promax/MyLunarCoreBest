package cn.itcast.demo.mylunarcore.assist; // AI 助手回答缓存所在包

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties; // 读取 AI 助手缓存配置
import com.github.benmanes.caffeine.cache.Cache; // 使用 Caffeine 保存命中结果
import com.github.benmanes.caffeine.cache.Caffeine; // 构建带 TTL 的本地缓存
import jakarta.annotation.PostConstruct; // Spring 启动后重建缓存
import org.springframework.stereotype.Component; // 交给 Spring 管理

import java.nio.charset.StandardCharsets; // 计算摘要时使用 UTF-8
import java.security.MessageDigest; // 使用 SHA-256 生成稳定缓存键
import java.time.Duration; // 将秒数转换成过期间隔
import java.util.HexFormat; // 把摘要字节转成十六进制
import java.util.Locale; // 统一 scene 小写规则
import java.util.Map; // 遍历缓存键以按场景失效
import java.util.Optional; // 表达缓存命中与否
import java.util.Set; // 存放可缓存来源前缀
import java.util.HashSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AI 助手回答本地缓存（Caffeine TTL）。
 * <p>
 * 键：{@code u={uid}|s={scene}|q={sha256(normalize(question))}}；
 * 只缓存 retcode=0 且来源可复用的答案；命中时 source 追加 {@code -cache} 供审计区分。
 */
@Component
public class AssistAnswerCache {
    /**
     * 可入缓存的 source 前缀/精确值集合。
     */
    private static final Set<String> CACHEABLE_PREFIXES = Set.of("guide-local", "rule", "llm", "remote-", "fallback");
    /**
     * 缓存配置来源。
     */
    private final LunarCoreProperties properties;
    /**
     * 当前缓存实例。
     */
    private volatile Cache<String, AssistAnswer> cache = Caffeine.newBuilder().maximumSize(1).build();
    /** 缓存键 → 归一化问句，供相似检索。 */
    private final ConcurrentHashMap<String, String> reverseIndex = new ConcurrentHashMap<>();
    /**
     * 由 Spring 注入配置
     */
    public AssistAnswerCache(LunarCoreProperties properties) {
        this.properties = properties;
    }
    /**
     * Spring 启动完成后初始化缓存
     */
    @PostConstruct
    public void init() {
        rebuild(); // 按配置重建真实缓存实例
    }
    /**
     * 根据最新配置重建缓存
     */
    public void rebuild() {
        LunarCoreProperties.AiAssistProperties cfg = properties.getAiAssist(); // 读取 AI 助手子配置
        long ttl = Math.max(0L, cfg.getAnswerCacheTtlSeconds()); // 负 TTL 视为关闭过期
        long maxSize = Math.max(1L, cfg.getAnswerCacheMaxSize()); // 容量至少为 1
        Caffeine<Object, Object> builder = Caffeine.newBuilder().maximumSize(maxSize); // 先按容量构建
        if (ttl > 0) { // 仅在 TTL 大于 0 时启用写入过期
            builder.expireAfterWrite(Duration.ofSeconds(ttl)); // 设置回答缓存的过期时长
        }
        cache = builder.build(); // 原子替换缓存实例
    }
    /**
     * 读取缓存结果
     */
    public Optional<AssistAnswer> get(long uid, String scene, String question) {
        if (!enabled() || uid <= 0) {
            return Optional.empty();
        }
        AssistAnswer hit = cache.getIfPresent(cacheKey(uid, scene, question));
        if (hit == null) {
            return getSimilar(uid, scene, question);
        }
        String source = hit.source() == null ? "" : hit.source();
        if (source.endsWith("-cache")) {
            return Optional.of(hit);
        }
        return Optional.of(AssistAnswer.of(hit.retcode(), hit.answer(), source + "-cache",
                hit.relatedHints(), hit.citedConfigIds(), hit.disclaimer(), hit.strategyVersion(), hit.locale()));
    }

    /**
     * 相似问句缓存：Jaccard ≥ 配置阈值时复用答案（降级远程调用）。
     */
    public Optional<AssistAnswer> getSimilar(long uid, String scene, String question) {
        return getSimilar(uid, scene, question, properties.getAiAssist().getSimilarQuestionCacheThreshold());
    }

    public Optional<AssistAnswer> getSimilar(long uid, String scene, String question, double thresholdOverride) {
        if (!enabled() || uid <= 0) {
            return Optional.empty();
        }
        double threshold = Math.min(0.99, Math.max(0.5, thresholdOverride));
        String normalized = normalizeQuestion(question);
        Set<String> qTokens = tokenSet(normalized);
        if (qTokens.size() < 2) {
            return Optional.empty();
        }
        String sceneMarker = "|s=" + (scene == null || scene.isBlank() ? "general" : scene.trim().toLowerCase(Locale.ROOT)) + "|";
        String uidMarker = "u=" + uid + "|";
        AssistAnswer best = null;
        double bestScore = 0;
        for (Map.Entry<String, AssistAnswer> e : cache.asMap().entrySet()) {
            if (!e.getKey().startsWith(uidMarker) || !e.getKey().contains(sceneMarker)) {
                continue;
            }
            String storedNorm = reverseIndex.get(e.getKey());
            if (storedNorm == null || storedNorm.isBlank()) {
                continue;
            }
            double score = jaccard(qTokens, tokenSet(storedNorm));
            if (score >= threshold && score > bestScore) {
                bestScore = score;
                best = e.getValue();
            }
        }
        if (best == null) {
            return Optional.empty();
        }
        String source = best.source() == null ? "" : best.source();
        if (!source.endsWith("-cache")) {
            source = source + "-cache";
        }
        return Optional.of(AssistAnswer.of(best.retcode(), best.answer(), source,
                best.relatedHints(), best.citedConfigIds(), best.disclaimer(), best.strategyVersion(), best.locale()));
    }
    /**
     * 写入可复用答案
     */
    public void put(long uid, String scene, String question, AssistAnswer answer) {
        if (!enabled() || uid <= 0 || answer == null || answer.retcode() != 0) { // 关闭、非法或失败结果不缓存
            return; // 直接结束写入流程
        }
        String source = answer.source() == null ? "" : answer.source(); // 取出来源以判断是否可缓存
        if (!isCacheableSource(source)) { // 不可复用的瞬时结果不入缓存
            return; // 保持缓存只存稳定答案
        }
        String key = cacheKey(uid, scene, question);
        String baseSource = source.endsWith("-cache") ? source.substring(0, source.length() - "-cache".length()) : source;
        cache.put(key, AssistAnswer.of(answer.retcode(), answer.answer(), baseSource,
                answer.relatedHints(), answer.citedConfigIds(),
                answer.disclaimer(), answer.strategyVersion(), answer.locale()));
        reverseIndex.put(key, normalizeQuestion(question));
    }
    /**
     * 按场景清理相关缓存
     */
    public void invalidateScene(String scene) {
        if (scene == null || scene.isBlank()) {
            cache.invalidateAll();
            reverseIndex.clear();
            return;
        }
        String marker = "|s=" + scene.trim().toLowerCase(Locale.ROOT) + "|";
        for (Map.Entry<String, AssistAnswer> e : cache.asMap().entrySet()) {
            if (e.getKey().contains(marker)) {
                cache.invalidate(e.getKey());
                reverseIndex.remove(e.getKey());
            }
        }
    }

    public void invalidateAll() {
        cache.invalidateAll();
        reverseIndex.clear();
    }

    private static Set<String> tokenSet(String normalized) {
        Set<String> set = new HashSet<>();
        if (normalized == null || normalized.isEmpty()) {
            return set;
        }
        // 中文按 2-gram，英文按连续字母数字
        StringBuilder latin = new StringBuilder();
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                latin.append(c);
            } else {
                if (latin.length() >= 2) {
                    set.add(latin.toString());
                }
                latin.setLength(0);
                if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN && i + 1 < normalized.length()) {
                    set.add(normalized.substring(i, i + 2));
                }
            }
        }
        if (latin.length() >= 2) {
            set.add(latin.toString());
        }
        return set;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        int inter = 0;
        for (String t : a) {
            if (b.contains(t)) {
                inter++;
            }
        }
        int union = a.size() + b.size() - inter;
        return union <= 0 ? 0 : inter * 1.0 / union;
    }
    /**
     * 返回缓存估算大小
     */
    public long estimatedSize() {
        return cache.estimatedSize(); // 供监控页展示缓存规模
    }
    /**
     * 判断缓存是否处于启用状态
     */
    private boolean enabled() {
        return properties.getAiAssist().getAnswerCacheTtlSeconds() > 0; // TTL 大于 0 才允许读写缓存
    }
    /**
     * 判断来源是否适合复用
     */
    private static boolean isCacheableSource(String source) {
        if (source == null || source.isBlank() || "blocked".equals(source) || "error".equals(source) || "accepted".equals(source)) { // 拦截、错误和仅受理态不缓存
            return false; // 这些结果不应长期复用
        }
        String base = source.endsWith("-cache") ? source.substring(0, source.length() - 6) : source; // 先去掉缓存后缀再判断
        if (CACHEABLE_PREFIXES.contains(base)) { // guide-local/rule/llm/fallback 精确命中
            return true; // 表示结果可以复用
        }
        for (String prefix : CACHEABLE_PREFIXES) { // 兼容远程降级来源前缀
            if (prefix.endsWith("-") && base.startsWith(prefix)) { // 识别 remote-xxx 这种来源
                return true; // 允许进入缓存
            }
        }
        return false; // 其它瞬时来源保持不缓存
    }
    /**
     * 构造稳定缓存键
     */
    static String cacheKey(long uid, String scene, String question) {
        String s = scene == null || scene.isBlank() ? "general" : scene.trim().toLowerCase(Locale.ROOT); // 场景空值回退到 general
        String q = normalizeQuestion(question); // 先归一化再哈希
        return "u=" + uid + "|s=" + s + "|q=" + sha256(q); // 生成最终键串
    }
    /**
     * 归一化玩家问题文本
     */
    static String normalizeQuestion(String question) {
        if (question == null) { // 空问题直接返回空串
            return ""; // 保证后续哈希稳定
        }
        StringBuilder sb = new StringBuilder(question.length()); // 预估长度并拼接归一化内容
        for (int i = 0; i < question.length(); i++) { // 逐字处理输入文本
            char c = question.charAt(i); // 取出当前字符
            if (c >= 0xFF01 && c <= 0xFF5E) { // 全角可见字符范围
                c = (char) (c - 0xFEE0); // 转成半角字符
            } else if (c == 0x3000) { // 全角空格
                c = ' '; // 替换成普通空格
            }
            if (Character.isWhitespace(c)) { // 去掉所有空白字符
                continue; // 空白不参与缓存键计算
            }
            sb.append(Character.toLowerCase(c)); // 统一转小写再拼接
        }
        String n = sb.toString(); // 得到标准化文本
        n = n.replace("卡池", "抽卡") // 将常见同义说法压缩为统一词
                .replace("保底进度", "保底") // 让同类问法命中同一个缓存键
                .replace("主线任务", "主线") // 去掉冗余后缀
                .replace("今日活动", "活动") // 按业务常见口语归一
                .replace("怎么玩", "玩法"); // 将询问方式统一到同一个语义词
        return n; // 返回可用于哈希的归一化文本
    }
    /**
     * 计算问题摘要
     */
    private static String sha256(String input) {
        try { // 标准加密算法优先
            MessageDigest md = MessageDigest.getInstance("SHA-256"); // 获取 SHA-256 摘要器
            byte[] dig = md.digest(input.getBytes(StandardCharsets.UTF_8)); // 对 UTF-8 文本做摘要
            return HexFormat.of().formatHex(dig); // 输出十六进制字符串
        } catch (Exception e) { // 极端情况下算法不可用
            return Integer.toHexString(input.hashCode()); // 用 hashCode 作为稳定兜底
        }
    }
}
