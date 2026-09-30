package cn.itcast.demo.mylunarcore.assist;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("AI 助手站内摘要")
class AssistInlineSummaryServiceTest {

    @Test
    @DisplayName("摘要不超过 200 字且去掉 URL")
    void clipAndStripUrls() {
        String longAnswer = "要点：" + "打弱点破盾。".repeat(80)
                + "\nhttps://www.bilibili.com/video/x\n相关攻略（主流平台）：";
        AssistInlineSummaryService.InlineSummary s =
                new AssistInlineSummaryService().summarize(longAnswer, "深渊怎么打", List.of());
        assertTrue(s.text().length() <= AssistInlineSummaryService.MAX_CHARS);
        assertFalse(s.text().contains("https://"));
    }

    @Test
    @DisplayName("外链动作应改写为站内 WebView")
    void toInAppRewritesAction() {
        AssistMediaLink raw = new AssistMediaLink("1", "t", "bilibili", "video",
                "https://bilibili.com/x", "OPEN_VIDEO", true);
        AssistMediaLink inApp = AssistInlineSummaryService.toInApp(raw);
        assertEquals("OPEN_VIDEO_INLINE", inApp.action());
        assertEquals("INLINE_WEBVIEW", inApp.renderMode());
    }
}
