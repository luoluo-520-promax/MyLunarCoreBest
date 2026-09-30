package cn.itcast.demo.mylunarcore.center;

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
 * {@link SceneRegistry}：zoneId 编码、注册幂等、查找、心跳续约、租约驱逐与排水标记。
 */
@DisplayName("SceneRegistry 场景注册表测试")
class SceneRegistryTest {

    private static final Logger log = LoggerFactory.getLogger(SceneRegistryTest.class);

    private static final int PLANE_ID = 20101;
    private static final int FLOOR_ID = 1;
    /** 期望 zoneId = plane*10000+floor = 201010001 */
    private static final int EXPECTED_ZONE_ID = PLANE_ID * 10000 + FLOOR_ID;

    private SceneRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SceneRegistry();
        log.info("场景注册表初始化: planeId={}, floorId={}, expectedZoneId={}",
                PLANE_ID, FLOOR_ID, EXPECTED_ZONE_ID);
    }

    /** 静态 zoneId(20101,1)=201010001；zoneId(103,42)=1030042。 */
    @Test
    @DisplayName("zoneId 应按 planeId*10000+floorId 编码")
    void zoneIdShouldEncodePlaneAndFloor() {
        int zoneId = SceneRegistry.zoneId(PLANE_ID, FLOOR_ID);
        int zoneIdOther = SceneRegistry.zoneId(103, 42);

        log.info("ZoneId 编码校验: planeId={}, floorId={}, zoneId={}, planeId=103 floorId=42 zoneId={}",
                PLANE_ID, FLOOR_ID, zoneId, zoneIdOther);
        assertEquals(EXPECTED_ZONE_ID, zoneId);
        assertEquals(1030042, zoneIdOther);
    }

    /** 首次 register：nodeId=local，registeredAt 落在调用前后时间窗内。 */
    @Test
    @DisplayName("register 应创建 ZoneInfo 并写入注册表")
    void registerShouldCreateZoneInfo() {
        long beforeMs = System.currentTimeMillis();
        SceneRegistry.ZoneInfo info = registry.register(PLANE_ID, FLOOR_ID);
        long afterMs = System.currentTimeMillis();

        log.info("首次注册校验: zoneId={}, planeId={}, floorId={}, nodeId={}, registeredAt={}, beforeMs={}, afterMs={}",
                info.zoneId(), info.planeId(), info.floorId(), info.nodeId(),
                info.registeredAt(), beforeMs, afterMs);
        assertNotNull(info);
        assertEquals(EXPECTED_ZONE_ID, info.zoneId());
        assertEquals(PLANE_ID, info.planeId());
        assertEquals(FLOOR_ID, info.floorId());
        assertEquals("local", info.nodeId());
        assertTrue(info.registeredAt() >= beforeMs && info.registeredAt() <= afterMs);
    }

    /** 同 plane/floor 再 register：zoneId/node/registeredAt 不变，lease 可延长。 */
    @Test
    @DisplayName("重复 register 应保持同一 Zone 并由同节点续约")
    void registerShouldBeIdempotent() {
        SceneRegistry.ZoneInfo first = registry.register(PLANE_ID, FLOOR_ID);
        SceneRegistry.ZoneInfo second = registry.register(PLANE_ID, FLOOR_ID);

        log.info("幂等注册校验: zoneId={}, firstRegisteredAt={}, secondRegisteredAt={}",
                first.zoneId(), first.registeredAt(), second.registeredAt());
        assertEquals(first.zoneId(), second.zoneId());
        assertEquals(first.nodeId(), second.nodeId());
        assertEquals(first.registeredAt(), second.registeredAt());
        assertTrue(second.leaseUntilMillis() >= first.leaseUntilMillis());
    }

    /** find(已注册) 非空；find(不存在) 为 null。 */
    @Test
    @DisplayName("find 应按 zoneId 查询已注册 Zone")
    void findShouldReturnRegisteredZone() {
        registry.register(PLANE_ID, FLOOR_ID);
        SceneRegistry.ZoneInfo found = registry.find(EXPECTED_ZONE_ID);
        SceneRegistry.ZoneInfo missing = registry.find(99999999);

        log.info("按 zoneId 查询校验: zoneId={}, foundPlaneId={}, foundFloorId={}, foundNodeId={}, missingIsNull={}",
                EXPECTED_ZONE_ID,
                found != null ? found.planeId() : null,
                found != null ? found.floorId() : null,
                found != null ? found.nodeId() : null,
                missing == null);
        assertNotNull(found);
        assertEquals(PLANE_ID, found.planeId());
        assertEquals(FLOOR_ID, found.floorId());
        assertNull(missing);
    }

    /** (100,1)/(100,2)/(200,1) 映射为三个不同 zoneId 且均可 find。 */
    @Test
    @DisplayName("不同 plane/floor 应注册为独立 Zone")
    void differentPlaneFloorShouldRegisterDistinctZones() {
        SceneRegistry.ZoneInfo a = registry.register(100, 1);
        SceneRegistry.ZoneInfo b = registry.register(100, 2);
        SceneRegistry.ZoneInfo c = registry.register(200, 1);

        log.info("多 Zone 注册校验: zoneA={}, zoneB={}, zoneC={}, countHint={}",
                a.zoneId(), b.zoneId(), c.zoneId(), 3);
        assertEquals(1000001, a.zoneId());
        assertEquals(1000002, b.zoneId());
        assertEquals(2000001, c.zoneId());
        assertNotNull(registry.find(a.zoneId()));
        assertNotNull(registry.find(b.zoneId()));
        assertNotNull(registry.find(c.zoneId()));
    }

    /**
     * heartbeat 续约并写入 playerCountHint=3；
     * 另建短租约注册表，用「现在+60s」作为 now 驱逐，find 变 null。
     */
    @Test
    @DisplayName("heartbeat 应续约租约；过期后 evict 摘除")
    void heartbeatAndLeaseEviction() {
        SceneRegistry.ZoneInfo info = registry.register(PLANE_ID, FLOOR_ID, "node-a");
        assertNotNull(info);
        long leaseUntil = info.leaseUntilMillis();
        SceneRegistry.ZoneInfo renewed = registry.heartbeat(EXPECTED_ZONE_ID, "node-a", 3);
        assertNotNull(renewed);
        assertTrue(renewed.leaseUntilMillis() >= leaseUntil);
        assertEquals(3, renewed.playerCountHint());

        SceneRegistry shortLease = new SceneRegistry();
        shortLease.register(1, 1, "n1");
        int zoneId = SceneRegistry.zoneId(1, 1);
        assertNotNull(shortLease.find(zoneId));
        int removed = shortLease.evictExpired(System.currentTimeMillis() + 60_000L);
        assertTrue(removed >= 1);
        assertNull(shortLease.find(zoneId));
    }

    /** markDraining(true) 后 ZoneInfo.draining() 为 true。 */
    @Test
    @DisplayName("markDraining 应标记排水")
    void markDrainingShouldUpdateFlag() {
        registry.register(PLANE_ID, FLOOR_ID, "local");
        SceneRegistry.ZoneInfo draining = registry.markDraining(EXPECTED_ZONE_ID, true);
        assertNotNull(draining);
        assertTrue(draining.draining());
    }
}
