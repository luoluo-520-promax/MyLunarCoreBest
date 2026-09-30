package cn.itcast.demo.mylunarcore.scene;

import cn.itcast.demo.mylunarcore.protocol.SceneSystemProto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SceneContext 场景运行时上下文测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code SceneContextTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("SceneContext 场景运行时上下文测试")
class SceneContextTest {

    private static final Logger log = LoggerFactory.getLogger(SceneContextTest.class);

    private SceneContext context;

    @BeforeEach
    void setUp() {
        context = SceneTestFixtures.createContext(SceneTestFixtures.PLAYER_UID);
        log.info("场景上下文初始化: playerUid={}, planeId={}, floorId={}, entryId={}, pos=({}, {}, {})",
                context.getPlayerUid(), context.getPlaneId(), context.getFloorId(), context.getEntryId(),
                context.getPlayerPos().getX(), context.getPlayerPos().getY(), context.getPlayerPos().getZ());
    }

    /**
     * 验证点：构造器应正确保存玩家与场景基础信息。
     * <p>测试方法 {@code constructorShouldStoreSceneBasics}：
     * <ul>
     *   <li>{@code assertEquals(SceneTestFixtures.PLAYER_UID, context.getPlayerUid());}</li>
     *   <li>{@code assertEquals(SceneTestFixtures.PLANE_ID, context.getPlaneId());}</li>
     *   <li>{@code assertEquals(SceneTestFixtures.FLOOR_ID, context.getFloorId());}</li>
     *   <li>{@code assertEquals(SceneTestFixtures.ENTRY_ID, context.getEntryId());}</li>
     *   <li>{@code assertFalse(context.isInitialized());}</li>
     * </ul>
     */
    @Test
    @DisplayName("构造器应正确保存玩家与场景基础信息")
    void constructorShouldStoreSceneBasics() {
        assertEquals(SceneTestFixtures.PLAYER_UID, context.getPlayerUid());
        assertEquals(SceneTestFixtures.PLANE_ID, context.getPlaneId());
        assertEquals(SceneTestFixtures.FLOOR_ID, context.getFloorId());
        assertEquals(SceneTestFixtures.ENTRY_ID, context.getEntryId());
        assertFalse(context.isInitialized());
        log.info("基础信息校验: playerUid={}, planeId={}, floorId={}, entryId={}, initialized={}",
                context.getPlayerUid(), context.getPlaneId(), context.getFloorId(),
                context.getEntryId(), context.isInitialized());
    }

    /**
     * 验证点：addMonster/addNpc/addProp 应注册实体并可按 entityId 查询。
     * <p>测试方法 {@code addEntitiesShouldBeRetrievable}：
     * <ul>
     *   <li>{@code assertNotNull(monster);}</li>
     *   <li>{@code assertNotNull(npc);}</li>
     *   <li>{@code assertNotNull(prop);}</li>
     *   <li>{@code assertEquals(500, monster.getHp());}</li>
     *   <li>{@code assertEquals(List.of(7, 8), monster.getBuffs());}</li>
     *   <li>{@code assertEquals(42, npc.getRogueEventId());}</li>
     * </ul>
     */
    @Test
    @DisplayName("addMonster/addNpc/addProp 应注册实体并可按 entityId 查询")
    void addEntitiesShouldBeRetrievable() {
        context.addMonster(SceneTestFixtures.monsterState(
                1000001, 101, 5, 500, 500, 1.0f, 2.0f, 3.0f, List.of(7, 8)));
        context.addNpc(SceneTestFixtures.npcState(
                1000002, 501, 42, 4.0f, 5.0f, 6.0f));
        context.addProp(SceneTestFixtures.propState(
                1000003, 301, 0, 7.0f, 8.0f, 9.0f));

        SceneContext.MonsterState monster = context.getMonster(1000001);
        SceneContext.NpcState npc = context.getNpc(1000002);
        SceneContext.PropState prop = context.getProp(1000003);

        assertNotNull(monster);
        assertNotNull(npc);
        assertNotNull(prop);
        log.info("实体注册校验: monster101Hp={}, monster101Buffs={}, npc501RogueEventId={}, prop301State={}",
                monster.getHp(), monster.getBuffs(), npc.getRogueEventId(), prop.getState());
        assertEquals(500, monster.getHp());
        assertEquals(List.of(7, 8), monster.getBuffs());
        assertEquals(42, npc.getRogueEventId());
        assertEquals(0, prop.getState());
    }

