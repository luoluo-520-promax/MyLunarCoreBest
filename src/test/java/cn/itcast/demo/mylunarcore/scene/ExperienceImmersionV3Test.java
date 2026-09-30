package cn.itcast.demo.mylunarcore.scene;

import cn.itcast.demo.mylunarcore.anticheat.MoveSpeedGuard;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 箱庭体验三期：微交互 / 预测移动 / 热管理 / 世界时间。
 */
class ExperienceImmersionV3Test {

    @Test
    @DisplayName("环境微交互池：跑步时能检出石子或草丛")
    void envInteractDetectorProbesOnRun() {
        var hits = EnvInteractDetector.probe(1, 4.2f, 0.2f, 4.1f, 0f, 0f, MovementPhysics.RUN);
        assertFalse(hits.isEmpty());
        assertTrue(hits.size() <= 2);
        assertNotNull(hits.get(0).type().particleId());
    }

    @Test
    @DisplayName("客户端预测：100ms 窗口内轨迹可被接受")
    void playerMoveServiceAcceptsPredictionWindow() {
        LunarCoreProperties props = new LunarCoreProperties();
        PlayerMoveService svc = new PlayerMoveService(new MoveSpeedGuard(props), props);
        SceneContext.ScenePos pos = new SceneContext.ScenePos(0, 0, 0);
        SceneContext ctx = new SceneContext(1L, 1, 1, 1, pos, 1);
        ctx.setInitialized(true);
        long now = System.currentTimeMillis();
        PlayerMoveService.MoveAccept first = svc.acceptMove(ctx, 0.5f, 0, 0.5f, 5f, 5f, now, now,
                MovementPhysics.RUN);
        assertEquals(MoveSpeedGuard.MoveCheckResult.ACCEPT, first.check());

        PlayerMoveService.MoveAccept second = svc.acceptMove(ctx, 1.0f, 0, 1.0f, 5f, 5f, now + 40, now + 40,
                MovementPhysics.RUN);
        assertEquals(MoveSpeedGuard.MoveCheckResult.ACCEPT, second.check());
    }

    @Test
    @DisplayName("温度超过 45℃ 时下发强制低渲染档")
    void thermalAdjustAbove45() {
        var adjust = DevicePerfProbeService.decide(
                new DevicePerfProbeService.Probe(46.5f, 50, 80, "phone-a", System.currentTimeMillis()));
        assertTrue(adjust.isPresent());
        assertEquals(0, adjust.get().renderTier());
        assertEquals("thermal", adjust.get().reason());
        assertTrue(adjust.get().disableNpcSilhouette());
    }

    @Test
    @DisplayName("世界时间时段循环与过滤")
    void worldTimePeriodFilter() {
        assertEquals(WorldTimeService.Period.NOON, WorldTimeService.Period.DAWN.next());
        assertEquals(WorldTimeService.Period.DAWN, WorldTimeService.Period.NIGHT.next());
        assertEquals("night", WorldTimeService.Period.NIGHT.skybox());
    }

    @Test
    @DisplayName("行为类型可映射到协议枚举")
    void dailyRoutineKindMapsToProto() {
        assertNotNull(DailyRoutineBehavior.Kind.PATROL.toProto());
        assertNotNull(DailyRoutineBehavior.Kind.DESPAWN.toProto());
        assertNotNull(DailyRoutineBehavior.Kind.OPEN_SHOP.toProto());
    }

    @Test
    @DisplayName("MovementPhysics 委托微交互检测")
    void movementPhysicsDelegatesEnvDetect() {
        var list = MovementPhysics.detectEnv(1, 8f, 0f, 8f, 0f, 0f, MovementPhysics.WALK);
        assertNotNull(list);
    }
}
