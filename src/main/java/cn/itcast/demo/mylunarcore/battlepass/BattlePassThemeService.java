package cn.itcast.demo.mylunarcore.battlepass;

import cn.itcast.demo.mylunarcore.activity.VersionThemeService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 战令主题化：每赛季关联版本主题奖励与剧情任务，随 {@link VersionThemeService} 动态刷新。
 */
@Service
public class BattlePassThemeService {

    public record ThemePassBinding(String themeId, int seasonId, String storyQuestTag,
                                   String premiumSkinKey, boolean refreshed) {}

    private final VersionThemeService themeService;
    private final BattlePassService battlePassService;
    private final AtomicReference<ThemePassBinding> binding = new AtomicReference<>();

    public BattlePassThemeService(ObjectProvider<VersionThemeService> themeProvider,
                                  ObjectProvider<BattlePassService> passProvider) {
        this.themeService = themeProvider == null ? null : themeProvider.getIfAvailable();
        this.battlePassService = passProvider == null ? null : passProvider.getIfAvailable();
    }

    public ThemePassBinding refresh() {
        if (themeService == null) {
            ThemePassBinding def = new ThemePassBinding("1.0", 1, "story_theme_1_0", "skin_bp_1_0", true);
            binding.set(def);
            return def;
        }
        VersionThemeService.ThemeView view = themeService.currentView();
        VersionThemeService.ThemeConfig cur = view.current();
        String themeId = cur == null ? "1.0" : cur.themeId();
        int seasonId = battlePassService == null ? 1 : hashSeason(themeId);
        ThemePassBinding next = new ThemePassBinding(
                themeId,
                seasonId,
                "story_theme_" + themeId.replace('.', '_'),
                "skin_bp_" + themeId.replace('.', '_'),
                true);
        binding.set(next);
        return next;
    }

    public ThemePassBinding current() {
        ThemePassBinding cur = binding.get();
        return cur == null ? refresh() : cur;
    }

    public Map<String, Object> status() {
        ThemePassBinding b = current();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("themeId", b.themeId());
        m.put("seasonId", b.seasonId());
        m.put("storyQuestTag", b.storyQuestTag());
        m.put("premiumSkinKey", b.premiumSkinKey());
        return m;
    }

    private static int hashSeason(String themeId) {
        int h = themeId == null ? 1 : Math.abs(themeId.hashCode() % 10_000);
        return Math.max(1, h);
    }
}
