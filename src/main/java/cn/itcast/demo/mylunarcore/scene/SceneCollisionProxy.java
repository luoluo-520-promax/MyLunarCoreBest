package cn.itcast.demo.mylunarcore.scene;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务端碰撞体代理：角色胶囊体 + 场景静态阻挡多边形。
 * 移动校验时做点/胶囊相交测试，非法则回退到最近合法点。
 */
@Component
public class SceneCollisionProxy {

    public record Capsule(float radius, float height) {
    }

    /** 2D 阻挡多边形（XZ 平面，顺时针或逆时针均可）。 */
    public record BlockerPolygon(List<float[]> vertices) {
        public BlockerPolygon {
            vertices = vertices == null ? List.of() : List.copyOf(vertices);
        }
    }

    public record CollisionResult(boolean legal, float correctedX, float correctedY, float correctedZ,
                                  boolean penetrated, String reason) {
        public static CollisionResult ok(float x, float y, float z) {
            return new CollisionResult(true, x, y, z, false, "");
        }

        public static CollisionResult blocked(float x, float y, float z, String reason) {
            return new CollisionResult(false, x, y, z, true, reason == null ? "blocked" : reason);
        }
    }

    private final LunarCoreProperties properties;
    /** planeId → 阻挡多边形列表（切图时下发简化版给客户端）。 */
    private final ConcurrentHashMap<Integer, List<BlockerPolygon>> planeBlockers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Integer> penetrateWarnCount = new ConcurrentHashMap<>();

    public SceneCollisionProxy(LunarCoreProperties properties) {
        this.properties = properties;
        // 默认样例阻挡：主城中央矩形墙，便于联调
        registerPlaneBlockers(10001, List.of(
                new BlockerPolygon(List.of(
                        new float[]{-2f, -2f}, new float[]{2f, -2f},
                        new float[]{2f, 2f}, new float[]{-2f, 2f}))));
    }

    public Capsule playerCapsule() {
        return new Capsule(
                Math.max(0.1f, properties.getAntiCheat().getCapsuleRadius()),
                Math.max(0.5f, properties.getAntiCheat().getCapsuleHeight()));
    }

    public void registerPlaneBlockers(int planeId, List<BlockerPolygon> polygons) {
        planeBlockers.put(planeId, polygons == null ? List.of() : List.copyOf(polygons));
    }

    /** 切图下发：当前区块阻挡多边形简化数据。 */
    public List<BlockerPolygon> blockersForClient(int planeId) {
        return planeBlockers.getOrDefault(planeId, List.of());
    }

    /**
     * 校验目标点是否合法；非法则沿 from→to 二分回退到最近合法点。
     */
    public CollisionResult validateMove(int planeId, float fromX, float fromY, float fromZ,
                                        float toX, float toY, float toZ) {
        if (!properties.getAntiCheat().isCollisionCheckEnabled()) {
            return CollisionResult.ok(toX, toY, toZ);
        }
        Capsule cap = playerCapsule();
        if (!collides(planeId, toX, toZ, cap.radius())) {
            return CollisionResult.ok(toX, toY, toZ);
        }
        // 二分回退到最近合法点
        float lx = fromX, ly = fromY, lz = fromZ;
        float hx = toX, hy = toY, hz = toZ;
        for (int i = 0; i < 8; i++) {
            float mx = (lx + hx) * 0.5f;
            float my = (ly + hy) * 0.5f;
            float mz = (lz + hz) * 0.5f;
            if (collides(planeId, mx, mz, cap.radius())) {
                hx = mx;
                hy = my;
                hz = mz;
            } else {
                lx = mx;
                ly = my;
                lz = mz;
            }
        }
        return CollisionResult.blocked(lx, ly, lz, "static_blocker");
    }

    public int recordPenetrate(long playerUid) {
        return penetrateWarnCount.compute(playerUid, (k, v) -> v == null ? 1 : v + 1);
    }

    public void clearPenetrate(long playerUid) {
        penetrateWarnCount.remove(playerUid);
    }

    public boolean shouldKick(long playerUid) {
        Integer n = penetrateWarnCount.get(playerUid);
        return n != null && n >= properties.getAntiCheat().getCollisionWarnThreshold();
    }

    private boolean collides(int planeId, float x, float z, float radius) {
        List<BlockerPolygon> list = planeBlockers.get(planeId);
        if (list == null || list.isEmpty()) {
            return false;
        }
        for (BlockerPolygon poly : list) {
            if (circleIntersectsPolygon(x, z, radius, poly)) {
                return true;
            }
        }
        return false;
    }

    /** 圆心在多边形内，或圆与任一边相交。 */
    static boolean circleIntersectsPolygon(float cx, float cz, float r, BlockerPolygon poly) {
        List<float[]> vs = poly.vertices();
        if (vs.size() < 3) {
            return false;
        }
        if (pointInPolygon(cx, cz, vs)) {
            return true;
        }
        for (int i = 0; i < vs.size(); i++) {
            float[] a = vs.get(i);
            float[] b = vs.get((i + 1) % vs.size());
            if (distancePointToSegment(cx, cz, a[0], a[1], b[0], b[1]) <= r) {
                return true;
            }
        }
        return false;
    }

    static boolean pointInPolygon(float x, float z, List<float[]> vs) {
        boolean inside = false;
        for (int i = 0, j = vs.size() - 1; i < vs.size(); j = i++) {
            float xi = vs.get(i)[0], zi = vs.get(i)[1];
            float xj = vs.get(j)[0], zj = vs.get(j)[1];
            boolean intersect = ((zi > z) != (zj > z))
                    && (x < (xj - xi) * (z - zi) / (zj - zi + 1e-9f) + xi);
            if (intersect) {
                inside = !inside;
            }
        }
        return inside;
    }

    static float distancePointToSegment(float px, float pz, float ax, float az, float bx, float bz) {
        float dx = bx - ax;
        float dz = bz - az;
        float len2 = dx * dx + dz * dz;
        if (len2 < 1e-9f) {
            float ex = px - ax;
            float ez = pz - az;
            return (float) Math.sqrt(ex * ex + ez * ez);
        }
        float t = ((px - ax) * dx + (pz - az) * dz) / len2;
        t = Math.max(0f, Math.min(1f, t));
        float qx = ax + t * dx;
        float qz = az + t * dz;
        float ex = px - qx;
        float ez = pz - qz;
        return (float) Math.sqrt(ex * ex + ez * ez);
    }

    public Map<Integer, List<BlockerPolygon>> snapshotAll() {
        return Map.copyOf(planeBlockers);
    }

    /** 导出为客户端预碰撞用的扁平 float 列表：每多边形 [n, x0,z0, x1,z1, ...] */
    public List<float[]> exportClientMesh(int planeId) {
        List<BlockerPolygon> list = planeBlockers.getOrDefault(planeId, List.of());
        List<float[]> out = new ArrayList<>(list.size());
        for (BlockerPolygon p : list) {
            List<float[]> vs = p.vertices();
            float[] flat = new float[1 + vs.size() * 2];
            flat[0] = vs.size();
            int i = 1;
            for (float[] v : vs) {
                flat[i++] = v[0];
                flat[i++] = v[1];
            }
            out.add(flat);
        }
        return out;
    }
}
