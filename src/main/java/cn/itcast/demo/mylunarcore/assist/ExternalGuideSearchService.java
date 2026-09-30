package cn.itcast.demo.mylunarcore.assist;

import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.List;

/**
 * 从主流平台目录检索游戏攻略：优先运营精选，不足时补平台搜索深链。
 * <p>URL 仅作深度参考；客户端必须用站内 WebView 浮层渲染，禁止强制跳出浏览器。
 */
@Service
public class ExternalGuideSearchService {

    private final ExternalGuideCatalogRepository catalogRepository;

    public ExternalGuideSearchService(ExternalGuideCatalogRepository catalogRepository) {
        this.catalogRepository = catalogRepository;
    }

    /**
     * 按问题与场景检索外链攻略；无攻略意图时返回空列表。
     */
    public List<AssistMediaLink> search(String question, String scene) {
        ExternalGuideCatalogConfig catalog = catalogRepository.current();
        if (catalog == null || !Boolean.TRUE.equals(catalog.enabled())) {
            return List.of();
        }
        String q = question == null ? "" : question.trim();
        if (q.isEmpty() || !looksLikeGuideIntent(q, catalog)) {
            return List.of();
        }
        String sceneSafe = scene == null ? "" : scene.trim().toLowerCase(Locale.ROOT);
        int limit = catalog.maxResults() == null ? 3 : catalog.maxResults();

        List<ScoredGuide> scored = new ArrayList<>();
        for (ExternalGuideCatalogConfig.GuideEntry entry : catalog.guides()) {
            if (entry == null || entry.url().isBlank()) {
                continue;
            }
            int score = scoreEntry(entry, q, sceneSafe);
            if (score <= 0) {
                continue;
            }
            scored.add(new ScoredGuide(entry, score));
        }
        scored.sort(Comparator
                .comparingInt(ScoredGuide::score).reversed()
                .thenComparing((ScoredGuide s) -> s.entry().priority() == null ? 0 : s.entry().priority(),
                        Comparator.reverseOrder()));

        List<AssistMediaLink> out = new ArrayList<>();
        Set<String> seenUrls = new LinkedHashSet<>();
        for (ScoredGuide sg : scored) {
            if (out.size() >= limit) {
                break;
            }
            AssistMediaLink link = toCuratedLink(sg.entry());
            if (!isAllowed(link.url(), catalog) || !seenUrls.add(normalizeUrl(link.url()))) {
                continue;
            }
            out.add(link);
        }

        if (out.size() < limit) {
            for (AssistMediaLink searchLink : buildPlatformSearchLinks(q, catalog)) {
                if (out.size() >= limit) {
                    break;
                }
                if (!isAllowed(searchLink.url(), catalog) || !seenUrls.add(normalizeUrl(searchLink.url()))) {
                    continue;
                }
                out.add(searchLink);
            }
        }
        return List.copyOf(out);
    }

