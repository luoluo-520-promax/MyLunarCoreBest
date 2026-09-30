package cn.itcast.demo.mylunarcore.scene;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SceneManager 场景运行时索引测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code SceneManagerTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("SceneManager 场景运行时索引测试")
class SceneManagerTest {

    private static final Logger log = LoggerFactory.getLogger(SceneManagerTest.class);

    private static final long PLAYER_UID = SceneTestFixtures.PLAYER_UID;
    private static final long OTHER_UID = 88L;

    private SceneManager manager;

    @BeforeEach
    void setUp() {
        manager = new SceneManager();
        log.info("场景管理器初始化: playerUid={}, planeId={}, floorId={}",
                PLAYER_UID, SceneTestFixtures.PLANE_ID, SceneTestFixtures.FLOOR_ID);
    }

    /**
     * 验证点：put/get 应正确缓存与读取场景上下文。
     * <p>测试方法 {@code putAndGetShouldStoreContext}：
     * <ul>
     *   <li>{@code assertNotNull(loaded);}</li>
     *   <li>{@code assertEquals(PLAYER_UID, loaded.getPlayerUid());}</li>
     *   <li>{@code assertEquals(SceneTestFixtures.PLANE_ID, loaded.getPlaneId());}</li>
     *   <li>{@code assertEquals(SceneTestFixtures.FLOOR_ID, loaded.getFloorId());}</li>
     *   <li>{@code assertEquals(1000001, loaded.getMonster(1000001).getEntityId());}</li>
     * </ul>
     */
    @Test
    @DisplayName("put/get 应正确缓存与读取场景上下文")
    void putAndGetShouldStoreContext() {
        SceneContext ctx = SceneTestFixtures.createInitializedContext(PLAYER_UID);
        manager.put(PLAYER_UID, ctx);

        SceneContext loaded = manager.getByPlayerUid(PLAYER_UID);
        assertNotNull(loaded);
        log.info("场景缓存校验: playerUid={}, planeId={}, floorId={}, initialized={}, monsterCount={}",
                loaded.getPlayerUid(), loaded.getPlaneId(), loaded.getFloorId(),
                loaded.isInitialized(), loaded.getMonster(1000001) != null ? 1 : 0);
        assertEquals(PLAYER_UID, loaded.getPlayerUid());
        assertEquals(SceneTestFixtures.PLANE_ID, loaded.getPlaneId());
        assertEquals(SceneTestFixtures.FLOOR_ID, loaded.getFloorId());
        assertEquals(1000001, loaded.getMonster(1000001).getEntityId());
    }

    /**
     * 验证点：put 应覆盖玩家已有场景上下文。
     * <p>测试方法 {@code putShouldReplaceExistingContext}：
     * <ul>
     *   <li>{@code assertNotNull(loaded);}</li>
     *   <li>{@code assertEquals(second, loaded);}</li>
     *   <li>{@code assertTrue(loaded.isInitialized());}</li>
     * </ul>
     */
    @Test
    @DisplayName("put 应覆盖玩家已有场景上下文")
    void putShouldReplaceExistingContext() {
        SceneContext first = SceneTestFixtures.createContext(PLAYER_UID);
        SceneContext second = SceneTestFixtures.createInitializedContext(PLAYER_UID);
        manager.put(PLAYER_UID, first);
        manager.put(PLAYER_UID, second);

        SceneContext loaded = manager.getByPlayerUid(PLAYER_UID);
        log.info("场景覆盖校验: playerUid={}, firstInitialized={}, loadedInitialized={}",
                PLAYER_UID, first.isInitialized(), loaded.isInitialized());
        assertNotNull(loaded);
        assertEquals(second, loaded);
        assertTrue(loaded.isInitialized());
    }

    /**
     * 验证点：remove 应释放玩家场景上下文。
     * <p>测试方法 {@code removeShouldDropContext}：
     * <ul>
     *   <li>{@code assertNotNull(manager.getByPlayerUid(PLAYER_UID));}</li>
     *   <li>{@code assertNull(afterRemove);}</li>
     * </ul>
     */
    @Test
    @DisplayName("remove 应释放玩家场景上下文")
    void removeShouldDropContext() {
        manager.put(PLAYER_UID, SceneTestFixtures.createInitializedContext(PLAYER_UID));
        assertNotNull(manager.getByPlayerUid(PLAYER_UID));

        manager.remove(PLAYER_UID);
        SceneContext afterRemove = manager.getByPlayerUid(PLAYER_UID);
        log.info("场景移除校验: playerUid={}, existsBeforeRemove=true, existsAfterRemove={}",
                PLAYER_UID, afterRemove != null);
        assertNull(afterRemove);
    }

    /**
     * 验证点：查询未进场景的玩家应返回 null。
     * <p>测试方法 {@code getMissingUidShouldReturnNull}：
     * <ul>
     *   <li>{@code assertNull(missing);}</li>
     * </ul>
     */
    @Test
    @DisplayName("查询未进场景的玩家应返回 null")
    void getMissingUidShouldReturnNull() {
        SceneContext missing = manager.getByPlayerUid(OTHER_UID);
        log.info("缺失玩家查询校验: playerUid={}, context={}", OTHER_UID, missing);
        assertNull(missing);
    }
}
