package cn.itcast.demo.mylunarcore.scene;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 场景后台预加载：玩家接近传送门/边界时提前缓存目标 Plane 低模资源键与基础数据标记。
 * 真正切图时 LoadingTicket 降级为「校验握手」，缩短玩家感知加载时间。
 * <p>
 * 过渡分级：预加载命中 → DISSOLVE/RIFT（元素溶解/裂缝穿梭）；未命中 → BLACK。
 * 方向预判：沿移动方向提前 2~3 身位触发，压低 BLACK 比例。
 */
@Service
public class ScenePreloadService {

    /** 身位约 1.5m；提前 2.5 身位 ≈ 3.75m 外扩预判半径 */
    public static final float PREDICT_BODY_LENGTHS = 2.5f;
    public static final float BODY_LENGTH_METERS = 1.5f;
    public static final int DISSOLVE_DURATION_MS = 900;
    public static final int RIFT_DURATION_MS = 1_200;
    public static final int FADE_DURATION_MS = 600;
    public static final int BLACK_DURATION_MS = 1_800;

    public record PreloadKey(int planeId, int floorId) {}

    public record MaskInfo(String illustrationId, String characterAnimId, String tipText, String maskStyle,
                           String transitionType, String recommendedLayoutId, int hitRateBp, int durationMs) {
        public MaskInfo(String illustrationId, String characterAnimId, String tipText, String maskStyle) {
            this(illustrationId, characterAnimId, tipText, maskStyle, "BLACK", "", 0, BLACK_DURATION_MS);
        }

        public MaskInfo(String illustrationId, String characterAnimId, String tipText, String maskStyle,
                        String transitionType, String recommendedLayoutId, int hitRateBp) {
            this(illustrationId, characterAnimId, tipText, maskStyle, transitionType, recommendedLayoutId,
                    hitRateBp, durationFor(transitionType));
        }

        public static MaskInfo forPlane(int planeId, int floorId) {
            return forPlane(planeId, floorId, "BLACK", "", 0);
        }

        public static MaskInfo forPlane(int planeId, int floorId, String transitionType,
                                        String recommendedLayoutId, int hitRateBp) {
            String type = transitionType == null || transitionType.isBlank() ? "BLACK" : transitionType;
            return new MaskInfo(
                    "plane_illust_" + planeId,
                    "char_travel_loop",
                    "正在前往区域 " + planeId + "-" + floorId + "…",
                    "hybrid",
                    type,
                    recommendedLayoutId == null ? "" : recommendedLayoutId,
                    Math.max(0, hitRateBp),
                    durationFor(type));
        }

        public MaskInfo withLayout(String layoutId) {
            return new MaskInfo(illustrationId, characterAnimId, tipText, maskStyle,
                    transitionType, layoutId == null ? "" : layoutId, hitRateBp, durationMs);
        }

        static int durationFor(String transitionType) {
            if (transitionType == null) {
                return BLACK_DURATION_MS;
            }
            return switch (transitionType) {
                case "DISSOLVE" -> DISSOLVE_DURATION_MS;
                case "RIFT" -> RIFT_DURATION_MS;
                case "FADE" -> FADE_DURATION_MS;
                default -> BLACK_DURATION_MS;
            };
        }
    }

    public record PreloadState(int planeId, int floorId, int entryId, List<String> assetKeys,
                               long readyAtMillis, MaskInfo mask) {}

    private final Map<Long, PreloadState> readyByUid = new ConcurrentHashMap<>();
    /** 预加载命中统计：hits<<16 | misses */
    private final Map<Long, int[]> hitStats = new ConcurrentHashMap<>();
    /** 上一帧位置，用于方向预判 */
    private final Map<Long, float[]> lastPosByUid = new ConcurrentHashMap<>();
    /** 预加载风暴限流：uid → 最近请求时间戳环形缓冲 */
    private final Map<Long, long[]> preloadStormWindow = new ConcurrentHashMap<>();
    private static final int STORM_MAX_IN_WINDOW = 3;
    private static final long STORM_WINDOW_MS = 10_000L;

