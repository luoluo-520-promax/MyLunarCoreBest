package cn.itcast.demo.mylunarcore.assist;

/**
 * 助手答复附带的攻略媒体条目。
 * <p>默认站内 WebView 浮层渲染；链接仅作深度参考，禁止强制跳转外部浏览器。
 */
public record AssistMediaLink(
        String id,
        String title,
        String platform,
        String mediaType,
        String url,
        String action,
        boolean curated,
        String renderMode
) {
    public AssistMediaLink(
            String id,
            String title,
            String platform,
            String mediaType,
            String url,
            String action,
            boolean curated) {
        this(id, title, platform, mediaType, url, action, curated, "INLINE_WEBVIEW");
    }

    public AssistMediaLink {
        id = id == null ? "" : id;
        title = title == null ? "" : title;
        platform = platform == null ? "" : platform;
        mediaType = mediaType == null ? "link" : mediaType;
        url = url == null ? "" : url;
        if (action == null || action.isBlank()) {
            action = "video".equalsIgnoreCase(mediaType) ? "OPEN_VIDEO_INLINE" : "OPEN_IN_APP_WEBVIEW";
        }
        renderMode = renderMode == null || renderMode.isBlank() ? "INLINE_WEBVIEW" : renderMode;
    }
}
