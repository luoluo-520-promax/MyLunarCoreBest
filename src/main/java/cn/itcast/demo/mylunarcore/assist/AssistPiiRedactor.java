package cn.itcast.demo.mylunarcore.assist;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * AI 场景 PII 脱敏：送入 LLM 前替换 UID/手机号/邮箱/身份证等，避免明文外泄。
 */
@Component
public class AssistPiiRedactor {

    private static final Pattern EMAIL = Pattern.compile(
            "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");
    private static final Pattern PHONE = Pattern.compile("(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern UID_TOKEN = Pattern.compile("(?i)(uid|玩家id|账号)[=:：\\s]*\\d{4,}");
    private static final Pattern LONG_DIGIT = Pattern.compile("(?<!\\d)\\d{15,19}(?!\\d)");

    /**
     * 脱敏文本；blank 原样返回。
     */
    public String redact(String text) {
        if (text == null || text.isBlank()) {
            return text == null ? "" : text;
        }
        String out = text;
        out = EMAIL.matcher(out).replaceAll("[EMAIL]");
        out = PHONE.matcher(out).replaceAll("[PHONE]");
        out = UID_TOKEN.matcher(out).replaceAll("$1=[UID]");
        out = LONG_DIGIT.matcher(out).replaceAll("[ID]");
        return out;
    }

    /**
     * 脱敏 Map 中字符串值（浅拷贝），供上下文拼装使用。
     */
    public java.util.Map<String, Object> redactContext(java.util.Map<String, Object> ctx) {
        if (ctx == null || ctx.isEmpty()) {
            return ctx == null ? java.util.Map.of() : ctx;
        }
        java.util.Map<String, Object> copy = new java.util.LinkedHashMap<>(ctx);
        for (var e : copy.entrySet()) {
            if (e.getValue() instanceof String s) {
                e.setValue(redact(s));
            }
        }
        // 永不把真实 uid/nickname 明文送入 LLM
        copy.remove("uid");
        copy.remove("nickname");
        copy.putIfAbsent("playerRef", "P#" + Integer.toHexString(System.identityHashCode(ctx) & 0xffff));
        return copy;
    }
}
