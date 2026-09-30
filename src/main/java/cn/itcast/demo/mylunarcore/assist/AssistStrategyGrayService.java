package cn.itcast.demo.mylunarcore.assist;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * AI 策略灰度：按 UID 尾号切换 prompt/策略版本；fallback 率过高时自动回滚 baseline。
 */
@Component
public class AssistStrategyGrayService {

    private final LunarCoreProperties properties;
    private final AtomicLong baselineAsks = new AtomicLong();
    private final AtomicLong grayAsks = new AtomicLong();
    private final AtomicLong grayFallbacks = new AtomicLong();
    private volatile boolean forceRollback;

    public AssistStrategyGrayService(LunarCoreProperties properties) {
        this.properties = properties;
    }

    /** 解析本次应使用的策略版本。 */
    public String resolveStrategyVersion(long uid) {
        LunarCoreProperties.AiAssistProperties cfg = properties.getAiAssist();
        String baseline = emptyTo(cfg.getStrategyVersion(), "baseline");
        String gray = cfg.getGrayStrategyVersion();
        if (forceRollback || gray == null || gray.isBlank()) {
            baselineAsks.incrementAndGet();
            return baseline;
        }
        if (!uidInGray(uid, cfg.getGrayStrategyUidTails())) {
            baselineAsks.incrementAndGet();
            return baseline;
        }
        grayAsks.incrementAndGet();
        maybeAutoRollback(cfg);
        return forceRollback ? baseline : gray.trim();
    }

    public void recordOutcome(String strategyVersion, String source) {
        LunarCoreProperties.AiAssistProperties cfg = properties.getAiAssist();
        String gray = cfg.getGrayStrategyVersion();
        if (gray == null || gray.isBlank() || strategyVersion == null) {
            return;
        }
        if (!gray.trim().equals(strategyVersion.trim())) {
            return;
        }
        if (source != null && (source.contains("fallback") || source.contains("error") || "rule".equals(source))) {
            grayFallbacks.incrementAndGet();
            maybeAutoRollback(cfg);
        }
    }

    public Map<String, Object> snapshot() {
        return Map.of(
                "forceRollback", forceRollback,
                "baselineAsks", baselineAsks.get(),
                "grayAsks", grayAsks.get(),
                "grayFallbacks", grayFallbacks.get());
    }

    /** 测试/运维手动清除回滚标记。 */
    public void clearRollback() {
        forceRollback = false;
    }

    private void maybeAutoRollback(LunarCoreProperties.AiAssistProperties cfg) {
        long asks = grayAsks.get();
        if (asks < 20) {
            return;
        }
        double rate = grayFallbacks.get() * 1.0 / asks;
        if (rate >= Math.max(0.05, cfg.getGrayAutoRollbackFallbackRate())) {
            forceRollback = true;
        }
    }

    private static boolean uidInGray(long uid, String tailsCsv) {
        if (tailsCsv == null || tailsCsv.isBlank()) {
            return false;
        }
        int tail = (int) Math.floorMod(uid, 10L);
        for (String part : tailsCsv.split(",")) {
            String p = part.trim();
            if (p.isEmpty()) {
                continue;
            }
            try {
                if (Integer.parseInt(p) == tail) {
                    return true;
                }
            } catch (NumberFormatException ignored) {
                // skip
            }
        }
        return false;
    }

    private static String emptyTo(String v, String dft) {
        return v == null || v.isBlank() ? dft : v.trim();
    }

    /** 按策略版本选择 system prompt 变体。 */
    public String systemPrompt(String strategyVersion, String locale) {
        boolean en = locale != null && locale.toLowerCase(Locale.ROOT).startsWith("en");
        if (strategyVersion != null && strategyVersion.toLowerCase(Locale.ROOT).contains("concise")) {
            return en
                    ? "You are the official game assistant. Answer briefly with actionable tips only. No recharge inducement."
                    : "你是官方游戏助手。请用更短的条目式建议回答，禁止充值诱导，不确定时引导打开官方界面。";
        }
        return en ? AssistPromptTemplates.SYSTEM_PROMPT_EN : AssistPromptTemplates.SYSTEM_PROMPT;
    }
}