    /**
     * 验证点：removeMonster 应删除并返回怪物状态。
     * <p>测试方法 {@code removeMonsterShouldDeleteAndReturn}：
     * <ul>
     *   <li>{@code assertNotNull(removed);}</li>
     *   <li>{@code assertEquals(101, removed.getMonsterId());}</li>
     *   <li>{@code assertNull(context.getMonster(1000001));}</li>
     * </ul>
     */
    @Test
    @DisplayName("removeMonster 应删除并返回怪物状态")
    void removeMonsterShouldDeleteAndReturn() {
        context.addMonster(SceneTestFixtures.monsterState(
                1000001, 101, 5, 500, 500, 1.0f, 2.0f, 3.0f, List.of()));
        SceneContext.MonsterState removed = context.removeMonster(1000001);
        assertNotNull(removed);
        assertEquals(101, removed.getMonsterId());
        assertNull(context.getMonster(1000001));
    }

    /**
     * 验证点：shouldBroadcastMove 首次与跨格应广播，同格微移应跳过。
     * <p>测试方法 {@code shouldBroadcastMoveThrottle}：
     * <ul>
     *   <li>{@code assertTrue(context.shouldBroadcastMove(0f, 0f, 20f, 1.0f));}</li>
     *   <li>{@code assertFalse(context.shouldBroadcastMove(0.1f, 0.1f, 20f, 1.0f));}</li>
     *   <li>{@code assertTrue(context.shouldBroadcastMove(25f, 0f, 20f, 1.0f));}</li>
     * </ul>
     */
    @Test
    @DisplayName("shouldBroadcastMove 首次与跨格应广播，同格微移应跳过")
    void shouldBroadcastMoveThrottle() {
        assertTrue(context.shouldBroadcastMove(0f, 0f, 20f, 1.0f));
        assertFalse(context.shouldBroadcastMove(0.1f, 0.1f, 20f, 1.0f));
        assertTrue(context.shouldBroadcastMove(25f, 0f, 20f, 1.0f));
    }

    /**
     * 验证点：buildEnterSceneRsp 应组装场景信息与实体列表。
     * <p>测试方法 {@code buildEnterSceneRspShouldIncludeEntities}：
     * <ul>
     *   <li>{@code assertEquals(0, rsp.getRetcode());}</li>
     *   <li>{@code assertEquals(SceneTestFixtures.PLANE_ID, info.getPlaneId());}</li>
     *   <li>{@code assertEquals(SceneTestFixtures.FLOOR_ID, info.getFloorId());}</li>
     *   <li>{@code assertEquals(SceneTestFixtures.ENTRY_ID, info.getEntryId());}</li>
     *   <li>{@code assertEquals(SceneTestFixtures.POS_X, info.getPosX(), 0.001f);}</li>
     *   <li>{@code assertEquals(1, rsp.getEntityList().getMonstersCount());}</li>
     * </ul>
     */
    @Test
    @DisplayName("buildEnterSceneRsp 应组装场景信息与实体列表")
    void buildEnterSceneRspShouldIncludeEntities() {
        context.addMonster(SceneTestFixtures.monsterState(
                1000001, 101, 5, 500, 500, 1.0f, 2.0f, 3.0f, List.of(7)));
        context.addNpc(SceneTestFixtures.npcState(
                1000002, 501, 42, 4.0f, 5.0f, 6.0f));
        context.addProp(SceneTestFixtures.propState(
                1000003, 301, 0, 7.0f, 8.0f, 9.0f));

        SceneSystemProto.EnterSceneScRsp rsp = context.buildEnterSceneRsp(0);
        SceneSystemProto.SceneLoadInfo info = rsp.getSceneInfo();

        log.info("进入场景响应校验: retcode={}, planeId={}, floorId={}, entryId={}, pos=({}, {}, {}), monsterCount={}, npcCount={}, propCount={}",
                rsp.getRetcode(), info.getPlaneId(), info.getFloorId(), info.getEntryId(),
                info.getPosX(), info.getPosY(), info.getPosZ(),
                rsp.getEntityList().getMonstersCount(),
                rsp.getEntityList().getNpcsCount(),
                rsp.getEntityList().getPropsCount());
        assertEquals(0, rsp.getRetcode());
        assertEquals(SceneTestFixtures.PLANE_ID, info.getPlaneId());
        assertEquals(SceneTestFixtures.FLOOR_ID, info.getFloorId());
        assertEquals(SceneTestFixtures.ENTRY_ID, info.getEntryId());
        assertEquals(SceneTestFixtures.POS_X, info.getPosX(), 0.001f);
        assertEquals(1, rsp.getEntityList().getMonstersCount());
        assertEquals(1000001, rsp.getEntityList().getMonsters(0).getEntityId());
        assertEquals(101, rsp.getEntityList().getMonsters(0).getMonsterId());
        assertEquals(500, rsp.getEntityList().getMonsters(0).getHp());
    }

