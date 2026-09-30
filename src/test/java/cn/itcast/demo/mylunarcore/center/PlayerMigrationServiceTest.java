package cn.itcast.demo.mylunarcore.center;

import cn.itcast.demo.mylunarcore.battle.BattleSnapshotService;
import cn.itcast.demo.mylunarcore.scene.SceneContext;
import cn.itcast.demo.mylunarcore.scene.SceneManager;
import cn.itcast.demo.mylunarcore.scene.SceneSnapshotService;
import cn.itcast.demo.mylunarcore.scene.ZoneManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link PlayerMigrationService}：本地迁移不提前 leaveZone；远端无广告地址失败 retcode=3 等编排。
 */
@DisplayName("PlayerMigrationService 玩家迁移编排测试")
class PlayerMigrationServiceTest {

    private static final long PLAYER_UID = 10086L;
    private static final int OLD_ZONE_ID = 201010001;
    private static final int TARGET_PLANE_ID = 20101;
    private static final int TARGET_FLOOR_ID = 5;
    private static final int TARGET_ZONE_ID = SceneRegistry.zoneId(TARGET_PLANE_ID, TARGET_FLOOR_ID);

    private CenterServer centerServer;
    private ZoneManager zoneManager;
    private SceneManager sceneManager;
    private MigrationTicketService ticketService;
    private SceneRegistry sceneRegistry;
    private PlayerMigrationService migrationService;

    /** 注入 mock Center/Zone/Scene 与真实 Ticket/Registry；写冻结超时 10s、重试 3。 */
    @BeforeEach
    void setUp() {
        centerServer = mock(CenterServer.class);
        zoneManager = mock(ZoneManager.class);
        sceneManager = mock(SceneManager.class);
        ticketService = new MigrationTicketService();
        sceneRegistry = new SceneRegistry();
        migrationService = new PlayerMigrationService(
                centerServer, zoneManager, sceneManager, ticketService, sceneRegistry,
                mock(SceneSnapshotService.class), mock(BattleSnapshotService.class),
                new MigrationWriteFreezeService(),
                10_000, 3);
    }

    /**
     * 目标 node=local：migrate 成功 retcode=0，且 never leaveZone
     *（留给后续 EnterScene 切换，避免本地闪断）。
     */
    @Test
    @DisplayName("本地迁移不提前 leaveZone，由 EnterScene 负责切换")
    void localMigrateShouldNotLeaveEarly() {
        CenterServer.MigrationPlan plan = CenterServer.MigrationPlan.of(
                TARGET_ZONE_ID, TARGET_PLANE_ID, TARGET_FLOOR_ID, "local");
        SceneContext ctx = mock(SceneContext.class);
        when(centerServer.planMigration(TARGET_PLANE_ID, TARGET_FLOOR_ID)).thenReturn(plan);
        when(centerServer.isLocalNode("local")).thenReturn(true);
        when(sceneManager.getByPlayerUid(PLAYER_UID)).thenReturn(ctx);
        when(ctx.getZoneId()).thenReturn(OLD_ZONE_ID);

        PlayerMigrationService.MigrationResult result =
                migrationService.migrate(PLAYER_UID, TARGET_PLANE_ID, TARGET_FLOOR_ID);

        assertTrue(result.success());
        assertEquals(0, result.retcode());
        assertEquals(TARGET_ZONE_ID, result.zoneId());
        verify(zoneManager, never()).leaveZone(anyInt(), anyLong());
    }

    /** 远端节点且注册表无广告地址时 migrate 失败，业务码 3。 */
    @Test
    @DisplayName("目标节点非本地且无广告地址时应返回 retcode=3")
    void migrateShouldFailWhenTargetNodeIsRemoteWithoutAddress() {
        String remoteNodeId = "game-node-2";
        CenterServer.MigrationPlan plan = CenterServer.MigrationPlan.of(
                TARGET_ZONE_ID, TARGET_PLANE_ID, TARGET_FLOOR_ID, remoteNodeId);
        when(centerServer.planMigration(TARGET_PLANE_ID, TARGET_FLOOR_ID)).thenReturn(plan);
        when(centerServer.isLocalNode(remoteNodeId)).thenReturn(false);

        PlayerMigrationService.MigrationResult result =
                migrationService.migrate(PLAYER_UID, TARGET_PLANE_ID, TARGET_FLOOR_ID);

        assertFalse(result.success());
        assertEquals(3, result.retcode());
    }

    /**
     * 远端计划带 host/port：retcode=4（需重定向），签发非空 ticket，
     * 并 leaveZone(旧区) + sceneManager.remove。
     */
    @Test
    @DisplayName("跨节点有广告地址时应签发票据并 leave 源 Zone")
    void migrateShouldRedirectWithTicket() {
        String remoteNodeId = "game-node-2";
        CenterServer.MigrationPlan plan = new CenterServer.MigrationPlan(
                TARGET_ZONE_ID, TARGET_PLANE_ID, TARGET_FLOOR_ID, remoteNodeId, "10.0.0.2", 9000);
        SceneContext ctx = mock(SceneContext.class);
        when(centerServer.planMigration(TARGET_PLANE_ID, TARGET_FLOOR_ID)).thenReturn(plan);
        when(centerServer.isLocalNode(remoteNodeId)).thenReturn(false);
        when(sceneManager.getByPlayerUid(PLAYER_UID)).thenReturn(ctx);
        when(ctx.getZoneId()).thenReturn(OLD_ZONE_ID);
        when(ctx.getEntryId()).thenReturn(1);
        when(ctx.getPlayerPos()).thenReturn(new SceneContext.ScenePos(1f, 2f, 3f));

        PlayerMigrationService.MigrationResult result =
                migrationService.migrate(PLAYER_UID, TARGET_PLANE_ID, TARGET_FLOOR_ID);

        assertFalse(result.success());
        assertEquals(4, result.retcode());
        assertNotNull(result.sessionTicket());
        assertFalse(result.sessionTicket().isBlank());
        assertEquals("10.0.0.2", result.redirectHost());
        assertEquals(9000, result.redirectPort());
        verify(zoneManager).leaveZone(OLD_ZONE_ID, PLAYER_UID);
        verify(sceneManager).remove(PLAYER_UID);
    }

    /** 目标 Zone 已 markDraining → migrate 失败 retcode=7。 */
    @Test
    @DisplayName("排水中 Zone 应拒绝迁移")
    void migrateShouldRejectDrainingZone() {
        sceneRegistry.register(TARGET_PLANE_ID, TARGET_FLOOR_ID, "local");
        sceneRegistry.markDraining(TARGET_ZONE_ID, true);
        CenterServer.MigrationPlan plan = CenterServer.MigrationPlan.of(
                TARGET_ZONE_ID, TARGET_PLANE_ID, TARGET_FLOOR_ID, "local");
        when(centerServer.planMigration(TARGET_PLANE_ID, TARGET_FLOOR_ID)).thenReturn(plan);

        PlayerMigrationService.MigrationResult result =
                migrationService.migrate(PLAYER_UID, TARGET_PLANE_ID, TARGET_FLOOR_ID);

        assertFalse(result.success());
        assertEquals(7, result.retcode());
    }
}
