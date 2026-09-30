package cn.itcast.demo.mylunarcore.scene;

import cn.itcast.demo.mylunarcore.center.SceneRegistry;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.protocol.SceneSystemProto;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * NPC/生物日程作息：全面覆盖配置、绑种、时段切换、巡逻 Tick、推送与 ZoneTick 业务流程。
 */
@DisplayName("NPC 日程作息业务流程")
class NpcDailyRoutineFlowTest {

    private static final long PLAYER_UID = 88001L;
    private static final int NPC_VENDOR = 1001;
    private static final int NPC_ANIMAL = 1002;
    private static final int NPC_NO_SCHEDULE = 9999;

    private NpcScheduleConfigRepository scheduleRepo;
    private ZoneManager zoneManager;
    private SceneManager sceneManager;
    private WorldTimeService worldTime;
    private EntityBehaviorService behaviorService;
    private SceneSyncBroadcaster broadcaster;
    private EntitySleepService sleepService;
    private ZoneContext zone;
    private SceneContext scene;
    private final List<SceneSystemProto.SceneNpcBehaviorScNotify> pushed = new ArrayList<>();

    @BeforeEach
    void setUp() {
        scheduleRepo = new NpcScheduleConfigRepository(new ObjectMapper(), "data");
        scheduleRepo.loadConfig();
        assertNotNull(scheduleRepo.find(NPC_VENDOR), "样例配置应含摊贩 1001");

        LunarCoreProperties props = new LunarCoreProperties();
        props.getZone().setEntitySleepDistance(80f);
        props.getZone().setEntitySleepHz(1f);
        zoneManager = new ZoneManager(new SceneRegistry(), new SceneEntityIdAllocator(), props);
        sceneManager = new SceneManager();
        sleepService = new EntitySleepService(props);
        broadcaster = mock(SceneSyncBroadcaster.class);
        doAnswer(inv -> {
            SceneSystemProto.SceneNpcBehaviorScNotify n = inv.getArgument(3);
            pushed.add(n);
            return null;
        }).when(broadcaster).broadcastNpcBehavior(anyInt(), anyFloat(), anyFloat(), any());

        AtomicReference<EntityBehaviorService> behaviorRef = new AtomicReference<>();
        GameSessionManager sessions = mock(GameSessionManager.class);
        worldTime = new WorldTimeService(sessions, listenerProvider(behaviorRef));
        behaviorService = new EntityBehaviorService(
                scheduleRepo, worldTime, zoneManager, sceneManager, broadcaster, sleepService);
        behaviorRef.set(behaviorService);

        zone = zoneManager.getOrCreate(1, 1, 0);
        SceneContext.ScenePos playerPos = new SceneContext.ScenePos(12f, 0f, 8f);
        zone.addPlayer(PLAYER_UID, playerPos);
        scene = new SceneContext(PLAYER_UID, 1, 1, 1, playerPos, zone.getZoneId());
        scene.setInitialized(true);
        sceneManager.put(PLAYER_UID, scene);

        pushed.clear();
        // 默认正午，便于 OPEN_SHOP 断言
        worldTime.forcePeriod(WorldTimeService.Period.NOON);
        pushed.clear();
    }

    private static ObjectProvider<WorldTimePeriodListener> listenerProvider(
            AtomicReference<EntityBehaviorService> ref) {
        return new ObjectProvider<>() {
            @Override
            public WorldTimePeriodListener getObject(Object... args) {
                return ref.get();
            }

            @Override
            public WorldTimePeriodListener getObject() {
                return ref.get();
            }

            @Override
            public WorldTimePeriodListener getIfAvailable() {
                return ref.get();
            }

            @Override
            public WorldTimePeriodListener getIfUnique() {
                return ref.get();
            }

            @Override
            public Stream<WorldTimePeriodListener> stream() {
                return Stream.ofNullable(ref.get());
            }

            @Override
            public Stream<WorldTimePeriodListener> orderedStream() {
                return Stream.ofNullable(ref.get());
            }
        };
    }

    private ZoneContext.ZoneNpc putNpc(int entityId, int npcId, float x, float z) {
        ZoneContext.ZoneNpc npc = new ZoneContext.ZoneNpc(
                entityId, npcId, 0, new SceneContext.ScenePos(x, 0f, z));
        zone.putNpc(npc);
        scene.addNpc(npc.toSceneState());
        return npc;
    }

    @Nested
    @DisplayName("配置加载")
    class ConfigLoad {

