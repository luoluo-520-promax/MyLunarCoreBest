package cn.itcast.demo.mylunarcore.assist;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;

/**
 * 轻量语义安全分类器（无外部模型依赖）。
 * <p>在关键词/正则之外，用意图模板与字符混淆归一，拦截绕过词表的变体。
 * 命中后仍走既有 BLOCK/SOFT_BLOCK 分流，并由审计记录拦截内容摘要。
 */
@Component
public class AssistSemanticSafetyClassifier {

    public enum Intent {
        ALLOW,
        PRIVACY_PROBE,
        ABUSE_OR_CSAM,
        CHEAT_EXPLOIT,
        RECHARGE_INDUCEMENT,
        REAL_MONEY_TRADE
    }

    private static final Set<String> PRIVACY_HINTS = Set.of(
            "别人手机", "他人账号", "偷看私聊", "查别人", "定位他人", "人肉", "开盒");
    private static final Set<String> ABUSE_HINTS = Set.of(
            "儿童色情", "未成年色情", "自杀教程", "如何自杀", "爆炸物制作");
    private static final Set<String> CHEAT_HINTS = Set.of(
            "修改器下载", "内存修改", "外挂下载", "刷星琼脚本", "破解客户端");
    private static final Set<String> RECHARGE_HINTS = Set.of(
            "代充最便宜", "必出保底攻略充钱", "充值返利群", "代付链接");
    private static final Set<String> TRADE_HINTS = Set.of(
            "卖号联系", "出号微信", "买号qq", "账号交易平台");

    private final LunarCoreProperties properties;

    public AssistSemanticSafetyClassifier(LunarCoreProperties properties) {
        this.properties = properties;
    }

    public Intent classify(String question) {
        if (!properties.getAiAssist().isSemanticSafetyEnabled()) {
            return Intent.ALLOW;
        }
        if (question == null || question.isBlank()) {
            return Intent.ALLOW;
        }
        String q = normalize(question);
        if (containsAny(q, ABUSE_HINTS)) {
            return Intent.ABUSE_OR_CSAM;
        }
        if (containsAny(q, PRIVACY_HINTS)) {
            return Intent.PRIVACY_PROBE;
        }
        if (containsAny(q, CHEAT_HINTS)) {
            return Intent.CHEAT_EXPLOIT;
        }
        if (containsAny(q, TRADE_HINTS)) {
            return Intent.REAL_MONEY_TRADE;
        }
        if (containsAny(q, RECHARGE_HINTS)) {
            return Intent.RECHARGE_INDUCEMENT;
        }
        // 混淆：把数字/符号夹在敏感词中间仍可命中（简化语义）
        String compact = q.replaceAll("[\\s\\-_.*·]+", "");
        if (compact.contains("开挂") || compact.contains("外挂源码")) {
            return Intent.CHEAT_EXPLOIT;
        }
        if (compact.contains("卖号") || compact.contains("买号")) {
            return Intent.REAL_MONEY_TRADE;
        }
        return Intent.ALLOW;
    }

    /** BLOCK 意图（与关键词硬拦同级）。 */
    public boolean isHardBlock(Intent intent) {
        return intent == Intent.ABUSE_OR_CSAM
                || intent == Intent.PRIVACY_PROBE
                || intent == Intent.CHEAT_EXPLOIT
                || intent == Intent.REAL_MONEY_TRADE;
    }

    /** SOFT_BLOCK 意图。 */
    public boolean isSoftBlock(Intent intent) {
        return intent == Intent.RECHARGE_INDUCEMENT;
    }

    private static String normalize(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0xFF01 && c <= 0xFF5E) {
                c = (char) (c - 0xFEE0);
            }
            if (!Character.isWhitespace(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private static boolean containsAny(String q, Set<String> hints) {
        for (String h : hints) {
            if (q.contains(h.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
