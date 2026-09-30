package cn.itcast.demo.mylunarcore.assist;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;

/**
 * 多语言：根据请求 locale / Accept-Language 选择回复语言与模板。
 */
@Component
public class AssistLocaleService {

    private final LunarCoreProperties properties;

    public AssistLocaleService(LunarCoreProperties properties) {
        this.properties = properties;
    }

    public String resolveLocale(String requestLocale, String acceptLanguage) {
        if (requestLocale != null && !requestLocale.isBlank()) {
            return normalize(requestLocale);
        }
        if (acceptLanguage != null && !acceptLanguage.isBlank()) {
            String first = acceptLanguage.split(",")[0].trim();
            int sc = first.indexOf(';');
            if (sc > 0) {
                first = first.substring(0, sc).trim();
            }
            return normalize(first);
        }
        return normalize(properties.getAiAssist().getDefaultLocale());
    }

    public boolean isEnglish(String locale) {
        return locale != null && locale.toLowerCase(Locale.ROOT).startsWith("en");
    }

    public String complianceDisclaimer(String locale) {
        LunarCoreProperties.AiAssistProperties cfg = properties.getAiAssist();
        if (!cfg.isComplianceDisclaimerEnabled()) {
            return "";
        }
        if (isEnglish(locale)) {
            return AssistPromptTemplates.COMPLIANCE_DISCLAIMER_EN;
        }
        String custom = cfg.getComplianceDisclaimer();
        return custom == null || custom.isBlank()
                ? AssistPromptTemplates.COMPLIANCE_DISCLAIMER_ZH
                : custom;
    }

    public String emptyQuestionReply(String locale) {
        return isEnglish(locale) ? "Please enter a question." : "请输入要咨询的问题。";
    }

    public String unavailableReply(String locale) {
        return isEnglish(locale)
                ? "Assistant is temporarily unavailable. Please try again later."
                : "助手暂时不可用，请稍后再试。";
    }

    public Map<String, String> commonFaqFallback(String locale) {
        if (isEnglish(locale)) {
            return Map.of("hint", "Open Quests or Events for official guidance.");
        }
        return Map.of("hint", "请打开任务或活动界面查看官方说明。");
    }

    private static String normalize(String locale) {
        String v = locale.trim().replace('_', '-');
        if (v.toLowerCase(Locale.ROOT).startsWith("en")) {
            return "en-US";
        }
        if (v.toLowerCase(Locale.ROOT).startsWith("zh")) {
            return "zh-CN";
        }
        return v;
    }
}
