package cn.itcast.demo.mylunarcore.scene;

/**
 * 箱庭移动状态机与落地/踩踏反馈：按坐标分区给出地板材质、触觉强度与脚步声效。
 * <p>环境微交互见 {@link EnvInteractDetector}。
 */
public final class MovementPhysics {

    public static final int WALK = 1;
    public static final int RUN = 2;
    public static final int JUMP = 3;
    public static final int CLIMB = 4;

    public record Feedback(int moveState, String floorMaterial, int hapticStrength, int footstepSfxId, boolean landing) {}

    private MovementPhysics() {}

    public static int normalizeState(int raw) {
        if (raw == WALK || raw == RUN || raw == JUMP || raw == CLIMB) {
            return raw;
        }
        return WALK;
    }

    public static Feedback resolve(int planeId, float x, float y, float z, int moveState, int previousState) {
        int state = normalizeState(moveState);
        String floor = floorMaterial(planeId, x, z);
        boolean landing = previousState == JUMP && state != JUMP;
        int haptic = landing ? hapticFor(floor) + 20 : hapticFor(floor);
        if (state == JUMP && !landing) {
            haptic = Math.max(10, haptic - 15);
        }
        if (state == CLIMB) {
            haptic = Math.min(100, haptic + 10);
        }
        return new Feedback(state, floor, Math.min(100, Math.max(0, haptic)), sfxFor(floor, landing), landing);
    }

    /** 委托环境微交互池，便于 MovementPhysics 统一出口。 */
    public static java.util.List<EnvInteractDetector.Trigger> detectEnv(
            int planeId, float x, float y, float z, float prevX, float prevZ, int moveState) {
        return EnvInteractDetector.probe(planeId, x, y, z, prevX, prevZ, moveState);
    }

    static String floorMaterial(int planeId, float x, float z) {
        int bucket = Math.floorMod(planeId * 31 + (int) Math.floor(x / 8f) + (int) Math.floor(z / 8f), 3);
        return switch (bucket) {
            case 0 -> "wood";
            case 1 -> "metal";
            default -> "grass";
        };
    }

    private static int hapticFor(String floor) {
        return switch (floor) {
            case "metal" -> 70;
            case "wood" -> 45;
            default -> 25;
        };
    }

    private static int sfxFor(String floor, boolean landing) {
        int base = switch (floor) {
            case "metal" -> 3101;
            case "wood" -> 3201;
            default -> 3301;
        };
        return landing ? base + 1 : base;
    }
}
