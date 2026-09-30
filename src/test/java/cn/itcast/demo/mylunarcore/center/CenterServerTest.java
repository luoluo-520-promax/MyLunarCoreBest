package cn.itcast.demo.mylunarcore.center;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link LocalCenterServer} 迁移决策：planMigration 登记 Zone、幂等、isLocalNode 判定。
 */
@DisplayName("CenterServer 中心服迁移决策测试")
class CenterServerTest {

    private static final Logger log = LoggerFactory.getLogger(CenterServerTest.class);

    private static final int TARGET_PLANE_ID = 20101;
    private static final int TARGET_FLOOR_ID = 3;
    private static final int EXPECTED_ZONE_ID = SceneRegistry.zoneId(TARGET_PLANE_ID, TARGET_FLOOR_ID);

    private SceneRegistry sceneRegistry;
    private CenterServer centerServer;

    /** 使用 nodeId=local 的 LocalCenterServer + 空注册表。 */
    @BeforeEach
    void setUp() {
        sceneRegistry = new SceneRegistry();
        centerServer = new LocalCenterServer(sceneRegistry, "local");
        log.info("中心服初始化: targetPlaneId={}, targetFloorId={}, expectedZoneId={}",
                TARGET_PLANE_ID, TARGET_FLOOR_ID, EXPECTED_ZONE_ID);
    }

    /** planMigration 返回的 zone/plane/floor/node 与注册表 find 一致。 */
    @Test
    @DisplayName("planMigration 应登记目标 Zone 并返回迁移计划")
    void planMigrationShouldRegisterAndReturnPlan() {
        CenterServer.MigrationPlan plan = centerServer.planMigration(TARGET_PLANE_ID, TARGET_FLOOR_ID);
        SceneRegistry.ZoneInfo registered = sceneRegistry.find(EXPECTED_ZONE_ID);

        log.info("迁移计划校验: zoneId={}, planeId={}, floorId={}, nodeId={}, registeredPresent={}",
                plan.zoneId(), plan.planeId(), plan.floorId(), plan.nodeId(), registered != null);
        assertNotNull(plan);
        assertEquals(EXPECTED_ZONE_ID, plan.zoneId());
        assertEquals(TARGET_PLANE_ID, plan.planeId());
        assertEquals(TARGET_FLOOR_ID, plan.floorId());
        assertEquals("local", plan.nodeId());
        assertNotNull(registered);
    }

    /** 连续两次 planMigration 的 zone/plane/floor/node 完全相同。 */
    @Test
    @DisplayName("重复 planMigration 应返回同一 zoneId（幂等）")
    void planMigrationShouldBeIdempotent() {
        CenterServer.MigrationPlan first = centerServer.planMigration(TARGET_PLANE_ID, TARGET_FLOOR_ID);
        CenterServer.MigrationPlan second = centerServer.planMigration(TARGET_PLANE_ID, TARGET_FLOOR_ID);

        log.info("计划幂等校验: zoneIdFirst={}, zoneIdSecond={}, nodeIdFirst={}, nodeIdSecond={}",
                first.zoneId(), second.zoneId(), first.nodeId(), second.nodeId());
        assertEquals(first.zoneId(), second.zoneId());
        assertEquals(first.planeId(), second.planeId());
        assertEquals(first.floorId(), second.floorId());
        assertEquals(first.nodeId(), second.nodeId());
    }

    /** null 与 "local" 视为本机；其它 nodeId 为远端。 */
    @Test
    @DisplayName("isLocalNode 对 null 与 local 应返回 true")
    void isLocalNodeShouldAcceptNullAndLocal() {
        boolean nullIsLocal = centerServer.isLocalNode(null);
        boolean localIsLocal = centerServer.isLocalNode("local");
        boolean remoteIsLocal = centerServer.isLocalNode("game-node-2");

        log.info("本地节点判断: nodeId=null -> {}, nodeId=local -> {}, nodeId=game-node-2 -> {}",
                nullIsLocal, localIsLocal, remoteIsLocal);
        assertTrue(nullIsLocal);
        assertTrue(localIsLocal);
        assertFalse(remoteIsLocal);
    }

    /** plan(100,1) 与 plan(100,2) 的 zoneId 分别为 1000001 / 1000002。 */
    @Test
    @DisplayName("不同目标 plane/floor 应生成不同 zoneId")
    void differentTargetsShouldProduceDistinctZoneIds() {
        CenterServer.MigrationPlan planA = centerServer.planMigration(100, 1);
        CenterServer.MigrationPlan planB = centerServer.planMigration(100, 2);

        log.info("多目标计划校验: planA.zoneId={}, planA.floorId={}, planB.zoneId={}, planB.floorId={}",
                planA.zoneId(), planA.floorId(), planB.zoneId(), planB.floorId());
        assertEquals(1000001, planA.zoneId());
        assertEquals(1000002, planB.zoneId());
        assertEquals(1, planA.floorId());
        assertEquals(2, planB.floorId());
    }

    /**
     * RemoteCenter + 内存双节点路由：plane 0→node-a（本机），plane 1→node-b（远端）。
     */
    @Test
    @DisplayName("RemoteCenter + 内存路由桩应按 plane 分配节点")
    void remoteCenterShouldUseRoutingClientNodeAssignment() {
        CenterRoutingClient client = new InMemoryMultiNodeRoutingClient(java.util.List.of("node-a", "node-b"));
        CenterServer remote = new RemoteCenterServer(client, "node-a");

        CenterServer.MigrationPlan onA = remote.planMigration(0, 1);
        CenterServer.MigrationPlan onB = remote.planMigration(1, 1);

        assertEquals("node-a", onA.nodeId());
        assertEquals("node-b", onB.nodeId());
        assertTrue(remote.isLocalNode(onA.nodeId()));
        assertFalse(remote.isLocalNode(onB.nodeId()));
    }
}
