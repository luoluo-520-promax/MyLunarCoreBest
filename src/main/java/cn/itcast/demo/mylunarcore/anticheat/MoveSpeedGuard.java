package cn.itcast.demo.mylunarcore.anticheat;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.scene.SceneCollisionProxy;
import cn.itcast.demo.mylunarcore.scene.SceneContext;
import cn.itcast.demo.mylunarcore.scene.SmoothPositionCorrection;
import org.springframework.stereotype.Component;

/**
 * 移动反作弊：速度守卫 + 场景碰撞体校验；非法位置回退到最近合法点。
 */
@Component
public class MoveSpeedGuard {

    public enum MoveCheckResult {
        ACCEPT,
        REJECT_TOO_FAST,
        REJECT_TOO_FREQUENT,
        REJECT_INVALID,
        /** 碰撞阻挡：位置已纠正到最近合法点，仍视为“接受纠正后的位置”。 */
        CORRECTED_COLLISION
    }

    public record MoveOutcome(MoveCheckResult result, SmoothPositionCorrection.Correction smooth,
                              boolean penetrateWarn) {
        public static MoveOutcome of(MoveCheckResult r) {
            return new MoveOutcome(r, null, false);
        }
    }

    private final LunarCoreProperties properties;
    private final SceneCollisionProxy collisionProxy;

    public MoveSpeedGuard(LunarCoreProperties properties, SceneCollisionProxy collisionProxy) {
        this.properties = properties;
        this.collisionProxy = collisionProxy;
    }

    /** 单测便捷：内建默认碰撞代理。 */
    public MoveSpeedGuard(LunarCoreProperties properties) {
        this(properties, new SceneCollisionProxy(properties));
    }

    /**
     * 校验客户端申报坐标；拒绝时不修改 ScenePos。
     */
    public MoveCheckResult validateAndMaybeApply(SceneContext ctx, float x, float y, float z, long nowMillis) {
        return validateDetailed(ctx, x, y, z, nowMillis).result();
    }

    public MoveOutcome validateDetailed(SceneContext ctx, float x, float y, float z, long nowMillis) {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)
                || Math.abs(x) > 10000 || Math.abs(y) > 10000 || Math.abs(z) > 10000) {
            return MoveOutcome.of(MoveCheckResult.REJECT_INVALID);
        }
        LunarCoreProperties.AntiCheatProperties anti = properties.getAntiCheat();
        SceneContext.ScenePos pos = ctx.getPlayerPos();
        float fromX = pos.getX();
        float fromY = pos.getY();
        float fromZ = pos.getZ();

        if (!anti.isMoveSpeedCheckEnabled()) {
            return applyWithCollision(ctx, fromX, fromY, fromZ, x, y, z, nowMillis);
        }

        long lastMs = ctx.getLastMoveAcceptedMillis();
        if (lastMs > 0 && nowMillis - lastMs < anti.getMinMoveIntervalMs()) {
            return MoveOutcome.of(MoveCheckResult.REJECT_TOO_FREQUENT);
        }

        if (lastMs > 0) {
            float dx = x - fromX;
            float dz = z - fromZ;
            double dist = Math.sqrt(dx * dx + dz * dz);
            double dtSec = Math.max(0.001, (nowMillis - lastMs) / 1000.0);
            double maxDist = anti.getMaxMoveSpeed() * anti.getMoveSpeedBurstFactor() * dtSec;
            if (dist > maxDist + 0.05) {
                return MoveOutcome.of(MoveCheckResult.REJECT_TOO_FAST);
            }
        }
        return applyWithCollision(ctx, fromX, fromY, fromZ, x, y, z, nowMillis);
    }

    private MoveOutcome applyWithCollision(SceneContext ctx, float fromX, float fromY, float fromZ,
                                           float x, float y, float z, long nowMillis) {
        SceneCollisionProxy.CollisionResult col = collisionProxy.validateMove(
                ctx.getPlaneId(), fromX, fromY, fromZ, x, y, z);
        if (col.legal()) {
            apply(ctx, x, y, z, nowMillis);
            collisionProxy.clearPenetrate(ctx.getPlayerUid());
            return MoveOutcome.of(MoveCheckResult.ACCEPT);
        }
        int warns = collisionProxy.recordPenetrate(ctx.getPlayerUid());
        boolean warn = warns > 0;
        apply(ctx, col.correctedX(), col.correctedY(), col.correctedZ(), nowMillis);
        SmoothPositionCorrection.Correction smooth = null;
        if (properties.getAntiCheat().isSmoothPositionCorrection()) {
            smooth = SmoothPositionCorrection.start(fromX, fromY, fromZ,
                    col.correctedX(), col.correctedY(), col.correctedZ(), nowMillis, warn);
        }
        return new MoveOutcome(MoveCheckResult.CORRECTED_COLLISION, smooth, warn);
    }

    private static void apply(SceneContext ctx, float x, float y, float z, long nowMillis) {
        SceneContext.ScenePos pos = ctx.getPlayerPos();
        pos.setX(x);
        pos.setY(y);
        pos.setZ(z);
        ctx.setLastMoveAcceptedMillis(nowMillis);
    }
}
