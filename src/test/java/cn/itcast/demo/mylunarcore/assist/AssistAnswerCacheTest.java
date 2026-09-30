package cn.itcast.demo.mylunarcore.assist;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AssistAnswerCache} 测试：问题归一与 cacheKey、命中后 source 追加 -cache、
 * 按 scene 失效，以及 TTL=0 / 非法 uid / 非成功答案跳过入缓存。
 */
@DisplayName("AssistAnswerCache 回答缓存测试")
class AssistAnswerCacheTest {

    private static final Logger log = LoggerFactory.getLogger(AssistAnswerCacheTest.class);

    /**
     * normalizeQuestion 合并空白并把「卡池」类说法归一到含「抽卡」；
     * cacheKey 对 scene 大小写不敏感，同义问句应得到相同 key（含 |s=gacha|）。
     */
    @Test
    @DisplayName("normalize 应去空白并做同义词归一")
    void normalizeShouldCollapseWhitespaceAndSynonyms() {
        String n1 = AssistAnswerCache.normalizeQuestion("  卡 池 怎么 抽  ");
        String n2 = AssistAnswerCache.normalizeQuestion("抽卡怎么抽");
        String key1 = AssistAnswerCache.cacheKey(1001L, "Gacha", "保底进度还有多少");
        String key2 = AssistAnswerCache.cacheKey(1001L, "gacha", "保底还有多少");
        log.info("问题归一校验: n1={}, n2={}, sameNorm={}, key1={}, key2={}, sameKey={}",
                n1, n2, n1.equals(n2), key1, key2, key1.equals(key2));
        assertTrue(n1.contains("抽卡"));
        assertTrue(key1.contains("|s=gacha|"));
        assertEquals(key1, key2);
    }

    /**
     * put 成功答案后同 uid/scene/问题应命中，source 变为 rule-cache；
     * 不同 scene 不命中；invalidateScene("quest") 后原条目消失。
     */
    @Test
    @DisplayName("命中缓存时应追加 -cache，并支持按 scene 失效")
    void putGetAndInvalidateSceneShouldWork() {
        LunarCoreProperties props = new LunarCoreProperties();
        props.getAiAssist().setAnswerCacheTtlSeconds(120);
        props.getAiAssist().setAnswerCacheMaxSize(100);
        AssistAnswerCache cache = new AssistAnswerCache(props);
        cache.init();

        AssistAnswer answer = AssistAnswer.of(0, "先做主线", "rule", List.of(), List.of("quest:1"));
        cache.put(1001L, "quest", "下一步做什么", answer);
        Optional<AssistAnswer> hit = cache.get(1001L, "quest", "下一步做什么");
        Optional<AssistAnswer> miss = cache.get(1001L, "activity", "下一步做什么");

        log.info("缓存读写校验: hitPresent={}, hitSource={}, hitAnswer={}, missPresent={}, size={}",
                hit.isPresent(),
                hit.map(AssistAnswer::source).orElse(null),
                hit.map(AssistAnswer::answer).orElse(null),
                miss.isPresent(),
                cache.estimatedSize());
        assertTrue(hit.isPresent());
        assertEquals("rule-cache", hit.get().source());
        assertEquals("先做主线", hit.get().answer());
        assertTrue(miss.isEmpty());

        cache.invalidateScene("quest");
        Optional<AssistAnswer> after = cache.get(1001L, "quest", "下一步做什么");
        log.info("场景失效校验: afterPresent={}, sizeAfter={}", after.isPresent(), cache.estimatedSize());
        assertTrue(after.isEmpty());
    }

    /**
     * TTL=0：put 后 get 仍空；uid=0、retcode≠0、source=blocked 均不应写入可命中条目。
     */
    @Test
    @DisplayName("TTL=0 或非法 uid / 非成功答案不应入缓存")
    void disabledOrInvalidShouldSkipCache() {
        LunarCoreProperties props = new LunarCoreProperties();
        props.getAiAssist().setAnswerCacheTtlSeconds(0);
        AssistAnswerCache disabled = new AssistAnswerCache(props);
        disabled.init();
        disabled.put(1L, "general", "你好", AssistAnswer.of(0, "hi", "rule", List.of(), List.of()));
        Optional<AssistAnswer> disabledHit = disabled.get(1L, "general", "你好");

        props.getAiAssist().setAnswerCacheTtlSeconds(60);
        AssistAnswerCache enabled = new AssistAnswerCache(props);
        enabled.init();
        enabled.put(0L, "general", "你好", AssistAnswer.of(0, "hi", "rule", List.of(), List.of()));
        enabled.put(1L, "general", "你好", AssistAnswer.of(5, "err", "error", List.of(), List.of()));
        enabled.put(1L, "general", "你好", AssistAnswer.of(0, "blocked", "blocked", List.of(), List.of()));
        Optional<AssistAnswer> skipHit = enabled.get(1L, "general", "你好");

        log.info("缓存跳过校验: disabledHit={}, skipHit={}, blockedOrErrorSkipped={}",
                disabledHit.isPresent(), skipHit.isPresent(), skipHit.isEmpty());
        assertTrue(disabledHit.isEmpty());
        assertTrue(skipHit.isEmpty());
    }
}