    /**
     * 10 秒内预加载请求超过 3 次则判定风暴，应返回 BLACK 并拒绝额外计算。
     */
    public boolean isPreloadStorm(long playerUid) {
        if (playerUid <= 0) {
            return true;
        }
        long now = System.currentTimeMillis();
        long[] window = preloadStormWindow.computeIfAbsent(playerUid, id -> new long[STORM_MAX_IN_WINDOW + 2]);
        synchronized (window) {
            int count = 0;
            for (int i = 0; i < window.length; i++) {
                if (window[i] > 0 && now - window[i] <= STORM_WINDOW_MS) {
                    count++;
                } else {
                    window[i] = 0;
                }
            }
            if (count >= STORM_MAX_IN_WINDOW) {
                return true;
            }
            for (int i = 0; i < window.length; i++) {
                if (window[i] == 0) {
                    window[i] = now;
                    break;
                }
            }
            return false;
        }
    }

    public record PortalHint(int fromPlaneId, int toPlaneId, int toFloorId, int toEntryId,
                             float x, float z, float radius) {}

    private static final List<PortalHint> DEFAULT_PORTALS = List.of(
            new PortalHint(1, 2, 1, 1, 100f, 100f, 18f),
            new PortalHint(2, 1, 1, 1, 8f, 8f, 18f)
    );

    /** 受理预加载：生成低模/基础数据资源键并标记就绪。 */
    public PreloadState beginPreload(long playerUid, int planeId, int floorId, int entryId) {
        if (playerUid <= 0 || planeId <= 0) {
            return null;
        }
        List<String> keys = buildAssetKeys(planeId, floorId);
        String trans = resolveTransitionType(playerUid, true);
        MaskInfo mask = MaskInfo.forPlane(planeId, floorId, trans, "", hitRateBp(playerUid));
        PreloadState state = new PreloadState(planeId, floorId, entryId, keys,
                System.currentTimeMillis(), mask);
        readyByUid.put(playerUid, state);
        return state;
    }

    public boolean isReady(long playerUid, int planeId, int floorId) {
        PreloadState state = readyByUid.get(playerUid);
        if (state == null) {
            return false;
        }
        return state.planeId() == planeId && state.floorId() == floorId;
    }

    public PreloadState peek(long playerUid) {
        return readyByUid.get(playerUid);
    }

    /** 切图握手成功后消费预加载态（允许幂等保留至超时由 clear 处理）。 */
    public PreloadState consumeIfMatch(long playerUid, int planeId, int floorId) {
        PreloadState state = readyByUid.get(playerUid);
        if (state == null || state.planeId() != planeId || state.floorId() != floorId) {
            return null;
        }
        readyByUid.remove(playerUid, state);
        return state;
    }

    public void clear(long playerUid) {
        readyByUid.remove(playerUid);
        lastPosByUid.remove(playerUid);
    }

    public MaskInfo resolveMask(long playerUid, int planeId, int floorId) {
        PreloadState state = readyByUid.get(playerUid);
        if (state != null && state.planeId() == planeId && state.floorId() == floorId) {
            return state.mask();
        }
        return MaskInfo.forPlane(planeId, floorId);
    }

    static List<String> buildAssetKeys(int planeId, int floorId) {
        List<String> keys = new ArrayList<>(4);
        keys.add("plane/" + planeId + "/lod0");
        keys.add("plane/" + planeId + "/floor/" + floorId + "/nav");
        keys.add("plane/" + planeId + "/floor/" + floorId + "/props_base");
        keys.add("plane/" + planeId + "/meta.json");
        return List.copyOf(keys);
    }

    public void recordOutcome(long playerUid, boolean hit) {
        int[] stats = hitStats.computeIfAbsent(playerUid, k -> new int[2]);
        if (hit) {
            stats[0]++;
        } else {
            stats[1]++;
        }
    }

