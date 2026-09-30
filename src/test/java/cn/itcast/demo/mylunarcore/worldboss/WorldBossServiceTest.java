package cn.itcast.demo.mylunarcore.worldboss;

import cn.itcast.demo.mylunarcore.common.ClusterJobLock;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * 世界BOSS 全服血量与日报限。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code WorldBossServiceTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("世界BOSS 全服血量与日报限")
class WorldBossServiceTest {

    private WorldBossService service;

    @BeforeEach
    void setUp() {
        service = new WorldBossService(mock(JdbcTemplate.class), new ObjectMapper(), mock(ClusterJobLock.class));
        service.init();
    }

    /**
     * 验证点：挑战应扣血并记伤害。
     * <p>测试方法 {@code challengeAppliesDamage}：
     * <ul>
     *   <li>{@code assertTrue(r.success());}</li>
     *   <li>{@code assertTrue(r.damageApplied() > 0);}</li>
     *   <li>{@code assertTrue(r.remainHp() < before.maxHp());}</li>
     *   <li>{@code assertEquals(1, service.topRanks(5).size());}</li>
     * </ul>
     */
    @Test
    @DisplayName("挑战应扣血并记伤害")
    void challengeAppliesDamage() {
        WorldBossService.BossInfo before = service.info();
        WorldBossService.ChallengeResult r = service.challenge(1001, 50_000);
        assertTrue(r.success());
        assertTrue(r.damageApplied() > 0);
        assertTrue(r.remainHp() < before.maxHp());
        assertEquals(1, service.topRanks(5).size());
    }

    /**
     * 验证点：超过每日次数应拒绝。
     * <p>测试方法 {@code dailyLimitBlocks}：
     * <ul>
     *   <li>{@code assertTrue(service.challenge(2002, 1000).success());}</li>
     *   <li>{@code assertEquals(2, blocked.retcode());}</li>
     * </ul>
     */
    @Test
    @DisplayName("超过每日次数应拒绝")
    void dailyLimitBlocks() {
        for (int i = 0; i < service.info().dailyLimit(); i++) {
            assertTrue(service.challenge(2002, 1000).success());
        }
        WorldBossService.ChallengeResult blocked = service.challenge(2002, 1000);
        assertEquals(2, blocked.retcode());
    }
}