    /** 将命中链接拼成聊天可读摘要（不附原始 URL，避免诱导跳出游戏）。 */
    public String formatAnswerSuffix(List<AssistMediaLink> links) {
        if (links == null || links.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("深度参考见站内攻略浮层：");
        int i = 1;
        for (AssistMediaLink link : links) {
            if (i > 1) {
                sb.append('、');
            }
            sb.append(link.platform().isBlank() ? "link" : link.platform());
            if (!link.title().isBlank()) {
                sb.append('《').append(link.title()).append('》');
            }
            i++;
        }
        sb.append("。");
        return sb.toString();
    }

    boolean looksLikeGuideIntent(String question, ExternalGuideCatalogConfig catalog) {
        String q = question.toLowerCase(Locale.ROOT);
        List<String> keywords = catalog.guideIntentKeywords();
        if (keywords.isEmpty()) {
            return q.contains("攻略") || q.contains("教程") || q.contains("视频")
                    || q.contains("guide") || q.contains("怎么打") || q.contains("怎么过");
        }
        for (String kw : keywords) {
            if (kw != null && !kw.isBlank() && q.contains(kw.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static int scoreEntry(ExternalGuideCatalogConfig.GuideEntry entry, String question, String scene) {
        String q = question.toLowerCase(Locale.ROOT);
        int score = 0;
        if (entry.scene() != null && !entry.scene().isBlank()
                && scene.equalsIgnoreCase(entry.scene())) {
            score += 8;
        }
        for (String kw : entry.keywords()) {
            if (kw == null || kw.isBlank()) {
                continue;
            }
            String k = kw.toLowerCase(Locale.ROOT);
            if (q.contains(k)) {
                score += 5 + Math.min(4, k.length() / 2);
            }
        }
        String title = entry.title() == null ? "" : entry.title().toLowerCase(Locale.ROOT);
        for (String token : q.split("\\s+|，|。|？|！|,|\\.|\\?|!")) {
            if (token.length() >= 2 && title.contains(token.toLowerCase(Locale.ROOT))) {
                score += 2;
            }
        }
        if (Boolean.TRUE.equals(entry.official())) {
            score += 3;
        }
        score += Math.max(0, entry.priority() == null ? 0 : entry.priority() / 5);
        return score;
    }

    private static AssistMediaLink toCuratedLink(ExternalGuideCatalogConfig.GuideEntry entry) {
        String mediaType = entry.mediaType();
        String action = "video".equalsIgnoreCase(mediaType) ? "OPEN_VIDEO_INLINE" : "OPEN_IN_APP_WEBVIEW";
        return new AssistMediaLink(
                entry.id(),
                entry.title(),
                entry.platform(),
                mediaType,
                entry.url().trim(),
                action,
                true,
                "INLINE_WEBVIEW");
    }

    private List<AssistMediaLink> buildPlatformSearchLinks(String question, ExternalGuideCatalogConfig catalog) {
        String query = buildSearchQuery(question, catalog.gameSearchPrefix());
        // 路径型模板（如抖音 /search/{query}）需要 %20；查询串型同样可接受。
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8).replace("+", "%20");
        List<AssistMediaLink> links = new ArrayList<>();
        for (ExternalGuideCatalogConfig.PlatformDef platform : catalog.platforms()) {
            if (platform == null || !Boolean.TRUE.equals(platform.enabled())
                    || platform.searchUrlTemplate().isBlank()) {
                continue;
            }
            String url = platform.searchUrlTemplate().replace("{query}", encoded);
            links.add(new AssistMediaLink(
                    "search:" + platform.id(),
                    platform.displayName() + "搜索：" + query,
                    platform.id(),
                    "search",
                    url,
                    "OPEN_IN_APP_WEBVIEW",
                    false,
                    "INLINE_WEBVIEW"));
        }
        return links;
    }

    static String buildSearchQuery(String question, String gamePrefix) {
        String q = question == null ? "" : question.trim();
        String prefix = gamePrefix == null ? "" : gamePrefix.trim();
        if (prefix.isEmpty()) {
            return q;
        }
        if (q.contains(prefix)) {
            return q;
        }
        return prefix + " " + q;
    }

    boolean isAllowed(String url, ExternalGuideCatalogConfig catalog) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            URI uri = URI.create(url.trim());
            String scheme = uri.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("http"))) {
                return false;
            }
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                return false;
            }
            String h = host.toLowerCase(Locale.ROOT);
            for (String allowed : catalog.allowedHosts()) {
                if (allowed == null || allowed.isBlank()) {
                    continue;
                }
                String a = allowed.toLowerCase(Locale.ROOT).trim();
                if (h.equals(a) || h.endsWith("." + a)) {
                    return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private static String normalizeUrl(String url) {
        return url == null ? "" : url.trim().toLowerCase(Locale.ROOT);
    }

    private record ScoredGuide(ExternalGuideCatalogConfig.GuideEntry entry, int score) {
    }
}
