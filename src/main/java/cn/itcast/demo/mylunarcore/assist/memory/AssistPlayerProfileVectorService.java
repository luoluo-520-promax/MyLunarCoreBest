package cn.itcast.demo.mylunarcore.assist.memory;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家画像向量库（轻量内存近似 FAISS）：每周行为序列向量，检索相似模式生成个性化建议。
 */
@Service
public class AssistPlayerProfileVectorService {

    public static final int DIM = 16;

    public record ProfileVector(long uid, float[] vector, long updatedAtMs, String topAvatarHint) {}

    private final ConcurrentHashMap<Long, ProfileVector> profiles = new ConcurrentHashMap<>();

    /** 用行为计数更新向量（avatarId 使用频次等）。 */
    public ProfileVector upsert(long uid, Map<Integer, Integer> avatarUsage, String preferredLineup) {
        float[] v = new float[DIM];
        if (avatarUsage != null) {
            for (Map.Entry<Integer, Integer> e : avatarUsage.entrySet()) {
                int idx = Math.floorMod(e.getKey(), DIM);
                v[idx] += e.getValue() == null ? 0f : e.getValue();
            }
        }
        if (preferredLineup != null) {
            v[Math.floorMod(preferredLineup.hashCode(), DIM)] += 3f;
        }
        normalize(v);
        String top = preferredLineup == null ? "" : preferredLineup;
        if ((top == null || top.isBlank()) && avatarUsage != null && !avatarUsage.isEmpty()) {
            top = avatarUsage.entrySet().stream()
                    .max(Comparator.comparingInt(e -> e.getValue() == null ? 0 : e.getValue()))
                    .map(e -> String.valueOf(e.getKey()))
                    .orElse("");
        }
        ProfileVector pv = new ProfileVector(uid, v, System.currentTimeMillis(), top);
        profiles.put(uid, pv);
        return pv;
    }

    public ProfileVector get(long uid) {
        return profiles.get(uid);
    }

    /** 检索最相似玩家画像（排除自身），用于协同建议。 */
    public List<ProfileVector> nearest(long uid, int k) {
        ProfileVector self = profiles.get(uid);
        if (self == null) {
            return List.of();
        }
        List<ProfileVector> all = new ArrayList<>(profiles.values());
        all.removeIf(p -> p.uid() == uid);
        all.sort(Comparator.comparingDouble((ProfileVector p) -> -cosine(self.vector(), p.vector())));
        if (all.size() > k) {
            return List.copyOf(all.subList(0, k));
        }
        return List.copyOf(all);
    }

    public String personalizedHint(long uid) {
        ProfileVector self = profiles.get(uid);
        if (self == null || self.topAvatarHint() == null || self.topAvatarHint().isBlank()) {
            return "";
        }
        List<ProfileVector> near = nearest(uid, 1);
        if (near.isEmpty()) {
            return "你最近常用角色 " + self.topAvatarHint() + "，可尝试搭配停云提升输出窗口。";
        }
        return "你最近常用 " + self.topAvatarHint() + "，相似玩家偏好 "
                + near.get(0).topAvatarHint() + "，建议参考其阵容搭配。";
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
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            s += a[i] * b[i];
        }
        return s;
    }
}
