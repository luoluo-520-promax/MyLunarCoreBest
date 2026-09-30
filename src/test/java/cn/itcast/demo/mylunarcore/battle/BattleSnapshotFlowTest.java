package cn.itcast.demo.mylunarcore.battle;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 开战注册 → 检查点快照 → 按玩家/战斗 ID 热恢复 → 结束清理。
 */
@DisplayName("战斗断线快照业务流程")
class BattleSnapshotFlowTest {

    private BattleSnapshotService snapshotService;
    private BattleManager battleManager;

    @BeforeEach
    void setUp() {
        LunarCoreProperties props = new LunarCoreProperties();
        props.getBattleSnapshot().setEnabled(true);
        props.getRedis().setEnabled(false);
        @SuppressWarnings("unchecked")
        ObjectProvider<StringRedisTemplate> redis = mock(ObjectProvider.class);
        when(redis.getIfAvailable()).thenReturn(null);
        snapshotService = new BattleSnapshotService(new ObjectMapper(), props, redis);
        battleManager = new BattleManager(snapshotService);
    }

    @Test
    @DisplayName("put/checkpoint 后可按 battleId 与 playerId 加载快照")
    void saveAndLoadByBattleAndPlayer() {
        BattleContext ctx = BattleTestFixtures.createContext(9001L, 42, BattleTestFixtures.singleWaveStage());
        battleManager.put(ctx);
        ctx.incrementTurn();
        battleManager.checkpoint(ctx);

        Optional<BattleSnapshot> byId = snapshotService.load(9001L);
        assertTrue(byId.isPresent());
        assertEquals(42, byId.get().playerId());
        assertEquals(2, byId.get().turn());

        Optional<BattleSnapshot> byPlayer = snapshotService.loadByPlayer(42);
        assertTrue(byPlayer.isPresent());
        assertEquals(9001L, byPlayer.get().battleId());
    }

    @Test
    @DisplayName("remove 后快照应消失")
    void removeClearsSnapshot() {
        BattleContext ctx = BattleTestFixtures.createContext(9002L, 7, BattleTestFixtures.singleWaveStage());
        battleManager.put(ctx);
        assertTrue(snapshotService.load(9002L).isPresent());
        battleManager.remove(9002L);
        assertTrue(snapshotService.load(9002L).isEmpty());
    }

    @Test
    @DisplayName("关闭快照开关时 save 为空操作")
    void disabledSnapshotIsNoop() {
        LunarCoreProperties props = new LunarCoreProperties();
        props.getBattleSnapshot().setEnabled(false);
        @SuppressWarnings("unchecked")
        ObjectProvider<StringRedisTemplate> redis = mock(ObjectProvider.class);
        BattleSnapshotService disabled = new BattleSnapshotService(new ObjectMapper(), props, redis);
        BattleContext ctx = BattleTestFixtures.createContext(1L, 1, List.of());
        disabled.save(ctx);
        assertTrue(disabled.load(1L).isEmpty());
    }
}
