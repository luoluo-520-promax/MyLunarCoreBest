package cn.itcast.demo.mylunarcore.assist;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * 主流平台外链攻略目录：运营精选条目 + 平台搜索深链模板。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExternalGuideCatalogConfig(
        String version,
        Boolean enabled,
        Integer maxResults,
        String gameSearchPrefix,
        List<String> allowedHosts,
        List<String> guideIntentKeywords,
        List<PlatformDef> platforms,
        List<GuideEntry> guides
) {
    public ExternalGuideCatalogConfig {
        version = version == null ? "0.0.0" : version;
        enabled = enabled == null || enabled;
        maxResults = maxResults == null || maxResults <= 0 ? 3 : Math.min(maxResults, 8);
        gameSearchPrefix = gameSearchPrefix == null ? "" : gameSearchPrefix.trim();
        allowedHosts = allowedHosts == null ? List.of() : List.copyOf(allowedHosts);
        guideIntentKeywords = guideIntentKeywords == null ? List.of() : List.copyOf(guideIntentKeywords);
        platforms = platforms == null ? List.of() : List.copyOf(platforms);
        guides = guides == null ? List.of() : List.copyOf(guides);
    }

    public static ExternalGuideCatalogConfig empty() {
        return new ExternalGuideCatalogConfig(
                "0.0.0", false, 3, "", List.of(), List.of(), List.of(), List.of());
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PlatformDef(
            String id,
            String displayName,
            String searchUrlTemplate,
            Boolean enabled
    ) {
        public PlatformDef {
            id = id == null ? "" : id;
            displayName = displayName == null ? id : displayName;
            searchUrlTemplate = searchUrlTemplate == null ? "" : searchUrlTemplate;
            enabled = enabled == null || enabled;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GuideEntry(
            String id,
            String title,
            String platform,
            String mediaType,
            String url,
            String scene,
            List<String> keywords,
            Integer priority,
            Boolean official
    ) {
        public GuideEntry {
            id = id == null ? "" : id;
            title = title == null ? "" : title;
            platform = platform == null ? "" : platform;
            mediaType = mediaType == null ? "video" : mediaType;
            url = url == null ? "" : url;
            scene = scene == null ? "" : scene;
            keywords = keywords == null ? List.of() : List.copyOf(keywords);
            priority = priority == null ? 0 : priority;
            official = official != null && official;
        }
    }
}
