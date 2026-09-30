package cn.itcast.demo.mylunarcore.assist;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 入站问题安全分流 + 出站答案清洗。
 * <p>
 * 入站：BLOCK / SOFT_BLOCK / ALLOW；出站：敏感句剔除、长度截断、抽卡场景追加免责声明。
 */
@Component
public class AssistSafetyFilter {

    /** 入站分级：允许、软拦（给官方说明引导）、硬拦。 */
    public enum InboundLevel {
        ALLOW, // 枚举常量 ALLOW，供额度/分级判断使用
        SOFT_BLOCK, // 枚举常量 SOFT_BLOCK，供额度/分级判断使用
        BLOCK // 枚举常量 BLOCK，供额度/分级判断使用
    }
    /** 词表来源：仓储热更或单测 defaults。 */
    private final AssistSafetyRulesSource rulesSource;
    /** 轻量语义意图分类（可空，单测可不注入）。 */
    private final AssistSemanticSafetyClassifier semanticClassifier;

    public AssistSafetyFilter(AssistSafetyRulesSource rulesSource) {
        this(rulesSource, null);
    }

    public AssistSafetyFilter(AssistSafetyRulesSource rulesSource,
                              AssistSemanticSafetyClassifier semanticClassifier) {
        this.rulesSource = rulesSource == null ? AssistSafetyRulesConfig::defaults : rulesSource;
        this.semanticClassifier = semanticClassifier;
    }
    /**
     * 是否硬拦截该问题。
     */
    public boolean isQuestionBlocked(String question) {
        return classifyInbound(question) == InboundLevel.BLOCK; // 复用统一分类，避免双套规则
    }
    /**
     * 是否软拦截该问题。
     */
    public boolean isQuestionSoftBlocked(String question) {
        return classifyInbound(question) == InboundLevel.SOFT_BLOCK; // 软拦仍返回引导文案，不进 LLM
    }
    /**
     * 对问题做入站分级。空问题视为 BLOCK。
     */
    public InboundLevel classifyInbound(String question) {
        if (question == null || question.isBlank()) {
            return InboundLevel.BLOCK;
        }
        String q = normalizeForMatch(question);
        AssistSafetyRulesConfig rules = rulesSource.current();
        if (matchesAny(q, rules.blockPatterns())) {
            return InboundLevel.BLOCK;
        }
        if (semanticClassifier != null) {
            AssistSemanticSafetyClassifier.Intent intent = semanticClassifier.classify(question);
            if (semanticClassifier.isHardBlock(intent)) {
                return InboundLevel.BLOCK;
            }
            if (semanticClassifier.isSoftBlock(intent)) {
                return InboundLevel.SOFT_BLOCK;
            }
        }
        if (matchesAny(q, rules.softBlockPatterns())) {
            return InboundLevel.SOFT_BLOCK;
        }
        return InboundLevel.ALLOW;
    }
    /**
     * 无场景信息时的答案清洗入口。
     */
    public String sanitizeAnswer(String answer) {
        return sanitizeAnswer(answer, null); // scene=null 时不做抽卡免责追加
    }
    /**
     * 清洗答案：出站敏感模式 → 按句剔除 → 仍敏感则换 rechargeSanitizeReply； 超长截断；gacha 场景追加免责声明。
     */
    public String sanitizeAnswer(String answer, String scene) {
        AssistSafetyRulesConfig rules = rulesSource.current(); // 当前出站规则快照
        if (answer == null || answer.isBlank()) { // 若满足 answer == null || answer.isBlank() 则走本分支
            return "暂时无法给出建议，请打开对应界面查看官方说明。"; // 空答案用官方引导兜底
        }
        String sanitized = answer.trim(); // 去掉首尾空白再匹配
        if (matchesAny(sanitized, rules.outboundBlockPatterns())) { // 若满足 matchesAny(sanitized, rules.outboundBlockPatterns()) 则走本分支
            String sentenceFiltered = filterOutboundSentences(sanitized, rules); // 按句剔除敏感句
            if (sentenceFiltered.isBlank() || matchesAny(sentenceFiltered, rules.outboundBlockPatterns())) { // 若满足 sentenceFiltered.isBlank() || matchesAny(sentenceFiltered, rules.outboundBlockPa 则走本分支
                sanitized = rules.rechargeSanitizeReply(); // 整段仍敏感：替换为充值诱导拒答文案
            } else { // 条件不成立时的替代分支
                sanitized = sentenceFiltered; // 剔除敏感句后保留剩余正文
            }
        }
        int maxLen = rules.maxAnswerLength(); // 配置的最大答案长度
        if (sanitized.length() > maxLen) { // 若满足 sanitized.length() > maxLen 则走本分支
            sanitized = sanitized.substring(0, maxLen) + "…"; // 超长截断并加省略号
        }
        if (scene != null && "gacha".equalsIgnoreCase(scene.trim()) // 若满足 scene != null && "gacha".equalsIgnoreCase(scene.trim() 则走本分支
                && !sanitized.contains("官方说明")) { // 延续上一行布尔条件
            sanitized = sanitized + " " + rules.gachaDisclaimer(); // 抽卡场景追加免责声明
            if (sanitized.length() > maxLen) { // 若满足 sanitized.length() > maxLen 则走本分支
                sanitized = sanitized.substring(0, maxLen) + "…"; // 追加后可能再次超长，再截断
            }
        }
        return sanitized; // 返回可展示给玩家的安全正文
    }
    /**
     * 硬拦时的固定回复文案。
     */
    public String blockedQuestionReply() {
        return rulesSource.current().blockedQuestionReply(); // 来自安全规则配置
    }
    /**
     * 软拦时的固定回复文案。
     */
    public String softBlockedQuestionReply() {
        return rulesSource.current().softBlockReply(); // 引导查看官方说明
    }
    /**
     * 按中英文句号/叹号/问号分句，剔除命中 kill/block 模式的句子。
     */
    private static String filterOutboundSentences(String text, AssistSafetyRulesConfig rules) {
        String[] parts = text.split("(?<=[。！？!;；])"); // 保留分隔符在前句末尾
        List<String> kept = new ArrayList<>(); // 幸存句子
        for (String part : parts) { // 遍历 (String part : parts) { 处理每一项
            String p = part.trim(); // 单句去空白
            if (p.isEmpty()) { // 若满足 p.isEmpty() 则走本分支
                continue; // 跳过空片段
            }
            if (matchesAny(p, rules.outboundSentenceKillPatterns()) // 若满足 matchesAny(p, rules.outboundSentenceKillPatterns() 则走本分支
                    || matchesAny(p, rules.outboundBlockPatterns())) { // 延续上一行布尔条件
                continue; // 整句命中敏感模式则丢弃
            }
            kept.add(p); // 保留安全句子
        }
        return String.join("", kept).trim(); // 拼回正文
    }
    /**
     * 任一正则命中即 true；非法正则回退为字面包含匹配。
     */
    private static boolean matchesAny(String text, List<String> patterns) {
        if (text == null || patterns == null || patterns.isEmpty()) { // 若满足 text == null || patterns == null || patterns.isEmpty() 则走本分支
            return false; // 无文本或无模式视为不命中
        }
        for (String raw : patterns) { // 遍历 (String raw : patterns) { 处理每一项
            if (raw == null || raw.isBlank()) { // 若满足 raw == null || raw.isBlank() 则走本分支
                continue; // 跳过空模式项
            }
            try { // 包裹可能失败的外部/IO/推理调用
                if (Pattern.compile(raw, Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(text).find()) { // 若满足 Pattern.compile(raw, Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(text).fi 则走本分支
                    return true; // 正则命中
                }
            } catch (Exception ignored) { // 捕获异常后降级：记日志并返回错误或空结果
                if (text.toLowerCase(Locale.ROOT).contains(raw.toLowerCase(Locale.ROOT))) { // 若满足 text.toLowerCase(Locale.ROOT).contains(raw.toLowerCase(Locale.ROOT)) 则走本分支
                    return true; // 非法正则时按字面子串匹配
                }
            }
        }
        return false; // 全部未命中
    }
    /**
     * 去掉空白并转小写，供入站匹配。
     */
    static String normalizeForMatch(String question) {
        StringBuilder sb = new StringBuilder(question.length()); // 预分配容量
        for (int i = 0; i < question.length(); i++) { // 遍历 (int i = 0; i < question.length(); i++) { 处理每一项
            char c = question.charAt(i); // 逐字符扫描
            if (Character.isWhitespace(c)) { // 若满足 Character.isWhitespace(c) 则走本分支
                continue; // 丢弃所有空白
            }
            sb.append(Character.toLowerCase(c)); // 统一小写
        }
        return sb.toString(); // 归一化后的匹配串
    }
}
