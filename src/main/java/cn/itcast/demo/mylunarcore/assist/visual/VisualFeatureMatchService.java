package cn.itcast.demo.mylunarcore.assist.visual;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端轻量特征（ONNX/MobileNet 输出）匹配预置 POI：优先本地特征库，复杂场景再走远程 VLM。
 */
@Service
public class VisualFeatureMatchService {

    public static final int FEATURE_DIM = 64;
    public static final double MATCH_THRESHOLD = 0.72;
    public static final int DAILY_VLM_LIMIT = 20;

    public record PoiFeature(String poiId, String displayName, float[] feature) {}

    public record MatchResult(boolean matched, String poiId, String displayName, double score,
                              boolean suggestRemoteVlm) {}

    private final ConcurrentHashMap<String, PoiFeature> catalog = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, Integer> dailyVlmUses = new ConcurrentHashMap<>();
    private volatile String dayBucket = "";

    public VisualFeatureMatchService() {
        // 预置少量 POI 占位特征（生产由配置/热更加载）
        register("main_city_fountain", "主城喷泉", hashFeature("main_city_fountain"));
        register("gacha_desk", "跃迁柜台", hashFeature("gacha_desk"));
        register("world_boss_altar", "世界Boss祭坛", hashFeature("world_boss_altar"));
    }

    public void register(String poiId, String displayName, float[] feature) {
        if (poiId == null || feature == null || feature.length == 0) {
            return;
        }
        float[] copy = new float[FEATURE_DIM];
        System.arraycopy(feature, 0, copy, 0, Math.min(feature.length, FEATURE_DIM));
        normalize(copy);
        catalog.put(poiId, new PoiFeature(poiId, displayName == null ? poiId : displayName, copy));
    }

    public MatchResult match(float[] clientFeature) {
        if (clientFeature == null || clientFeature.length == 0) {
            return new MatchResult(false, "", "", 0, true);
        }
        float[] q = new float[FEATURE_DIM];
        System.arraycopy(clientFeature, 0, q, 0, Math.min(clientFeature.length, FEATURE_DIM));
        normalize(q);
        PoiFeature best = null;
        double bestScore = -1;
        for (PoiFeature p : catalog.values()) {
            double s = cosine(q, p.feature());
            if (s > bestScore) {
                bestScore = s;
                best = p;
            }
        }
        if (best == null) {
            return new MatchResult(false, "", "", 0, true);
        }
        boolean matched = bestScore >= MATCH_THRESHOLD;
        return new MatchResult(matched, best.poiId(), best.displayName(), bestScore, !matched);
    }

    public boolean allowRemoteVlm(long uid) {
        String day = java.time.LocalDate.now().toString();
        if (!day.equals(dayBucket)) {
            dayBucket = day;
            dailyVlmUses.clear();
        }
        int used = dailyVlmUses.getOrDefault(uid, 0);
        if (used >= DAILY_VLM_LIMIT) {
            return false;
        }
        dailyVlmUses.put(uid, used + 1);
        return true;
    }

    public int catalogSize() {
        return catalog.size();
    }

    public List<Map<String, Object>> listPois() {
        List<Map<String, Object>> out = new ArrayList<>();
        catalog.values().stream()
                .sorted(Comparator.comparing(PoiFeature::poiId))
                .forEach(p -> out.add(Map.of("poiId", p.poiId(), "displayName", p.displayName())));
        return out;
    }

    private static float[] hashFeature(String seed) {
        float[] v = new float[FEATURE_DIM];
        int h = seed.hashCode();
        for (int i = 0; i < FEATURE_DIM; i++) {
            h = 31 * h + i;
            v[i] = ((h & 0xff) / 255f) - 0.5f;
        }
        normalize(v);
        return v;
    }

    private static void normalize(float[] v) {
        double sum = 0;
        for (float x : v) {
            sum += x * x;
        }
        if (sum <= 1e-9) {
            return;
        }
        float inv = (float) (1.0 / Math.sqrt(sum));
        for (int i = 0; i < v.length; i++) {
            v[i] *= inv;
        }
    }

    private static double cosine(float[] a, float[] b) {
        double s = 0;
        for (int i = 0; i < FEATURE_DIM; i++) {
            s += a[i] * b[i];
        }
        return s;
    }
}
