package cn.itcast.demo.mylunarcore.anticheat;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.scene.SceneContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link MoveSpeedGuard} 测试：根据 maxMoveSpeed 与时间间隔判定客户端位移是否可接受。
 * 场景初始点 (0,0,0)；maxMoveSpeed=10、burstFactor=1、minInterval=0，便于用整数毫秒推速度。
 */
@DisplayName("MoveSpeedGuard 移动速度校验测试")
class MoveSpeedGuardTest {

    private MoveSpeedGuard guard;
    private SceneContext scene;

    /** 配置限速 10 单位/秒，并构造 plane/floor 均为 1 的空场景。 */
    @BeforeEach
    void setUp() {
        LunarCoreProperties properties = new LunarCoreProperties();
        properties.getAntiCheat().setMaxMoveSpeed(10f);
        properties.getAntiCheat().setMoveSpeedBurstFactor(1.0f); // 不允许短时爆发加成
        properties.getAntiCheat().setMinMoveIntervalMs(0); // 关闭最小间隔限制，专注测速度
        guard = new MoveSpeedGuard(properties);
        scene = new SceneContext(1L, 1, 1, 1, new SceneContext.ScenePos(0f, 0f, 0f));
    }

    /**
     * 1 秒内移动 5 单位（速度 5 &lt; 10）应 ACCEPT，且服务器坐标更新到 x=5。
     * 首包在原点打点时间戳，建立基准。
     */
    @Test
    @DisplayName("合理速度应接受")
    void acceptNormalSpeed() {
        long t0 = 1_000L;
        assertEquals(MoveSpeedGuard.MoveCheckResult.ACCEPT,
                guard.validateAndMaybeApply(scene, 0f, 0f, 0f, t0));
        assertEquals(MoveSpeedGuard.MoveCheckResult.ACCEPT,
                guard.validateAndMaybeApply(scene, 5f, 0f, 0f, t0 + 1000L));
        assertEquals(5f, scene.getPlayerPos().getX()); // 接受后写回服务器位置
    }

    /**
     * 100ms 内位移 500（等价速度远超 10）应 REJECT_TOO_FAST，且 Scene 坐标仍停在上次接受的 0。
     */
    @Test
    @DisplayName("瞬移超速应拒绝并保持服务器坐标")
    void rejectTeleport() {
        long t0 = 1_000L;
        guard.validateAndMaybeApply(scene, 0f, 0f, 0f, t0); // 建立原点基准
        MoveSpeedGuard.MoveCheckResult result =
                guard.validateAndMaybeApply(scene, 500f, 0f, 0f, t0 + 100L);
        assertEquals(MoveSpeedGuard.MoveCheckResult.REJECT_TOO_FAST, result);
        assertEquals(0f, scene.getPlayerPos().getX()); // 拒绝时不应用客户端坐标
    }
}
