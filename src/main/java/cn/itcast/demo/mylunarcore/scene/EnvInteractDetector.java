package cn.itcast.demo.mylunarcore.scene;

import java.util.ArrayList;
import java.util.List;

/**
 * 箱庭环境微交互池：沿路检测可踢石子 / 草丛晃动 / 水面涟漪。
 * <p>仅产出表现层触发点，服务端不下发粒子数据体，只给坐标与类型。
 */
public final class EnvInteractDetector {

    public enum Type {
        KICK_PEBBLE(1, "fx_pebble_kick"),
        RUSTLE_GRASS(2, "fx_grass_rustle"),
        WATER_RIPPLE(3, "fx_water_ripple");

        private final int wire;
        private final String particleId;

        Type(int wire, String particleId) {
            this.wire = wire;
            this.particleId = particleId;
        }

        public int wire() {
            return wire;
        }

        public String particleId() {
            return particleId;
        }
    }

    public record Trigger(Type type, float x, float y, float z, int intensity) {}

    private EnvInteractDetector() {}

    /**
     * 按平面分区与位移启发式采样微交互；跑步时更容易踢到石子。
     */
    public static List<Trigger> probe(int planeId, float x, float y, float z,
                                      float prevX, float prevZ, int moveState) {
        List<Trigger> out = new ArrayList<>(2);
        float dx = x - prevX;
        float dz = z - prevZ;
        double dist = Math.sqrt(dx * dx + dz * dz);
        if (dist < 0.15) {
            return out;
        }
        int cell = Math.floorMod(planeId * 17 + (int) Math.floor(x / 4f) + (int) Math.floor(z / 4f), 10);
        boolean running = moveState == MovementPhysics.RUN;
        if (cell == 0 || cell == 3) {
            int intensity = running ? 70 : 40;
            out.add(new Trigger(Type.KICK_PEBBLE, x + 0.2f, y, z + 0.1f, intensity));
        }
        if (cell == 1 || cell == 6) {
            out.add(new Trigger(Type.RUSTLE_GRASS, x, y, z, running ? 55 : 30));
        }
        // 低洼水域启发式：Y 偏低且分区命中
        if (y < 0.5f && (cell == 2 || cell == 7 || cell == 9)) {
            out.add(new Trigger(Type.WATER_RIPPLE, x, y, z, 50));
        }
        // 限制每帧最多 2 个，避免跑图刷屏
        if (out.size() > 2) {
            return List.of(out.get(0), out.get(1));
        }
        return out;
    }
}
