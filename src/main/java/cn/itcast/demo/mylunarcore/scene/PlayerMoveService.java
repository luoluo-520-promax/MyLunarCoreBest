package cn.itcast.demo.mylunarcore.scene;

import cn.itcast.demo.mylunarcore.anticheat.MoveSpeedGuard;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.exploration.PuzzleStateService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 移动权威与客户端预测：校验「轨迹向量」而非逐帧精确坐标，允许约 100ms 本地先行。
 * 接受移动后探测压力板类谜题。
 */
@Service
public class PlayerMoveService {

    public static final int PREDICTION_WINDOW_MS = 100;

    public record MoveAccept(MoveSpeedGuard.MoveCheckResult check, boolean predictionAccepted,
                             float authX, float authY, float authZ,
                             List<EnvInteractDetector.Trigger> microInteracts) {}

    private final MoveSpeedGuard moveSpeedGuard;
    private final LunarCoreProperties properties;
    private final PuzzleStateService puzzleStateService;

    public PlayerMoveService(MoveSpeedGuard moveSpeedGuard, LunarCoreProperties properties) {
        this(moveSpeedGuard, properties, null);
    }

    public PlayerMoveService(MoveSpeedGuard moveSpeedGuard,
                             LunarCoreProperties properties,
                             ObjectProvider<PuzzleStateService> puzzleProvider) {
        this.moveSpeedGuard = moveSpeedGuard;
        this.properties = properties;
        this.puzzleStateService = puzzleProvider == null ? null : puzzleProvider.getIfAvailable();
    }

    /**
     * 先用轨迹向量做宽松校验；超预测窗口或向量非法时回落精确速度校验。
     */
    public MoveAccept acceptMove(SceneContext ctx, float x, float y, float z,
                                 float velocityX, float velocityZ, long clientTickMs, long nowMillis,
                                 int moveState) {
        SceneContext.ScenePos pos = ctx.getPlayerPos();
        float prevX = pos.getX();
        float prevY = pos.getY();
        float prevZ = pos.getZ();

        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(z)
                || Math.abs(x) > 10000 || Math.abs(y) > 10000 || Math.abs(z) > 10000) {
            return new MoveAccept(MoveSpeedGuard.MoveCheckResult.REJECT_INVALID, false,
                    prevX, prevY, prevZ, List.of());
        }

        boolean predictionOk = validateTrajectory(ctx, x, z, velocityX, velocityZ, clientTickMs, nowMillis);
        MoveSpeedGuard.MoveCheckResult check;
        boolean predictionAccepted = false;
        if (predictionOk) {
            // 预测窗口内：放宽最小间隔，仍走速度守卫但把突发系数略抬高
            check = moveSpeedGuard.validateAndMaybeApply(ctx, x, y, z, nowMillis);
            if (check == MoveSpeedGuard.MoveCheckResult.REJECT_TOO_FREQUENT
                    || check == MoveSpeedGuard.MoveCheckResult.REJECT_TOO_FAST) {
                // 100ms 先行：在窗口内用权威插值接受客户端点
                if (withinPredictionBudget(ctx, x, z, nowMillis)) {
                    applyPredicted(ctx, x, y, z, nowMillis);
                    check = MoveSpeedGuard.MoveCheckResult.ACCEPT;
                    predictionAccepted = true;
                }
            } else if (check == MoveSpeedGuard.MoveCheckResult.ACCEPT
                    || check == MoveSpeedGuard.MoveCheckResult.CORRECTED_COLLISION) {
                predictionAccepted = clientTickMs > 0;
            }
        } else {
            check = moveSpeedGuard.validateAndMaybeApply(ctx, x, y, z, nowMillis);
        }

        List<EnvInteractDetector.Trigger> micros = List.of();
        if (check == MoveSpeedGuard.MoveCheckResult.ACCEPT
                || check == MoveSpeedGuard.MoveCheckResult.CORRECTED_COLLISION) {
            SceneContext.ScenePos after = ctx.getPlayerPos();
            micros = EnvInteractDetector.probe(ctx.getPlaneId(), after.getX(), after.getY(), after.getZ(),
                    prevX, prevZ, moveState);
            if (puzzleStateService != null) {
                puzzleStateService.onPlayerPosition((int) ctx.getPlayerUid(), ctx.getPlaneId(), ctx.getFloorId(),
                        after.getX(), after.getY(), after.getZ());
            }
        }
        SceneContext.ScenePos auth = ctx.getPlayerPos();
        return new MoveAccept(check, predictionAccepted, auth.getX(), auth.getY(), auth.getZ(), micros);
    }

    public int predictionWindowMs() {
        return PREDICTION_WINDOW_MS;
    }

    /**
     * 轨迹向量粗校验：客户端速度方向与位移大致同向，且时间戳落在预测窗口附近。
     */
    private boolean validateTrajectory(SceneContext ctx, float x, float z,
                                       float velocityX, float velocityZ,
                                       long clientTickMs, long nowMillis) {
        if (clientTickMs <= 0) {
            return false;
        }
        long skew = Math.abs(nowMillis - clientTickMs);
        if (skew > PREDICTION_WINDOW_MS * 2L) {
            return false;
        }
        if (!Float.isFinite(velocityX) || !Float.isFinite(velocityZ)) {
            return false;
        }
        SceneContext.ScenePos pos = ctx.getPlayerPos();
        float dx = x - pos.getX();
        float dz = z - pos.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.01) {
            return true;
        }
        double speed = Math.sqrt(velocityX * velocityX + velocityZ * velocityZ);
        if (speed < 0.01) {
            return dist < 0.5;
        }
        // 位移与速度向量夹角不应接近反向
        double dot = dx * velocityX + dz * velocityZ;
        return dot >= -0.1 * dist * speed;
    }

    private boolean withinPredictionBudget(SceneContext ctx, float x, float z, long nowMillis) {
        long last = ctx.getLastMoveAcceptedMillis();
        if (last > 0 && nowMillis - last > PREDICTION_WINDOW_MS) {
            return false;
        }
        SceneContext.ScenePos pos = ctx.getPlayerPos();
        float dx = x - pos.getX();
        float dz = z - pos.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        LunarCoreProperties.AntiCheatProperties anti = properties.getAntiCheat();
        double maxDist = anti.getMaxMoveSpeed() * anti.getMoveSpeedBurstFactor()
                * (PREDICTION_WINDOW_MS / 1000.0) * 1.25;
        return dist <= maxDist + 0.05;
    }

    private void applyPredicted(SceneContext ctx, float x, float y, float z, long nowMillis) {
        SceneContext.ScenePos pos = ctx.getPlayerPos();
        pos.setX(x);
        pos.setY(y);
        pos.setZ(z);
        ctx.setLastMoveAcceptedMillis(nowMillis);
    }
}
