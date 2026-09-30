package cn.itcast.demo.mylunarcore.privacy;

import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 隐私同意管理：按 locale 展示政策；不同意则仅访客模式（不可存档）。
 */
@Service
public class PrivacyConsentService {

    public enum Consent { UNKNOWN, ACCEPTED, DECLINED }

    public record ConsentView(Consent consent, String policyUrl, String locale, boolean guestOnly) {}

    private final Map<Integer, Consent> byPlayer = new ConcurrentHashMap<>();
    private final Map<Integer, String> localeByPlayer = new ConcurrentHashMap<>();

    public ConsentView presentOnLogin(int playerId, String locale) {
        String loc = normalizeLocale(locale);
        localeByPlayer.put(playerId, loc);
        Consent c = byPlayer.getOrDefault(playerId, Consent.UNKNOWN);
        return new ConsentView(c, policyUrlFor(loc), loc, c == Consent.DECLINED || c == Consent.UNKNOWN);
    }

    public ConsentView accept(int playerId) {
        byPlayer.put(playerId, Consent.ACCEPTED);
        String loc = localeByPlayer.getOrDefault(playerId, "en-US");
        return new ConsentView(Consent.ACCEPTED, policyUrlFor(loc), loc, false);
    }

    public ConsentView decline(int playerId) {
        byPlayer.put(playerId, Consent.DECLINED);
        String loc = localeByPlayer.getOrDefault(playerId, "en-US");
        return new ConsentView(Consent.DECLINED, policyUrlFor(loc), loc, true);
    }

    /** 未同意或拒绝 → 禁止存档。 */
    public boolean canPersist(int playerId) {
        return byPlayer.getOrDefault(playerId, Consent.UNKNOWN) == Consent.ACCEPTED;
    }

    public Consent consentOf(int playerId) {
        return byPlayer.getOrDefault(playerId, Consent.UNKNOWN);
    }

    private static String normalizeLocale(String locale) {
        if (locale == null || locale.isBlank()) {
            return "en-US";
        }
        return locale.replace('_', '-');
    }

    private static String policyUrlFor(String locale) {
        String lang = locale.toLowerCase(Locale.ROOT);
        if (lang.startsWith("zh")) {
            return "https://privacy.example/zh-CN/policy";
        }
        if (lang.startsWith("ja")) {
            return "https://privacy.example/ja-JP/policy";
        }
        if (lang.startsWith("ko")) {
            return "https://privacy.example/ko-KR/policy";
        }
        return "https://privacy.example/en-US/policy";
    }
}
