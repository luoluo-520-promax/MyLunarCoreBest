package cn.itcast.demo.mylunarcore.world;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.scene.SceneContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 无缝 Cell / 实时战斗权威骨架测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code WorldCellRealtimeTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("无缝 Cell / 实时战斗权威骨架测试")
class WorldCellRealtimeTest {

    /**
     * 验证点：CellCoord 应按 cellSize 均匀分格。
     * <p>测试方法 {@code cellCoordFloorDivision}：
     * <ul>
     *   <li>{@code assertEquals(new CellCoord(0, 0), CellCoord.of(0f, 0f, 64f));}</li>
     *   <li>{@code assertEquals(new CellCoord(1, -1), CellCoord.of(64f, -0.1f, 64f));}</li>
     *   <li>{@code assertEquals("2:3", CellCoord.of(128.5f, 200f, 64f).key());}</li>
     * </ul>
     */
    @Test
    @DisplayName("CellCoord 应按 cellSize 均匀分格")
    void cellCoordFloorDivision() {
        assertEquals(new CellCoord(0, 0), CellCoord.of(0f, 0f, 64f));
        assertEquals(new CellCoord(1, -1), CellCoord.of(64f, -0.1f, 64f));
        assertEquals("2:3", CellCoord.of(128.5f, 200f, 64f).key());
    }

    /**
     * 验证点：跨 Cell 移动在开关开启时应产出 handoff 事件。
     * <p>测试方法 {@code handoffOnCellBoundary}：
     * <ul>
     *   <li>{@code assertNull(svc.onMoveAccepted(scene, 9L, 10f, 0f, 10f));}</li>
     *   <li>{@code assertNotNull(event);}</li>
     *   <li>{@code assertEquals(new CellCoord(0, 0), event.from());}</li>
     *   <li>{@code assertEquals(new CellCoord(1, 0), event.to());}</li>
     * </ul>
     */
    @Test
    @DisplayName("跨 Cell 移动在开关开启时应产出 handoff 事件")
    void handoffOnCellBoundary() {
        LunarCoreProperties props = new LunarCoreProperties();
        props.getWorld().setCellSize(64f);
        props.getWorld().setCellHandoffEnabled(true);
        CellBoundaryHandoffService svc = new CellBoundaryHandoffService(props);
        SceneContext scene = new SceneContext(9L, 100, 1, 0, new SceneContext.ScenePos(10f, 0f, 10f));

        assertNull(svc.onMoveAccepted(scene, 9L, 10f, 0f, 10f));
        CellBoundaryHandoffService.HandoffEvent event = svc.onMoveAccepted(scene, 9L, 70f, 0f, 10f);
        assertNotNull(event);
        assertEquals(new CellCoord(0, 0), event.from());
        assertEquals(new CellCoord(1, 0), event.to());
    }

    /**
     * 验证点：实时战斗默认关闭；开启后应拒绝超距与 CD 内重复。
     * <p>测试方法 {@code realtimeAuthorityGuards}：
     * <ul>
     *   <li>{@code assertFalse(authority.isEnabled());}</li>
     *   <li>{@code assertFalse(authority.applyHit(new RealtimeCombatAuthority.HitIntent(}</li>
     *   <li>{@code assertTrue(ok.accepted());}</li>
     *   <li>{@code assertTrue(ok.serverDamage() > 0);}</li>
     *   <li>{@code assertFalse(cd.accepted());}</li>
     *   <li>{@code assertEquals("skill_cd", cd.rejectReason());}</li>
     * </ul>
     */
    @Test
    @DisplayName("实时战斗默认关闭；开启后应拒绝超距与 CD 内重复")
    void realtimeAuthorityGuards() {
        LunarCoreProperties props = new LunarCoreProperties();
        RealtimeCombatAuthority authority = new RealtimeCombatAuthority(props);
        assertFalse(authority.isEnabled());
        assertFalse(authority.applyHit(new RealtimeCombatAuthority.HitIntent(
                1L, 101, 9L, 0f, 0f, 1f, 1f, 100)).accepted());

        props.getWorld().setRealtimeCombatEnabled(true);
        props.getWorld().setRealtimeSkillMinCdMs(5000L);
        RealtimeCombatAuthority.HitResult ok = authority.applyHit(new RealtimeCombatAuthority.HitIntent(
                1L, 101, 9L, 0f, 0f, 1f, 1f, 100));
        assertTrue(ok.accepted());
        assertTrue(ok.serverDamage() > 0);

        RealtimeCombatAuthority.HitResult cd = authority.applyHit(new RealtimeCombatAuthority.HitIntent(
                1L, 101, 9L, 0f, 0f, 1f, 1f, 100));
        assertFalse(cd.accepted());
        assertEquals("skill_cd", cd.rejectReason());

        RealtimeCombatAuthority.HitResult far = authority.applyHit(new RealtimeCombatAuthority.HitIntent(
                1L, 202, 9L, 0f, 0f, 100f, 100f, 100));
        assertFalse(far.accepted());
        assertEquals("out_of_range", far.rejectReason());
    }
}