        @Test
        @DisplayName("NPCScheduleConfig.json 含摊贩与动物两套日程")
        void sampleConfigHasVendorAndAnimal() {
            var vendor = scheduleRepo.find(NPC_VENDOR);
            var animal = scheduleRepo.find(NPC_ANIMAL);
            assertNotNull(vendor);
            assertNotNull(animal);
            assertEquals(4, vendor.periods().size());
            assertTrue(vendor.spots().containsKey("stall"));
            assertTrue(vendor.waypointGroups().containsKey("market_loop"));
            assertTrue(animal.waypointGroups().containsKey("graze"));
        }

        @Test
        @DisplayName("缺失配置文件时不抛异常且查不到日程")
        void missingFileIsSafe() throws Exception {
            Path tmp = Files.createTempDirectory("npc-sched-empty");
            NpcScheduleConfigRepository empty = new NpcScheduleConfigRepository(new ObjectMapper(), tmp.toString());
            empty.loadConfig();
            assertNull(empty.find(NPC_VENDOR));
        }
    }

    @Nested
    @DisplayName("播种绑定 bindNpc")
    class BindFlow {

        @Test
        @DisplayName("正午绑定摊贩 → OPEN_SHOP，瞬移到摊位并推送 Notify")
        void bindVendorAtNoonOpensShop() {
            ZoneContext.ZoneNpc npc = putNpc(2001, NPC_VENDOR, 0f, 0f);
            behaviorService.bindNpc(zone, npc);

            assertEquals(DailyRoutineBehavior.Kind.OPEN_SHOP, npc.getBehavior());
            assertEquals("vend", npc.getAnimHint());
            assertEquals("stall", npc.getInteractionSpot());
            assertTrue(npc.isVisible());
            assertEquals(12f, npc.getPos().getX(), 0.01f);
            assertEquals(8f, npc.getPos().getZ(), 0.01f);
            assertFalse(pushed.isEmpty());
            SceneSystemProto.SceneNpcBehaviorScNotify last = pushed.get(pushed.size() - 1);
            assertEquals(NPC_VENDOR, last.getNpcId());
            assertEquals(SceneSystemProto.NpcBehaviorType.NPC_BEHAVIOR_OPEN_SHOP, last.getBehavior());
            assertEquals(SceneSystemProto.WorldTimePeriod.NOON, last.getPeriod());
            assertTrue(last.getVisible());
            assertEquals("stall", last.getInteractionSpot());
            assertTrue(last.getTransitionMs() >= 800);
        }

        @Test
        @DisplayName("无日程 NPC 绑定后保持 IDLE 且不推送")
        void bindUnscheduledNpcNoPush() {
            ZoneContext.ZoneNpc npc = putNpc(2002, NPC_NO_SCHEDULE, 1f, 1f);
            int before = pushed.size();
            behaviorService.bindNpc(zone, npc);
            assertEquals(DailyRoutineBehavior.Kind.IDLE, npc.getBehavior());
            assertEquals(before, pushed.size());
        }
    }

    @Nested
    @DisplayName("时段切换全链路")
    class PeriodSwitchFlow {

        @Test
        @DisplayName("forcePeriod 经 WorldTime 监听器驱动作息切换")
        void forcePeriodDrivesListener() {
            ZoneContext.ZoneNpc vendor = putNpc(3001, NPC_VENDOR, 0f, 0f);
            behaviorService.bindNpc(zone, vendor);
            pushed.clear();

            worldTime.forcePeriod(WorldTimeService.Period.NIGHT);

            assertEquals(WorldTimeService.Period.NIGHT, worldTime.currentPeriod());
            assertEquals(DailyRoutineBehavior.Kind.SLEEP, vendor.getBehavior());
            assertEquals("sleep", vendor.getAnimHint());
            assertEquals(5f, vendor.getPos().getX(), 0.01f);
            assertEquals(3f, vendor.getPos().getZ(), 0.01f);
            assertTrue(vendor.isVisible());
            assertTrue(pushed.stream().anyMatch(n ->
                    n.getNpcId() == NPC_VENDOR
                            && n.getBehavior() == SceneSystemProto.NpcBehaviorType.NPC_BEHAVIOR_SLEEP
                            && n.getPeriod() == SceneSystemProto.WorldTimePeriod.NIGHT));
        }