    /**
     * 验证点：buildCurSceneRsp 应返回当前场景快照。
     * <p>测试方法 {@code buildCurSceneRspShouldMirrorCurrentState}：
     * <ul>
     *   <li>{@code assertEquals(0, rsp.getRetcode());}</li>
     *   <li>{@code assertEquals(1000003, prop.getEntityId());}</li>
     *   <li>{@code assertEquals(301, prop.getPropId());}</li>
     *   <li>{@code assertEquals(1, prop.getState());}</li>
     * </ul>
     */
    @Test
    @DisplayName("buildCurSceneRsp 应返回当前场景快照")
    void buildCurSceneRspShouldMirrorCurrentState() {
        context.addProp(SceneTestFixtures.propState(
                1000003, 301, 1, 7.0f, 8.0f, 9.0f));

        SceneSystemProto.GetCurSceneInfoScRsp rsp = context.buildCurSceneRsp(0);
        SceneSystemProto.PropEntity prop = rsp.getEntityList().getProps(0);

        log.info("当前场景响应校验: retcode={}, propEntityId={}, propId={}, propState={}",
                rsp.getRetcode(), prop.getEntityId(), prop.getPropId(), prop.getState());
        assertEquals(0, rsp.getRetcode());
        assertEquals(1000003, prop.getEntityId());
        assertEquals(301, prop.getPropId());
        assertEquals(1, prop.getState());
    }

    /**
     * 验证点：onTick 短间隔不应过期默认 TTL Buff。
     * <p>测试方法 {@code onTickShouldHandleMonstersWithBuffs}：
     * <ul>
     *   <li>{@code assertEquals(2, monster.getBuffs().size());}</li>
     *   <li>{@code assertEquals(500, monster.getHp());}</li>
     * </ul>
     */
    @Test
    @DisplayName("onTick 短间隔不应过期默认 TTL Buff")
    void onTickShouldHandleMonstersWithBuffs() {
        context.addMonster(SceneTestFixtures.monsterState(
                1000001, 101, 5, 500, 500, 0.0f, 0.0f, 0.0f, List.of(7, 8)));

        long nowMillis = 1_700_000_000L;
        long deltaMillis = 16L;
        context.onTick(nowMillis, deltaMillis);

        SceneContext.MonsterState monster = context.getMonster(1000001);
        log.info("场景 tick 校验: nowMillis={}, deltaMillis={}, monsterEntityId={}, buffCount={}, hp={}",
                nowMillis, deltaMillis, monster.getEntityId(), monster.getBuffs().size(), monster.getHp());
        assertEquals(2, monster.getBuffs().size());
        assertEquals(500, monster.getHp());
    }

    /**
     * 验证点：onTick 超过默认 TTL 应移除 Buff。
     * <p>测试方法 {@code onTickShouldExpireBuffsAfterTtl}：
     * <ul>
     *   <li>{@code assertEquals(0, context.getMonster(1000001).getBuffs().size());}</li>
     * </ul>
     */
    @Test
    @DisplayName("onTick 超过默认 TTL 应移除 Buff")
    void onTickShouldExpireBuffsAfterTtl() {
        context.addMonster(SceneTestFixtures.monsterState(
                1000001, 101, 5, 500, 500, 0.0f, 0.0f, 0.0f, List.of(7, 8)));
        context.onTick(1L, SceneContext.DEFAULT_BUFF_TTL_MS + 1);
        assertEquals(0, context.getMonster(1000001).getBuffs().size());
    }

    /**
     * 验证点：MonsterState 构造时 buffs 为 null 应降级为空列表。
     * <p>测试方法 {@code monsterStateNullBuffsShouldBeEmptyList}：
     * <ul>
     *   <li>{@code assertNotNull(monster.getBuffs());}</li>
     *   <li>{@code assertTrue(monster.getBuffs().isEmpty());}</li>
     * </ul>
     */
    @Test
    @DisplayName("MonsterState 构造时 buffs 为 null 应降级为空列表")
    void monsterStateNullBuffsShouldBeEmptyList() {
        SceneContext.MonsterState monster = SceneTestFixtures.monsterState(
                1000001, 101, 5, 500, 500, 0.0f, 0.0f, 0.0f, null);

        assertNotNull(monster.getBuffs());
        assertTrue(monster.getBuffs().isEmpty());
        log.info("空 Buff 降级校验: entityId={}, monsterId={}, buffs={}",
                monster.getEntityId(), monster.getMonsterId(), monster.getBuffs());
    }

    /**
     * 验证点：setInitialized 应更新初始化标记。
     * <p>测试方法 {@code setInitializedShouldUpdateFlag}：
     * <ul>
     *   <li>{@code assertFalse(initializedBefore);}</li>
     *   <li>{@code assertTrue(context.isInitialized());}</li>
     * </ul>
     */
    @Test
    @DisplayName("setInitialized 应更新初始化标记")
    void setInitializedShouldUpdateFlag() {
        boolean initializedBefore = context.isInitialized();
        context.setInitialized(true);
        log.info("初始化标记校验: initializedBefore={}, initializedAfter={}",
                initializedBefore, context.isInitialized());
        assertFalse(initializedBefore);
        assertTrue(context.isInitialized());
    }
}
