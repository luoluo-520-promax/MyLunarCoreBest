package cn.itcast.demo.mylunarcore.scene;

/**
 * 位置修正平滑：服务端纠正非法位置时，记录插值目标，避免客户端瞬移感。
 */
public final class SmoothPositionCorrection {

    public record Correction(float fromX, float fromY, float fromZ,
                             float toX, float toY, float toZ,
                             long startMs, int durationMs, boolean penetrateWarn) {
        public float[] sample(long nowMs) {
            if (durationMs <= 0) {
                return new float[]{toX, toY, toZ};
            }
            float t = Math.min(1f, Math.max(0f, (nowMs - startMs) / (float) durationMs));
            // smoothstep
            t = t * t * (3f - 2f * t);
            return new float[]{
                    fromX + (toX - fromX) * t,
                    fromY + (toY - fromY) * t,
                    fromZ + (toZ - fromZ) * t
            };
        }

        public boolean finished(long nowMs) {
            return nowMs - startMs >= durationMs;
        }
    }

    private SmoothPositionCorrection() {
    }

    public static Correction start(float fromX, float fromY, float fromZ,
                                   float toX, float toY, float toZ,
                                   long nowMs, boolean penetrateWarn) {
        return new Correction(fromX, fromY, fromZ, toX, toY, toZ, nowMs, 120, penetrateWarn);
    }
}
