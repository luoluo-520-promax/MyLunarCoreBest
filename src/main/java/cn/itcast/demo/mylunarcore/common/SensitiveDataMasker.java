package cn.itcast.demo.mylunarcore.common;

import java.util.regex.Pattern;

/**
 * 生产日志脱敏：密码、Token、IAP、身份证、手机、邮箱等敏感字段替换为掩码。
 * <p>
 * 与 Logback {@code %maskMsg} / {@link MaskingMessageConverter} 配合；
 * 也可在 MDC 中放入业务字段后由统一 converter 处理。
 */
public final class SensitiveDataMasker {

    private static final Pattern PASSWORD = Pattern.compile(
            "(?i)(password|passwd|pwd)\\s*[=:]\\s*([^\\s,;\"']+)");
    private static final Pattern TOKEN = Pattern.compile(
            "(?i)(token|authorization|bearer|jwt|internal[_-]?token)\\s*[=:]\\s*([^\\s,;\"']+)");
    private static final Pattern IAP_TX = Pattern.compile(
            "(?i)(channel[_-]?tx[_-]?id|receipt|transaction[_-]?id|purchase[_-]?token)\\s*[=:]\\s*([^\\s,;\"']+)");
    private static final Pattern BEARER_HEADER = Pattern.compile(
            "(?i)Bearer\\s+[A-Za-z0-9._\\-]+");
    private static final Pattern API_KEY = Pattern.compile(
            "(?i)(api[_-]?key|secret|private[_-]?key|shared[_-]?secret)\\s*[=:]\\s*([^\\s,;\"']+)");
    private static final Pattern EMAIL = Pattern.compile(
            "(?i)([a-z0-9._%+-]+)@([a-z0-9.-]+\\.[a-z]{2,})");
    private static final Pattern EMAIL_FIELD = Pattern.compile(
            "(?i)(email|mail)\\s*[=:]\\s*([^\\s,;\"']+)");
    private static final Pattern PHONE = Pattern.compile(
            "(?i)(phone|mobile|tel)\\s*[=:]\\s*(\\+?\\d{6,15})");
    private static final Pattern CN_MOBILE = Pattern.compile(
            "(?<!\\d)(1[3-9]\\d)\\d{4}(\\d{4})(?!\\d)");
    private static final Pattern ID_CARD = Pattern.compile(
            "(?i)(id[_-]?card|id[_-]?no|身份证|身份证号)\\s*[=:]\\s*([0-9Xx]{15,18})");
    private static final Pattern ID_CARD_RAW = Pattern.compile(
            "(?<![0-9A-Za-z])([1-9]\\d{5})(19|20)\\d{2}(0[1-9]|1[0-2])(0[1-9]|[12]\\d|3[01])(\\d{3})([0-9Xx])(?![0-9A-Za-z])");

    private SensitiveDataMasker() {
    }

    public static String mask(String message) {
        if (message == null || message.isEmpty()) {
            return message;
        }
        String out = PASSWORD.matcher(message).replaceAll("$1=***");
        out = TOKEN.matcher(out).replaceAll("$1=***");
        out = IAP_TX.matcher(out).replaceAll("$1=***");
        out = BEARER_HEADER.matcher(out).replaceAll("Bearer ***");
        out = API_KEY.matcher(out).replaceAll("$1=***");
        out = EMAIL_FIELD.matcher(out).replaceAll("$1=***");
        out = EMAIL.matcher(out).replaceAll("***@$2");
        out = PHONE.matcher(out).replaceAll("$1=***");
        out = CN_MOBILE.matcher(out).replaceAll("$1****$2");
        out = ID_CARD.matcher(out).replaceAll("$1=***");
        out = ID_CARD_RAW.matcher(out).replaceAll("$1********$5$6");
        return out;
    }
}
