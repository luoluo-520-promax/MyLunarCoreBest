package cn.itcast.demo.mylunarcore.scene;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ZoneContext 共享世界实体测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code ZoneContextTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("ZoneContext 共享世界实体测试")
class ZoneContextTest {

    /**
     * 验证点：击杀后无刷新应移除怪物；有刷新应到期复活。
     * <p>测试方法 {@code killAndRefreshMonster}：
     * <ul>
     *   <li>{@code assertNotNull(killed);}</li>
     *   <li>{@code assertNull(zone.getMonster(1000001));}</li>
     *   <li>{@code assertFalse(zone.getMonster(1000002).isAlive());}</li>
     *   <li>{@code assertTrue(zone.refreshDueMonsters(5_000L).isEmpty());}</li>
     *   <li>{@code assertEquals(1, revived.size());}</li>
     *   <li>{@code assertTrue(zone.getMonster(1000002).isAlive());}</li>
     * </ul>
     */
    @Test
    @DisplayName("击杀后无刷新应移除怪物；有刷新应到期复活")
    void killAndRefreshMonster() {
        ZoneContext zone = new ZoneContext(10001, 1, 1);
        ZoneContext.ZoneMonster monster = new ZoneContext.ZoneMonster(
                1000001, 101, 5, 100, 100,
                new SceneContext.ScenePos(1f, 0f, 1f), List.of());
        zone.putMonster(monster);

        ZoneContext.ZoneMonster killed = zone.killMonster(1000001, 0, 1_000L);
        assertNotNull(killed);
        assertNull(zone.getMonster(1000001));

        ZoneContext.ZoneMonster again = new ZoneContext.ZoneMonster(
                1000002, 102, 5, 100, 100,
                new SceneContext.ScenePos(2f, 0f, 2f), List.of());
        zone.putMonster(again);
        zone.killMonster(1000002, 10, 1_000L);
        assertFalse(zone.getMonster(1000002).isAlive());
        assertTrue(zone.refreshDueMonsters(5_000L).isEmpty());
        List<ZoneContext.ZoneMonster> revived = zone.refreshDueMonsters(12_000L);
        assertEquals(1, revived.size());
        assertTrue(zone.getMonster(1000002).isAlive());
        assertEquals(100, zone.getMonster(1000002).getHp());
    }
}