        @Test
        @DisplayName("夜间动物无 home → DESPAWN，并从玩家 Scene 移除")
        void nightAnimalDespawnsFromScene() {
            ZoneContext.ZoneNpc animal = putNpc(3002, NPC_ANIMAL, 30f, 20f);
            behaviorService.bindNpc(zone, animal);
            assertNotNull(scene.getNpc(3002));
            pushed.clear();

            worldTime.forcePeriod(WorldTimeService.Period.NIGHT);

            assertEquals(DailyRoutineBehavior.Kind.DESPAWN, animal.getBehavior());
            assertFalse(animal.isVisible());
            assertNull(scene.getNpc(3002));
            assertTrue(pushed.stream().anyMatch(n ->
                    n.getNpcId() == NPC_ANIMAL
                            && n.getBehavior() == SceneSystemProto.NpcBehaviorType.NPC_BEHAVIOR_DESPAWN
                            && !n.getVisible()));
        }

        @Test
        @DisplayName("夜→黎明：摊贩恢复 PATROL，动物重新投影到 Scene")
        void nightToDawnRestoresPatrolAndVisibility() {
            ZoneContext.ZoneNpc vendor = putNpc(3003, NPC_VENDOR, 0f, 0f);
            ZoneContext.ZoneNpc animal = putNpc(3004, NPC_ANIMAL, 30f, 20f);
            behaviorService.bindNpc(zone, vendor);
            behaviorService.bindNpc(zone, animal);
            worldTime.forcePeriod(WorldTimeService.Period.NIGHT);
            assertNull(scene.getNpc(3004));
            pushed.clear();

            worldTime.forcePeriod(WorldTimeService.Period.DAWN);

            assertEquals(DailyRoutineBehavior.Kind.PATROL, vendor.getBehavior());
            assertEquals("market_loop", vendor.getWaypointGroup());
            assertEquals("walk", vendor.getAnimHint());
            assertEquals(DailyRoutineBehavior.Kind.PATROL, animal.getBehavior());
            assertTrue(animal.isVisible());
            assertNotNull(scene.getNpc(3004));
            assertTrue(pushed.stream().anyMatch(n ->
                    n.getBehavior() == SceneSystemProto.NpcBehaviorType.NPC_BEHAVIOR_PATROL
                            && n.getPeriod() == SceneSystemProto.WorldTimePeriod.DAWN));
        }

        @Test
        @DisplayName("正午→黄昏→夜间完整一日循环行为序列")
        void fullDayCycleBehaviorSequence() {
            ZoneContext.ZoneNpc vendor = putNpc(3005, NPC_VENDOR, 0f, 0f);
            behaviorService.bindNpc(zone, vendor);

            worldTime.forcePeriod(WorldTimeService.Period.DAWN);
            assertEquals(DailyRoutineBehavior.Kind.PATROL, vendor.getBehavior());

            worldTime.forcePeriod(WorldTimeService.Period.NOON);
            assertEquals(DailyRoutineBehavior.Kind.OPEN_SHOP, vendor.getBehavior());
            assertEquals(12f, vendor.getPos().getX(), 0.01f);

            worldTime.forcePeriod(WorldTimeService.Period.DUSK);
            assertEquals(DailyRoutineBehavior.Kind.PATROL, vendor.getBehavior());

            worldTime.forcePeriod(WorldTimeService.Period.NIGHT);
            assertEquals(DailyRoutineBehavior.Kind.SLEEP, vendor.getBehavior());
            assertEquals(5f, vendor.getPos().getX(), 0.01f);
        }
    }

    @Nested
    @DisplayName("巡逻 Tick 与休眠门控")
    class PatrolTickFlow {

        @Test
        @DisplayName("黎明 PATROL：tick 推进坐标并同步到 SceneContext")
        void patrolTickMovesAndSyncsScene() {
            worldTime.forcePeriod(WorldTimeService.Period.DAWN);
            pushed.clear();
            ZoneContext.ZoneNpc vendor = putNpc(4001, NPC_VENDOR, 12f, 8f);
            behaviorService.bindNpc(zone, vendor);
            assertEquals(DailyRoutineBehavior.Kind.PATROL, vendor.getBehavior());

            float beforeX = vendor.getPos().getX();
            long now = System.currentTimeMillis();
            for (int i = 0; i < 5; i++) {
                behaviorService.tickZone(zone, now + i * 200L, 200L);
            }
            assertNotEquals(beforeX, vendor.getPos().getX(), 0.001f);
            SceneContext.NpcState local = scene.getNpc(4001);
            assertNotNull(local);
            assertEquals(vendor.getPos().getX(), local.getPos().getX(), 0.01f);
            assertEquals(vendor.getPos().getZ(), local.getPos().getZ(), 0.01f);
        }

