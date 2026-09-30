package cn.itcast.demo.mylunarcore.activity;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * {@link ActivityTemplateService} 模板玩法单测：转盘抽奖、拼图收集、积分兑换。
 * JdbcTemplate 使用 mock，进度主要落在服务内存态，验证业务规则而非落库。
 */
@DisplayName("活动模板：转盘/拼图/积分")
class ActivityTemplateServiceTest {

    // 每测例共享同一服务实例；mock JDBC 避免真实库依赖
    private final ActivityTemplateService svc = new ActivityTemplateService(
            mock(JdbcTemplate.class),
            mock(ActivityScriptEngine.class),
            new ActivityCircuitBreakerService());

    /**
     * 转盘权重 [100,0,0] 时必然落到下标 0，奖励文案应为 "A"。
     * activityId=5000808、playerId=1 仅作键，不影响权重逻辑。
     */
    @Test
    @DisplayName("转盘应按权重返回奖励")
    void wheelSpin() {
        ActivityTemplateService.OpResult r = svc.spinWheel(1, 5000808,
                List.of(100, 0, 0), List.of("A", "B", "C"));
        assertTrue(r.success());
        assertEquals("A", r.payload().get("reward")); // 权重全压在第一项
    }

    /**
     * 拼图共 3 片：先收集 0/1/2 各一次，再重复收集第 0 片时应标记 complete=true
     *（集齐后的再次操作仍成功并带完成标志）。
     */
    @Test
    @DisplayName("拼图集齐应标记 complete")
    void puzzleComplete() {
        for (int i = 0; i < 3; i++) {
            svc.collectPuzzlePiece(1, 9, i, 3); // player=1, activity=9, pieceIndex=i, total=3
        }
        ActivityTemplateService.OpResult r = svc.collectPuzzlePiece(1, 9, 0, 3);
        assertTrue(r.success());
        assertEquals(true, r.payload().get("complete"));
    }

    /**
     * 积分兑换：余额不足返回 retcode=2；addPoints 100 后再兑 50 应成功，余额剩 50。
     */
    @Test
    @DisplayName("积分不足应兑换失败")
    void exchangeInsufficient() {
        assertEquals(2, svc.exchangePoints(1, 10, 50, "p1").retcode()); // 无积分时失败
        svc.addPoints(1, 10, 100);
        assertTrue(svc.exchangePoints(1, 10, 50, "p1").success());
        assertEquals(50, svc.getPoints(1, 10)); // 100-50
    }
}
