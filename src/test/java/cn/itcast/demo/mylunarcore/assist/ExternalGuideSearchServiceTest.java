package cn.itcast.demo.mylunarcore.assist;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ExternalGuideSearchService 主流平台攻略检索")
class ExternalGuideSearchServiceTest {

    @Test
    @DisplayName("攻略意图应返回精选或平台搜索链接")
    void guideIntentShouldReturnLinks() {
        ExternalGuideCatalogRepository repo = mock(ExternalGuideCatalogRepository.class);
        when(repo.current()).thenReturn(sampleCatalog());
        ExternalGuideSearchService service = new ExternalGuideSearchService(repo);

        List<AssistMediaLink> links = service.search("忘却之庭怎么打 有没有攻略视频", "abyss");
        assertFalse(links.isEmpty());
        assertTrue(links.stream().anyMatch(l -> l.url().contains("bilibili") || l.url().contains("douyin")
                || l.url().contains("mihoyo")));
        assertTrue(links.stream().allMatch(l -> l.url().startsWith("https://")));
    }

    @Test
    @DisplayName("非攻略意图不应返回外链")
    void nonGuideIntentShouldReturnEmpty() {
        ExternalGuideCatalogRepository repo = mock(ExternalGuideCatalogRepository.class);
        when(repo.current()).thenReturn(sampleCatalog());
        ExternalGuideSearchService service = new ExternalGuideSearchService(repo);

        assertTrue(service.search("今天天气怎么样", "general").isEmpty());
    }

    @Test
    @DisplayName("非白名单域名应被过滤")
    void disallowedHostShouldBeFiltered() {
        ExternalGuideCatalogRepository repo = mock(ExternalGuideCatalogRepository.class);
        when(repo.current()).thenReturn(new ExternalGuideCatalogConfig(
                "1", true, 3, "测试游戏",
                List.of("www.bilibili.com"),
                List.of("攻略"),
                List.of(new ExternalGuideCatalogConfig.PlatformDef(
                        "evil", "Evil", "https://evil.example/search?q={query}", true)),
                List.of(new ExternalGuideCatalogConfig.GuideEntry(
                        "bad", "坏链", "evil", "video", "https://evil.example/v/1",
                        "general", List.of("攻略"), 99, false))));
        ExternalGuideSearchService service = new ExternalGuideSearchService(repo);
        List<AssistMediaLink> links = service.search("给我攻略", "general");
        assertTrue(links.isEmpty());
    }

    private static ExternalGuideCatalogConfig sampleCatalog() {
        return new ExternalGuideCatalogConfig(
                "1.1.0", true, 3, "崩坏星穹铁道",
                List.of("www.bilibili.com", "search.bilibili.com", "www.douyin.com", "sr.mihoyo.com"),
                List.of("攻略", "怎么打", "视频", "通关"),
                List.of(
                        new ExternalGuideCatalogConfig.PlatformDef(
                                "bilibili", "哔哩哔哩",
                                "https://search.bilibili.com/all?keyword={query}", true),
                        new ExternalGuideCatalogConfig.PlatformDef(
                                "douyin", "抖音",
                                "https://www.douyin.com/search/{query}", true),
                        new ExternalGuideCatalogConfig.PlatformDef(
                                "official", "官方网站",
                                "https://sr.mihoyo.com/", true)),
                List.of(new ExternalGuideCatalogConfig.GuideEntry(
                        "eg_abyss", "深渊攻略", "bilibili", "video",
                        "https://search.bilibili.com/all?keyword=abyss",
                        "abyss", List.of("深渊", "忘却之庭", "攻略"), 20, false)));
    }
}