    /** 命中率万分比；无样本时返回 0。 */
    public int hitRateBp(long playerUid) {
        int[] stats = hitStats.get(playerUid);
        if (stats == null) {
            return 0;
        }
        int total = stats[0] + stats[1];
        if (total <= 0) {
            return 0;
        }
        return (int) Math.floor(stats[0] * 10_000.0 / total);
    }

    /** 未命中（BLACK）占比万分比。 */
    public int missRateBp(long playerUid) {
        int[] stats = hitStats.get(playerUid);
        if (stats == null) {
            return 0;
        }
        int total = stats[0] + stats[1];
        if (total <= 0) {
            return 0;
        }
        return (int) Math.floor(stats[1] * 10_000.0 / total);
    }

    public boolean shouldFade(long playerUid) {
        return hitRateBp(playerUid) > 9_000;
    }

    /**
     * 过渡类型：命中时优先 DISSOLVE（命中率高）或 RIFT（穿梭感）；未命中 BLACK。
     * 兼容旧客户端仍可识别 FADE 语义（DISSOLVE 为 FADE 升级）。
     */
    public String resolveTransitionType(long playerUid, boolean preloadHit) {
        if (!preloadHit) {
            return "BLACK";
        }
        if (shouldFade(playerUid) || hitRateBp(playerUid) >= 7_000) {
            return "DISSOLVE";
        }
        return "RIFT";
    }

    /**
     * 接近传送门：基础半径内触发。未接近则 null。
     */
    public PortalHint detectApproach(int fromPlaneId, float x, float z) {
        return detectApproach(fromPlaneId, x, z, 0f, 0f, false);
    }

    /**
     * 方向预判预加载：沿移动方向外扩 2~3 身位半径，提前触发。
     *
     * @param predictive true 时使用扩大半径 + 朝向加权
     */
    public PortalHint detectApproach(int fromPlaneId, float x, float z,
                                     float dirX, float dirZ, boolean predictive) {
        float predictMeters = PREDICT_BODY_LENGTHS * BODY_LENGTH_METERS;
        for (PortalHint portal : DEFAULT_PORTALS) {
            if (portal.fromPlaneId() != fromPlaneId) {
                continue;
            }
            float radius = portal.radius();
            if (predictive) {
                radius += predictMeters;
            }
            double dx = portal.x() - x;
            double dz = portal.z() - z;
            double distSq = dx * dx + dz * dz;
            if (distSq > radius * radius) {
                continue;
            }
            if (predictive && (dirX != 0f || dirZ != 0f)) {
                double len = Math.sqrt(dirX * dirX + dirZ * dirZ);
                if (len > 1e-4) {
                    double ndx = dirX / len;
                    double ndz = dirZ / len;
                    double toLen = Math.sqrt(distSq);
                    if (toLen > 1e-4) {
                        double toward = (dx / toLen) * ndx + (dz / toLen) * ndz;
                        // 朝向传送门夹角余弦 > 0.2 才预判，避免背向误触发
                        if (toward < 0.2) {
                            continue;
                        }
                    }
                }
            }
            return portal;
        }
        return null;
    }

    /**
     * 记录位移并返回方向向量（归一化前原始差分）；首帧返回零向量。
     */
    public float[] trackAndDirection(long playerUid, float x, float z) {
        float[] prev = lastPosByUid.put(playerUid, new float[]{x, z});
        if (prev == null) {
            return new float[]{0f, 0f};
        }
        return new float[]{x - prev[0], z - prev[1]};
    }

    public List<String> lodPlaceholderKeys(int planeId, int floorId) {
        return List.of(
                "plane/" + planeId + "/lod0",
                "plane/" + planeId + "/floor/" + floorId + "/proxy_mesh"
        );
    }

    @Override
    public String toString() {
        return "ScenePreloadService{size=" + readyByUid.size() + "}";
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(readyByUid.size());
    }
}