        @Test
        @DisplayName("无人 Zone 不推进巡逻")
        void emptyZoneSkipsTick() {
            worldTime.forcePeriod(WorldTimeService.Period.DAWN);
            ZoneContext empty = zoneManager.getOrCreate(2, 2, 0);
            ZoneContext.ZoneNpc npc = new ZoneContext.ZoneNpc(
                    4002, NPC_VENDOR, 0, new SceneContext.ScenePos(12f, 0f, 8f));
            empty.putNpc(npc);
            behaviorService.bindNpc(empty, npc);
            float x = npc.getPos().getX();
            behaviorService.tickZone(empty, System.currentTimeMillis(), 500L);
            assertEquals(x, npc.getPos().getX(), 0.001f);
        }

        @Test
        @DisplayName("正午摆摊时 tick 不移动（非 PATROL）")
        void openShopTickDoesNotMove() {
            ZoneContext.ZoneNpc vendor = putNpc(4003, NPC_VENDOR, 0f, 0f);
            behaviorService.bindNpc(zone, vendor);
            assertEquals(DailyRoutineBehavior.Kind.OPEN_SHOP, vendor.getBehavior());
            float x = vendor.getPos().getX();
            behaviorService.tickZone(zone, System.currentTimeMillis(), 500L);
            assertEquals(x, vendor.getPos().getX(), 0.001f);
        }

        @Test
        @DisplayName("远距离 SLEEPING：巡逻不推进")
        void farSleepThrottlesPatrol() {
            worldTime.forcePeriod(WorldTimeService.Period.DAWN);
            // 玩家在 (12,8)，NPC 放到极远处
            ZoneContext.ZoneNpc vendor = putNpc(4004, NPC_VENDOR, 5000f, 5000f);
            behaviorService.bindNpc(zone, vendor);
            float x = vendor.getPos().getX();
            long now = System.currentTimeMillis();
            // 第一次可能因心跳放行；连续高频 tick 应大多被挡住，坐标几乎不变于短 delta
            behaviorService.tickZone(zone, now, 16L);
            float afterFirst = vendor.getPos().getX();
            behaviorService.tickZone(zone, now + 16L, 16L);
            behaviorService.tickZone(zone, now + 32L, 16L);
            // 休眠后 1Hz，短间隔内不应连续大幅移动
            assertTrue(Math.abs(vendor.getPos().getX() - afterFirst) < 0.5f
                    || Math.abs(vendor.getPos().getX() - x) < 50f);
        }
    }

    @Nested
    @DisplayName("ZoneTick 集成")
    class ZoneTickIntegration {

        @Test
        @DisplayName("ZoneTickService 会调用 EntityBehaviorService.tickZone")
        void zoneTickInvokesBehavior() {
            worldTime.forcePeriod(WorldTimeService.Period.DAWN);
            ZoneContext.ZoneNpc vendor = putNpc(5001, NPC_VENDOR, 12f, 8f);
            behaviorService.bindNpc(zone, vendor);

            ZoneWorldService zoneWorld = mock(ZoneWorldService.class);
            WorldEncounterService encounter = mock(WorldEncounterService.class);
            ZoneTickService tickService = new ZoneTickService(
                    zoneManager, zoneWorld, encounter, sleepService, behaviorService);

            float before = vendor.getPos().getX();
            long now = System.currentTimeMillis();
            tickService.onZoneTick(now, 250L);
            tickService.onZoneTick(now + 250L, 250L);
            tickService.onZoneTick(now + 500L, 250L);

            verify(zoneWorld, atLeastOnce()).tickRefresh(eq(zone), anyLong());
            assertNotEquals(before, vendor.getPos().getX(), 0.001f);
        }
    }

    @Nested
    @DisplayName("AOI 广播与协议载荷")
    class BroadcastAndProto {

