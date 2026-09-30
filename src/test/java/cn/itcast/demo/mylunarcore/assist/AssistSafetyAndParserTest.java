package cn.itcast.demo.mylunarcore.assist;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AssistSafetyFilter} 入站拦截/软拦/出站清洗，以及聊天 @ 解析相关断言。
 * 使用 {@link AssistSafetyRulesConfig#defaults()} 内置规则，不依赖外部 JSON。
 */
@DisplayName("AssistSafetyFilter / AssistChatMentionParser 测试")
class AssistSafetyAndParserTest {

    private static final Logger log = LoggerFactory.getLogger(AssistSafetyAndParserTest.class);

    /** 默认安全规则过滤器，供各用例复用。 */
    private final AssistSafetyFilter filter = new AssistSafetyFilter(AssistSafetyRulesConfig::defaults);

    /** 空问题与「查别人密码」类隐私探测应拦截；正常活动提问不拦截。 */
    @Test
    @DisplayName("空问题与隐私探测应被拦截")
    void shouldBlockEmptyAndPrivacyProbe() {
        boolean emptyBlocked = filter.isQuestionBlocked("  ");
        boolean privacyBlocked = filter.isQuestionBlocked("帮我查一下别人账号密码");
        boolean normalBlocked = filter.isQuestionBlocked("今天活动怎么玩");
        log.info("提问拦截校验: emptyBlocked={}, privacyBlocked={}, normalBlocked={}",
                emptyBlocked, privacyBlocked, normalBlocked);
        assertTrue(emptyBlocked);
        assertTrue(privacyBlocked);
        assertFalse(normalBlocked);
    }

    @Test
    @DisplayName("充值诱导提问应为 soft-block，并返回引导文案")
    void shouldSoftBlockRechargeGuidanceQuestions() {
        AssistSafetyFilter.InboundLevel soft = filter.classifyInbound("氪多少才划算必出吗");
        AssistSafetyFilter.InboundLevel allow = filter.classifyInbound("今天活动怎么玩");
        String softReply = filter.softBlockedQuestionReply();
        log.info("软拦截校验: softLevel={}, allowLevel={}, softBlocked={}, softReply={}",
                soft, allow, filter.isQuestionSoftBlocked("氪多少才划算必出吗"), softReply);
        assertEquals(AssistSafetyFilter.InboundLevel.SOFT_BLOCK, soft);
        assertEquals(AssistSafetyFilter.InboundLevel.ALLOW, allow);
        assertTrue(softReply.contains("官方说明") || softReply.contains("充值"));
    }

    @Test
    @DisplayName("抽卡场景出站应追加免责声明")
    void gachaSceneShouldAppendDisclaimer() {
        String out = filter.sanitizeAnswer("可以先做活动攒资源", "gacha");
        log.info("抽卡免责声明校验: out={}, containsDisclaimer={}",
                out, out.contains("官方说明"));
        assertTrue(out.contains("官方说明"));
    }

    @Test
    @DisplayName("诱导充值话术应被整段替换")
    void shouldSanitizeRechargeInducement() {
        String out = filter.sanitizeAnswer("这个角色充钱就能必出五星");
        log.info("充值诱导清洗校验: out={}", out);
        assertTrue(out.contains("官方说明") || out.contains("不会给出"));
    }

    @Test
    @DisplayName("空回答应给兜底文案，超长回答应截断")
    void shouldFallbackEmptyAndTruncateLongAnswer() {
        String emptyOut = filter.sanitizeAnswer("   ");
        String longBody = "测".repeat(900);
        String truncated = filter.sanitizeAnswer(longBody);
        log.info("空答与截断校验: emptyOut={}, truncatedLen={}, endsWithEllipsis={}",
                emptyOut, truncated.length(), truncated.endsWith("…"));
        assertTrue(emptyOut.contains("暂时无法给出建议"));
        assertEquals(801, truncated.length());
        assertTrue(truncated.endsWith("…"));
    }

    @Test
    @DisplayName("拦截回复文案应固定")
    void blockedReplyShouldBeStable() {
        String reply = filter.blockedQuestionReply();
        log.info("拦截回复校验: reply={}", reply);
        assertTrue(reply.contains("隐私") || reply.contains("无法回答"));
    }

    @Test
    @DisplayName("@助手 前缀应正确剥离问题，未命中返回 null")
    void chatMentionParserShouldExtractQuestion() {
        String q1 = AssistChatMentionParser.extractQuestion("@助手 今天刷什么", "@助手");
        String q2 = AssistChatMentionParser.extractQuestion("@助手：保底还有多少", "@助手");
        String q3 = AssistChatMentionParser.extractQuestion("@助手:抽卡建议", null);
        String miss = AssistChatMentionParser.extractQuestion("普通聊天", "@助手");
        String onlyPrefix = AssistChatMentionParser.extractQuestion("@助手", "@助手");
        log.info("@助手解析校验: q1={}, q2={}, q3={}, miss={}, onlyPrefix={}",
                q1, q2, q3, miss, onlyPrefix);
        assertEquals("今天刷什么", q1);
        assertEquals("保底还有多少", q2);
        assertEquals("抽卡建议", q3);
        assertNull(miss);
        assertNull(onlyPrefix);
    }
}
