package cn.itcast.demo.mylunarcore.scene;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NPC 日程行为：时段映射、路点推进、DESPAWN_OR_SLEEP 解析。
 */
class EntityBehaviorServiceTest {

    @Test
    @DisplayName("morning 别名映射为 dawn")
    void normalizeMorningAlias() {
        assertEquals("dawn", NpcScheduleConfigRepository.normalizePeriodKey("MORNING"));
        assertEquals("night", NpcScheduleConfigRepository.normalizePeriodKey("night"));
    }

    @Test
    @DisplayName("DESPAWN_OR_SLEEP：有 home 则 SLEEP，否则 DESPAWN")
    void despawnOrSleepResolvesByHome() {
        assertEquals(DailyRoutineBehavior.Kind.SLEEP,
                DailyRoutineBehavior.Kind.resolveDespawnOrSleep("DESPAWN_OR_SLEEP", true));
        assertEquals(DailyRoutineBehavior.Kind.DESPAWN,
                DailyRoutineBehavior.Kind.resolveDespawnOrSleep("DESPAWN_OR_SLEEP", false));
    }

    @Test
    @DisplayName("正午 OPEN_SHOP / 夜间回屋睡觉")
    void resolvePeriodBehaviors() {
        var schedule = new NpcScheduleConfigRepository.ScheduleCfg(
                1001, "IDLE", 2.5f, "idle_stand",
                List.of(
                        new NpcScheduleConfigRepository.PeriodCfg("dawn", "PATROL", "loop", null, null, "walk"),
                        new NpcScheduleConfigRepository.PeriodCfg("noon", "OPEN_SHOP", null, "stall", null, "vend"),
                        new NpcScheduleConfigRepository.PeriodCfg("night", "DESPAWN_OR_SLEEP", null, null, "home", "sleep")
                ),
                Map.of(
                        "stall", new NpcScheduleConfigRepository.VecCfg(12f, 0f, 8f),
                        "home", new NpcScheduleConfigRepository.VecCfg(5f, 0f, 3f)
                ),
                Map.of("loop", List.of(
                        new NpcScheduleConfigRepository.VecCfg(10f, 0f, 10f),
                        new NpcScheduleConfigRepository.VecCfg(20f, 0f, 10f)
                ))
        );

        var noon = DailyRoutineBehavior.resolve(schedule, WorldTimeService.Period.NOON);
        assertEquals(DailyRoutineBehavior.Kind.OPEN_SHOP, noon.kind());
        assertEquals("vend", noon.animHint());
        assertEquals("stall", noon.interactionSpot());
        assertTrue(noon.visible());
        assertNotNull(noon.targetPos());
        assertEquals(12f, noon.targetPos().getX(), 0.01f);

        var night = DailyRoutineBehavior.resolve(schedule, WorldTimeService.Period.NIGHT);
        assertEquals(DailyRoutineBehavior.Kind.SLEEP, night.kind());
        assertEquals("sleep", night.animHint());
        assertTrue(night.visible());
        assertEquals(5f, night.targetPos().getX(), 0.01f);

        var dawn = DailyRoutineBehavior.resolve(schedule, WorldTimeService.Period.DAWN);
        assertEquals(DailyRoutineBehavior.Kind.PATROL, dawn.kind());
        assertEquals("walk", dawn.animHint());
    }

    @Test
    @DisplayName("WaypointMove 沿路点推进并切换下标")
    void waypointMoveAdvancesIndex() {
        var schedule = new NpcScheduleConfigRepository.ScheduleCfg(
                9, "IDLE", 10f, "idle",
                List.of(),
                Map.of(),
                Map.of("g", List.of(
                        new NpcScheduleConfigRepository.VecCfg(10f, 0f, 0f),
                        new NpcScheduleConfigRepository.VecCfg(20f, 0f, 0f)
                ))
        );
        ZoneContext.ZoneNpc npc = new ZoneContext.ZoneNpc(
                1, 9, 0, new SceneContext.ScenePos(0f, 0f, 0f));
        boolean moved = DailyRoutineBehavior.stepWaypointMove(npc, schedule, "g", 10f, 1000L);
        assertTrue(moved);
        assertEquals(10f, npc.getPos().getX(), 0.01f);
        assertEquals(1, npc.getWaypointIndex());
        assertEquals(20f, npc.getTargetX(), 0.01f);
    }

    @Test
    @DisplayName("从 data/NPCScheduleConfig.json 可加载样例日程")
    void loadSampleConfigFile() throws Exception {
        Path dataDir = Path.of("data");
        NpcScheduleConfigRepository repo = new NpcScheduleConfigRepository(new ObjectMapper(), dataDir.toString());
        repo.loadConfig();
        assertNotNull(repo.find(1001));
        assertEquals("PATROL", repo.find(1001).periods().get(0).behavior());
        assertNotNull(repo.find(1002));
    }
}
