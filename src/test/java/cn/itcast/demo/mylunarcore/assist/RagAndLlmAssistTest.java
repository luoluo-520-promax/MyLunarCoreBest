package cn.itcast.demo.mylunarcore.assist;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * RagKnowledgeService / LlmAssistGateway 测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code RagAndLlmAssistTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("RagKnowledgeService / LlmAssistGateway 测试")
class RagAndLlmAssistTest {

    private static final Logger log = LoggerFactory.getLogger(RagAndLlmAssistTest.class);

    /**
     * 验证点：中文二元组应能命中活动名。
     * <p>测试方法 {@code ragTokenizeShouldSupportChineseBigrams}：
     * <ul>
     *   <li>{@code assertTrue(tokens.contains("夏日"));}</li>
     *   <li>{@code assertTrue(tokens.contains("庆典"));}</li>
     * </ul>
     */
    @Test
    @DisplayName("中文二元组应能命中活动名")
    void ragTokenizeShouldSupportChineseBigrams() {
        Set<String> tokens = RagKnowledgeService.tokenize("夏日庆典怎么玩");
        log.info("中文分词校验: tokenCount={}, contains夏日={}, contains庆典={}",
                tokens.size(), tokens.contains("夏日"), tokens.contains("庆典"));
        assertTrue(tokens.contains("夏日"));
        assertTrue(tokens.contains("庆典"));
    }

    /**
     * 验证点：场景加权应优先返回同类型知识块。
     * <p>测试方法 {@code ragSearchShouldPreferSceneType}：
     * <ul>
     *   <li>{@code assertEquals(1, hit.size());}</li>
     *   <li>{@code assertEquals("activity:1", hit.get(0).id());}</li>
     *   <li>{@code assertTrue(empty.isEmpty());}</li>
     *   <li>{@code assertEquals(2, rag.chunkCount());}</li>
     * </ul>
     */
    @Test
    @DisplayName("场景加权应优先返回同类型知识块")
    @SuppressWarnings("unchecked")
    void ragSearchShouldPreferSceneType() throws Exception {
        LunarCoreProperties props = new LunarCoreProperties();
        props.getAiAssist().setRagMinScore(0.5);
        RagKnowledgeService rag = new RagKnowledgeService(null, null, null, null, null, props, null) {
            @Override
            public synchronized void rebuild() {
            }
        };
        Field field = RagKnowledgeService.class.getDeclaredField("chunks");
        field.setAccessible(true);
        CopyOnWriteArrayList<RagKnowledgeService.KnowledgeChunk> chunks =
                (CopyOnWriteArrayList<RagKnowledgeService.KnowledgeChunk>) field.get(rag);
        chunks.add(new RagKnowledgeService.KnowledgeChunk("quest:1", "quest", "任务 清理威胁"));
        chunks.add(new RagKnowledgeService.KnowledgeChunk("activity:1", "activity", "活动 夏日庆典 收集代币"));
        Field corpusSize = RagKnowledgeService.class.getDeclaredField("corpusSize");
        corpusSize.setAccessible(true);
        corpusSize.set(rag, 2);

        List<RagKnowledgeService.KnowledgeChunk> hit = rag.search("夏日庆典", "activity", 1);
        List<RagKnowledgeService.KnowledgeChunk> empty = rag.search("   ", "activity", 3);
        log.info("场景加权检索校验: topId={}, topType={}, emptySize={}, chunkCount={}",
                hit.get(0).id(), hit.get(0).type(), empty.size(), rag.chunkCount());
        assertEquals(1, hit.size());
        assertEquals("activity:1", hit.get(0).id());
        assertTrue(empty.isEmpty());
        assertEquals(2, rag.chunkCount());
    }

    /**
     * 验证点：OpenAI choices / answer 字段 / 纯文本应可解析。
     * <p>测试方法 {@code llmGatewayShouldParseMultipleResponseShapes}：
     * <ul>
     *   <li>{@code assertEquals("建议先做主线任务。", openAi);}</li>
     *   <li>{@code assertEquals("你好", answerField);}</li>
     *   <li>{@code assertEquals("纯文本回答", plain);}</li>
     *   <li>{@code assertEquals(null, badJson);}</li>
     * </ul>
     */
    @Test
    @DisplayName("OpenAI choices / answer 字段 / 纯文本应可解析")
    void llmGatewayShouldParseMultipleResponseShapes() throws Exception {
        LlmAssistGateway gateway = new LlmAssistGateway(new LunarCoreProperties());
        String openAi = gateway.extractAnswer("""
                {"choices":[{"message":{"role":"assistant","content":"建议先做主线任务。"}}]}
                """);
        String answerField = gateway.extractAnswer("{\"answer\":\"你好\"}");
        String plain = gateway.extractAnswer("纯文本回答");
        String badJson = gateway.extractAnswer("{\"choices\":[]}");
        log.info("LLM 响应解析校验: openAi={}, answerField={}, plain={}, badJson={}",
                openAi, answerField, plain, badJson);
        assertEquals("建议先做主线任务。", openAi);
        assertEquals("你好", answerField);
        assertEquals("纯文本回答", plain);
        assertEquals(null, badJson);
    }

    /**
     * 验证点：LLM 未启用或无 endpoint 时应返回 empty。
     * <p>测试方法 {@code llmAskShouldReturnEmptyWhenDisabled}：
     * <ul>
     *   <li>{@code assertTrue(disabled.isEmpty());}</li>
     *   <li>{@code assertTrue(noEndpoint.isEmpty());}</li>
     * </ul>
     */
    @Test
    @DisplayName("LLM 未启用或无 endpoint 时应返回 empty")
    void llmAskShouldReturnEmptyWhenDisabled() {
        LunarCoreProperties props = new LunarCoreProperties();
        props.getAiAssist().setEnabled(true);
        props.getAiAssist().setLlmEnabled(false);
        LlmAssistGateway gateway = new LlmAssistGateway(props);
        var disabled = gateway.ask("你好", java.util.Map.of(), List.of());

        props.getAiAssist().setLlmEnabled(true);
        props.getAiAssist().setLlmEndpoint("");
        var noEndpoint = gateway.ask("你好", java.util.Map.of(), List.of());

        log.info("LLM 降级空值校验: disabledPresent={}, noEndpointPresent={}",
                disabled.isPresent(), noEndpoint.isPresent());
        assertTrue(disabled.isEmpty());
        assertTrue(noEndpoint.isEmpty());
    }
}
