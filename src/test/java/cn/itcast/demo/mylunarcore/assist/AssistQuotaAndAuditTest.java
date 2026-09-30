package cn.itcast.demo.mylunarcore.assist;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AssistQuotaLimiter} 分钟级配额与 {@link AssistAuditService} 按 source 计数。
 */
@DisplayName("AssistQuotaLimiter / AssistAuditService 测试")
class AssistQuotaAndAuditTest {

    private static final Logger log = LoggerFactory.getLogger(AssistQuotaAndAuditTest.class);

    /**
     * llmPerUidPerMinute=2：uid=0 拒绝；同一 uid 第 3 次拒绝；其它 uid 仍可获取。
     */
    @Test
    @DisplayName("非法 uid 应拒绝；合法 uid 在配额内可通过")
    void quotaLimiterShouldRejectInvalidUidAndAllowWithinLimit() {
        LunarCoreProperties props = new LunarCoreProperties();
        props.getAiAssist().setLlmPerUidPerMinute(2);
        props.getAiAssist().setDailyLlmBudget(100);
        AssistQuotaLimiter limiter = new AssistQuotaLimiter(props);

        boolean invalid = limiter.tryAcquire(0);
        boolean first = limiter.tryAcquire(1001L);
        boolean second = limiter.tryAcquire(1001L);
        boolean third = limiter.tryAcquire(1001L); // 超分钟配额
        boolean otherUid = limiter.tryAcquire(1002L);

        log.info("配额限流校验: invalidUid={}, uid1001_1={}, uid1001_2={}, uid1001_3={}, uid1002_1={}",
                invalid, first, second, third, otherUid);
        assertFalse(invalid);
        assertTrue(first);
        assertTrue(second);
        assertFalse(third);
        assertTrue(otherUid);
    }

    /**
     * coach 配额 2/分钟、llm 1/分钟：耗尽 coach 不影响 llm 第一次成功，llm 第二次失败。
     */
    @Test
    @DisplayName("coach 与 llm 配额应隔离，互不影响")
    void coachAndLlmQuotasShouldBeIndependent() {
        LunarCoreProperties props = new LunarCoreProperties();
        props.getAiAssist().setCoachPerUidPerMinute(2);
        props.getAiAssist().setLlmPerUidPerMinute(1);
        props.getAiAssist().setDailyLlmBudget(10);
        AssistQuotaLimiter limiter = new AssistQuotaLimiter(props);

        AssistQuotaLimiter.AcquireResult coach1 = limiter.tryAcquire(2001L, AssistQuotaLimiter.QuotaKind.COACH);
        AssistQuotaLimiter.AcquireResult coach2 = limiter.tryAcquire(2001L, AssistQuotaLimiter.QuotaKind.COACH);
        AssistQuotaLimiter.AcquireResult coach3 = limiter.tryAcquire(2001L, AssistQuotaLimiter.QuotaKind.COACH);
        AssistQuotaLimiter.AcquireResult llm1 = limiter.tryAcquire(2001L, AssistQuotaLimiter.QuotaKind.LLM);
        AssistQuotaLimiter.AcquireResult llm2 = limiter.tryAcquire(2001L, AssistQuotaLimiter.QuotaKind.LLM);

        log.info("coach/llm 隔离校验: coach1={}, coach2={}, coach3={}, llm1={}, llm2={}, rejectCount={}",
                coach1.allowed(), coach2.allowed(), coach3.allowed(),
                llm1.allowed(), llm2.allowed(), limiter.getRejectCount());
        assertTrue(coach1.allowed());
        assertTrue(coach2.allowed());
        assertFalse(coach3.allowed());
        assertTrue(llm1.allowed());
        assertFalse(llm2.allowed());
    }

    /**
     * 五次 record：ask=5；llm 命中 1；rule+fallback 计为 ruleFallback=2；blocked/error 各 1。
     */
    @Test
    @DisplayName("审计应按 source/retcode 累加计数")
    void auditShouldCountBySourceAndRetcode() {
        AssistAuditService audit = new AssistAuditService();
        audit.record(1001L, "quest", "llm", 0, "主线怎么做", "先推进主线");
        audit.record(1002L, "gacha", "rule", 0, "保底", "看官方说明");
        audit.record(1003L, "general", "fallback", 0, "？", "暂未匹配");
        audit.record(1004L, "mail", "blocked", 0, "别人账号", "无法回答");
        audit.record(1005L, "general", "error", 5, "x", "助手暂时不可用");

        log.info("审计计数校验: ask={}, llm={}, ruleFallback={}, blocked={}, error={}",
                audit.getAskCount(), audit.getLlmHitCount(), audit.getRuleFallbackCount(),
                audit.getBlockedCount(), audit.getErrorCount());
        assertEquals(5, audit.getAskCount());
        assertEquals(1, audit.getLlmHitCount());
        assertEquals(2, audit.getRuleFallbackCount());
        assertEquals(1, audit.getBlockedCount());
        assertEquals(1, audit.getErrorCount());
    }

    /**
     * source 带 -cache 后缀：计入 cacheHit，同时仍按 rule/llm 前缀计入对应桶。
     */
    @Test
    @DisplayName("缓存命中 source 应以 -cache 计入审计")
    void auditShouldCountCacheHits() {
        AssistAuditService audit = new AssistAuditService();
        audit.record(1L, "quest", "rule-cache", 0, "下一步", "先做主线");
        audit.record(1L, "quest", "llm-cache", 0, "保底", "看官方说明");
        log.info("缓存命中审计校验: ask={}, llm={}, ruleFallback={}, cacheHit={}",
                audit.getAskCount(), audit.getLlmHitCount(), audit.getRuleFallbackCount(),
                audit.getCacheHitCount());
        assertEquals(2, audit.getAskCount());
        assertEquals(1, audit.getLlmHitCount());
        assertEquals(1, audit.getRuleFallbackCount());
        assertEquals(2, audit.getCacheHitCount());
    }
}
