package cn.itcast.demo.mylunarcore.assist;

import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 强制生成 ≤200 字精简攻略摘要：玩家不点链接也能理解要点，链接仅作深度参考。
 */
@Service
public class AssistInlineSummaryService {

    public static final int MAX_CHARS = 200;

    public record InlineSummary(String text, int maxChars, String source) {}

    public InlineSummary summarize(String answer, String question, List<AssistMediaLink> links) {
        String body = answer == null ? "" : answer.trim();
        if (body.isBlank()) {
            body = buildFallback(question, links);
            return new InlineSummary(clip(body), MAX_CHARS, "rule");
        }
        // 去掉已拼接的外链 URL 清单，保留文字要点
        String cleaned = stripUrlBlocks(body);
        if (cleaned.length() <= MAX_CHARS) {
            return new InlineSummary(cleaned, MAX_CHARS, "hybrid");
        }
        return new InlineSummary(clip(cleaned), MAX_CHARS, "rule");
    }

    private static String buildFallback(String question, List<AssistMediaLink> links) {
        String q = question == null || question.isBlank() ? "当前问题" : question.trim();
        String tip = "关于「" + clip(q, 40) + "」：优先完成当前任务目标，注意体力与阵容克制。";
        if (links != null && !links.isEmpty()) {
            tip += "深度参考见站内攻略浮层。";
        }
        return tip;
    }

    private static String stripUrlBlocks(String body) {
        String[] lines = body.split("\\R");
        StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            String t = line.trim();
            if (t.startsWith("http://") || t.startsWith("https://")) {
                continue;
            }
            if (t.contains("相关攻略") || t.contains("外链内容来自第三方")) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(t);
        }
        return sb.toString().trim();
    }

    static String clip(String s) {
        return clip(s, MAX_CHARS);
    }

    static String clip(String s, int max) {
        if (s == null) {
            return "";
        }
        String t = s.trim();
        if (t.length() <= max) {
            return t;
        }
        return t.substring(0, Math.max(0, max - 1)) + "…";
    }

    /** 将外链动作统一改为站内 WebView。 */
    public static AssistMediaLink toInApp(AssistMediaLink link) {
        if (link == null) {
            return null;
        }
        String action = link.action();
        if (action == null || action.isBlank()
                || "OPEN_EXTERNAL_LINK".equalsIgnoreCase(action)
                || "OPEN_VIDEO".equalsIgnoreCase(action)) {
            action = "video".equalsIgnoreCase(link.mediaType())
                    ? "OPEN_VIDEO_INLINE" : "OPEN_IN_APP_WEBVIEW";
        }
        return new AssistMediaLink(
                link.id(), link.title(), link.platform(), link.mediaType(),
                link.url(), action, link.curated(), "INLINE_WEBVIEW");
    }

    public static List<AssistMediaLink> toInAppList(List<AssistMediaLink> links) {
        if (links == null || links.isEmpty()) {
            return List.of();
        }
        return links.stream().map(AssistInlineSummaryService::toInApp).toList();
    }
}
