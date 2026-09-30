package cn.itcast.demo.mylunarcore.battle;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link BattleManager} 内存战局表：put/get 同引用、remove 后清空、缺失 id 返回 null。
 * SnapshotService 仅 mock，本类不测持久化。
 */
@DisplayName("BattleManager 战局管理器测试")
class BattleManagerTest {

    private static final Logger log = LoggerFactory.getLogger(BattleManagerTest.class);

    private BattleManager battleManager;

    @BeforeEach
    void setUp() {
        battleManager = new BattleManager(org.mockito.Mockito.mock(BattleSnapshotService.class));
    }

    /** put(1001) 后 get(1001) 必须是同一 {@link BattleContext} 实例。 */
    @Test
    @DisplayName("put 后 get 应返回同一战局上下文")
    void putAndGetShouldReturnSameContext() {
        BattleContext context = BattleTestFixtures.createContext(1001L, 42, BattleTestFixtures.singleWaveStage());
        battleManager.put(context);

        BattleContext found = battleManager.get(1001L);
        log.info("put/get 校验: battleId={}, playerId={}, foundSameInstance={}",
                context.getBattleId(), context.getPlayerId(), found == context);
        assertSame(context, found);
    }

    /** remove 后同一 battleId 再 get 为 null。 */
    @Test
    @DisplayName("remove 后 get 应返回 null")
    void removeShouldClearBattle() {
        BattleContext context = BattleTestFixtures.createContext(2002L, 99, BattleTestFixtures.singleWaveStage());
        battleManager.put(context);
        battleManager.remove(2002L);

        BattleContext found = battleManager.get(2002L);
        log.info("remove 校验: battleId=2002, foundAfterRemove={}", found);
        assertNull(found);
    }

    /** 从未 put 过的 id 直接 get → null。 */
    @Test
    @DisplayName("查询不存在的 battleId 应返回 null")
    void getMissingBattleShouldReturnNull() {
        BattleContext found = battleManager.get(9999L);
        log.info("查询不存在战局: battleId=9999, found={}", found);
        assertNull(found);
    }
}
