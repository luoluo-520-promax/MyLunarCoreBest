package cn.itcast.demo.mylunarcore.story;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 剧情资源包（配音/立绘）版本：与热更配置联动，客户端按 bundle 校验匹配。
 */
@Service
public class StoryAssetVersionService {

    public record BundleInfo(String bundleId, String version, String contentHash) {}

    private final JdbcTemplate jdbc;
    private final ConcurrentHashMap<String, BundleInfo> mem = new ConcurrentHashMap<>();

    public StoryAssetVersionService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        // 默认与对话/过场配置对齐的逻辑版本
        mem.put("dialogue", new BundleInfo("dialogue", "1.0.0", "dialogue-trees"));
        mem.put("cutscene", new BundleInfo("cutscene", "1.0.0", "cutscene-configs"));
        mem.put("voice", new BundleInfo("voice", "1.0.0", "voice-pack"));
        mem.put("portrait", new BundleInfo("portrait", "1.0.0", "portrait-pack"));
    }

    public void publish(String bundleId, String version, String contentHash) {
        if (bundleId == null || version == null || contentHash == null) {
            return;
        }
        BundleInfo info = new BundleInfo(bundleId, version, contentHash);
        mem.put(bundleId, info);
        try {
            int u = jdbc.update("""
                    UPDATE story_asset_bundle SET version=?, content_hash=?, updated_at=? WHERE bundle_id=?
                    """, version, contentHash, Instant.now().toString(), bundleId);
            if (u == 0) {
                jdbc.update("""
                        INSERT INTO story_asset_bundle (bundle_id, version, content_hash, updated_at)
                        VALUES (?, ?, ?, ?)
                        """, bundleId, version, contentHash, Instant.now().toString());
            }
        } catch (Exception ignored) {
        }
    }

    /** 客户端上报本地版本；返回需热更的 bundle 列表。 */
    public List<Map<String, Object>> diffClient(Map<String, String> clientVersions) {
        List<Map<String, Object>> need = new java.util.ArrayList<>();
        for (Map.Entry<String, BundleInfo> e : mem.entrySet()) {
            String local = clientVersions == null ? null : clientVersions.get(e.getKey());
            if (local == null || !local.equals(e.getValue().version())) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("bundleId", e.getValue().bundleId());
                m.put("serverVersion", e.getValue().version());
                m.put("contentHash", e.getValue().contentHash());
                m.put("clientVersion", local == null ? "" : local);
                need.add(m);
            }
        }
        return need;
    }

    public Map<String, Object> catalog() {
        Map<String, Object> out = new LinkedHashMap<>();
        mem.forEach((k, v) -> out.put(k, Map.of("version", v.version(), "hash", v.contentHash())));
        return out;
    }
}