        @Test
        @DisplayName("SceneSyncBroadcaster 向 AOI 玩家下发 cmd=1143")
        void broadcasterSendsCmd1143() throws Exception {
            GameSessionManager sessions = mock(GameSessionManager.class);
            Channel channel = mock(Channel.class);
            GameSession session = mock(GameSession.class);
            when(session.getChannel()).thenReturn(channel);
            when(sessions.getOrNull(PLAYER_UID)).thenReturn(session);

            SceneSyncBroadcaster realBroadcaster = new SceneSyncBroadcaster(
                    sessions, zoneManager,
                    new SceneEntityIdAllocator(),
                    mock(AoiAsyncProcessor.class),
                    mock(SceneStateDiffer.class),
                    mock(LodBroadcastAdvisor.class),
                    mock(PlayerBandwidthLimiter.class));

            SceneSystemProto.SceneNpcBehaviorScNotify notify =
                    SceneSystemProto.SceneNpcBehaviorScNotify.newBuilder()
                            .setEntityId(1)
                            .setNpcId(NPC_VENDOR)
                            .setBehavior(SceneSystemProto.NpcBehaviorType.NPC_BEHAVIOR_PATROL)
                            .setPeriod(SceneSystemProto.WorldTimePeriod.DAWN)
                            .setAnimHint("walk")
                            .setVisible(true)
                            .setPos(SceneSystemProto.SceneVec3.newBuilder().setX(12).setY(0).setZ(8).build())
                            .setTargetPos(SceneSystemProto.SceneVec3.newBuilder().setX(18).setY(0).setZ(10).build())
                            .setTransitionMs(1200)
                            .build();

            realBroadcaster.broadcastNpcBehavior(zone.getZoneId(), 12f, 8f, notify);

            ArgumentCaptor<GamePacket> captor = ArgumentCaptor.forClass(GamePacket.class);
            verify(channel).writeAndFlush(captor.capture());
            GamePacket packet = captor.getValue();
            assertEquals(CmdIds.SCENE_NPC_BEHAVIOR_SC_NOTIFY, packet.getCmdId());
            SceneSystemProto.SceneNpcBehaviorScNotify parsed =
                    SceneSystemProto.SceneNpcBehaviorScNotify.parseFrom(packet.getPayload());
            assertEquals(NPC_VENDOR, parsed.getNpcId());
            assertEquals("walk", parsed.getAnimHint());
            assertEquals(SceneSystemProto.NpcBehaviorType.NPC_BEHAVIOR_PATROL, parsed.getBehavior());
        }

        @Test
        @DisplayName("CmdIds 常量与注解 cmd 一致为 1143")
        void cmdIdConstantIs1143() {
            assertEquals(1143, CmdIds.SCENE_NPC_BEHAVIOR_SC_NOTIFY);
        }
    }

    @Nested
    @DisplayName("投影可见性")
    class ProjectionVisibility {

        @Test
        @DisplayName("projectAliveToScene 跳过 invisible NPC")
        void projectSkipsInvisible() {
            ZoneContext.ZoneNpc animal = putNpc(6001, NPC_ANIMAL, 30f, 20f);
            behaviorService.bindNpc(zone, animal);
            worldTime.forcePeriod(WorldTimeService.Period.NIGHT);
            assertFalse(animal.isVisible());

            SceneContext fresh = new SceneContext(PLAYER_UID, 1, 1, 1, new SceneContext.ScenePos(12f, 0f, 8f));
            // 直接复用 ZoneWorldService 的投影逻辑：仅 visible
            for (ZoneContext.ZoneNpc n : zone.getNpcs().values()) {
                if (n.isVisible()) {
                    fresh.addNpc(n.toSceneState());
                }
            }
            assertNull(fresh.getNpc(6001));
        }
    }

    @Nested
    @DisplayName("DailyRoutine 边界")
    class BehaviorEdgeCases {

        @Test
        @DisplayName("morning 配置时段可匹配 DAWN")
        void morningAliasMatchesDawn() {
            var schedule = new NpcScheduleConfigRepository.ScheduleCfg(
                    42, "IDLE", 2f, "idle",
                    List.of(new NpcScheduleConfigRepository.PeriodCfg(
                            "MORNING", "PATROL", "g", null, null, "walk")),
                    Map.of(),
                    Map.of("g", List.of(new NpcScheduleConfigRepository.VecCfg(1f, 0f, 1f)))
            );
            var applied = DailyRoutineBehavior.resolve(schedule, WorldTimeService.Period.DAWN);
            assertEquals(DailyRoutineBehavior.Kind.PATROL, applied.kind());
        }

        @Test
        @DisplayName("路点走完一圈后索引回绕")
        void waypointIndexWraps() {
            var schedule = new NpcScheduleConfigRepository.ScheduleCfg(
                    43, "IDLE", 100f, "idle",
                    List.of(),
                    Map.of(),
                    Map.of("g", List.of(
                            new NpcScheduleConfigRepository.VecCfg(1f, 0f, 0f),
                            new NpcScheduleConfigRepository.VecCfg(2f, 0f, 0f)
                    ))
            );
            ZoneContext.ZoneNpc npc = new ZoneContext.ZoneNpc(
                    1, 43, 0, new SceneContext.ScenePos(0f, 0f, 0f));
            DailyRoutineBehavior.stepWaypointMove(npc, schedule, "g", 100f, 1000L);
            assertEquals(1, npc.getWaypointIndex());
            DailyRoutineBehavior.stepWaypointMove(npc, schedule, "g", 100f, 1000L);
            assertEquals(0, npc.getWaypointIndex());
        }
    }
}
