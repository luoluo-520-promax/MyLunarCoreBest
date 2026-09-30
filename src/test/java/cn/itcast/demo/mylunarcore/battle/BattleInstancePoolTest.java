package cn.itcast.demo.mylunarcore.battle;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("BattleInstancePool 配额与优先级调度")
class BattleInstancePoolTest {

    @Test
    @DisplayName("超额拒绝或排队")
    void admitRespectsQuota() {
        LunarCoreProperties props = new LunarCoreProperties();
        props.getGameLoop().setMaxActiveBattles(2);
        props.getGameLoop().setBattleQueueEnabled(false);
        BattleInstancePool pool = new BattleInstancePool(props);
        assertEquals(BattleInstancePool.AdmitResult.ADMITTED, pool.tryAdmit(1, BattleInstancePool.Priority.PLAYER_ACTIVE).result());
        assertEquals(BattleInstancePool.AdmitResult.ADMITTED, pool.tryAdmit(2, BattleInstancePool.Priority.ONLINE_AUTO).result());
        assertEquals(BattleInstancePool.AdmitResult.REJECTED, pool.tryAdmit(3, BattleInstancePool.Priority.OFFLINE_HOSTED).result());
        pool.release(1);
        assertEquals(BattleInstancePool.AdmitResult.ADMITTED, pool.tryAdmit(3, BattleInstancePool.Priority.PLAYER_ACTIVE).result());
    }

    @Test
    @DisplayName("优先推进玩家主动战局")
    void dispatchPriorityOrder() {
        LunarCoreProperties props = new LunarCoreProperties();
        props.getGameLoop().setMaxActiveBattles(10);
        props.getGameLoop().setBattleTickBudgetMs(1000);
        props.getGameLoop().setAutoBattleTickIntervalMs(0);
        props.getGameLoop().setHostedBattleTickIntervalMs(0);
        BattleInstancePool pool = new BattleInstancePool(props);
        pool.tryAdmit(1, BattleInstancePool.Priority.OFFLINE_HOSTED);
        pool.tryAdmit(2, BattleInstancePool.Priority.PLAYER_ACTIVE);

        BattleContext low = BattleContext.createNew(1, 1, 1, 1, 0, List.of());
        BattleContext high = BattleContext.createNew(2, 2, 1, 1, 0, List.of());
        List<Long> order = new ArrayList<>();
        pool.dispatchTick(List.of(low, high), System.currentTimeMillis(), ctx -> order.add(ctx.getBattleId()));
        assertEquals(List.of(2L, 1L), order);
    }
}
