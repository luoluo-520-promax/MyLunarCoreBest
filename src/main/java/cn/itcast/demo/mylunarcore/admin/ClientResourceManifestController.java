package cn.itcast.demo.mylunarcore.admin;

import cn.itcast.demo.mylunarcore.common.ClientResourceManifestService;
import cn.itcast.demo.mylunarcore.common.ConfigDeltaPatchService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 客户端资源 Manifest + 配置 Delta 运维接口。
 */
@RestController
@RequestMapping("/api/admin/resources")
public class ClientResourceManifestController {

    private final ClientResourceManifestService manifestService;
    private final ConfigDeltaPatchService deltaPatchService;

    public ClientResourceManifestController(ClientResourceManifestService manifestService,
                                            ConfigDeltaPatchService deltaPatchService) {
        this.manifestService = manifestService;
        this.deltaPatchService = deltaPatchService;
    }

    @GetMapping("/manifest")
    public Map<String, Object> manifest() {
        return manifestService.snapshot();
    }

    @PostMapping("/manifest/upsert")
    public Map<String, Object> upsert(@RequestBody Map<String, Object> body) {
        String path = String.valueOf(body.getOrDefault("path", ""));
        String hash = String.valueOf(body.getOrDefault("hash", ""));
        String cdnUrl = String.valueOf(body.getOrDefault("cdnUrl", ""));
        String locale = String.valueOf(body.getOrDefault("locale", ""));
        long size = body.get("size") instanceof Number n ? n.longValue() : 0L;
        manifestService.upsert(new ClientResourceManifestService.ManifestEntry(path, hash, size, cdnUrl, locale));
        return Map.of("ok", true, "snapshot", manifestService.snapshot());
    }

    @PostMapping("/manifest/diff")
    public ClientResourceManifestService.DiffResult diff(@RequestBody Map<String, Object> body) {
        String clientVersion = String.valueOf(body.getOrDefault("clientVersion", ""));
        @SuppressWarnings("unchecked")
        Map<String, String> hashes = body.get("hashes") instanceof Map<?, ?> m
                ? (Map<String, String>) m : Map.of();
        return manifestService.diffForClient(clientVersion, hashes);
    }

    @PostMapping("/manifest/reload-hotfix")
    public Map<String, Object> reloadHotfix() {
        manifestService.syncFromHotfix();
        return Map.of("ok", true, "snapshot", manifestService.snapshot());
    }

    @PostMapping("/config/delta")
    public Map<String, Object> applyConfigDelta(@RequestBody Map<String, Object> body) {
        String key = String.valueOf(body.getOrDefault("configKey", "default"));
        @SuppressWarnings("unchecked")
        Map<String, Object> paths = body.get("paths") instanceof Map<?, ?> m
                ? (Map<String, Object>) m : Map.of();
        String json = deltaPatchService.applyDelta(key, paths);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("configKey", key);
        out.put("json", json);
        out.put("binaryBytes", deltaPatchService.toBinarySnapshot(key).length);
        return out;
    }

    @PostMapping("/config/diff")
    public Map<String, Object> configDiff(@RequestBody Map<String, Object> body) {
        String oldJson = String.valueOf(body.getOrDefault("oldJson", "{}"));
        String newJson = String.valueOf(body.getOrDefault("newJson", "{}"));
        return Map.of("ok", true, "delta", deltaPatchService.diff(oldJson, newJson));
    }

    @PostMapping("/manifest/persist")
    public ResponseEntity<Map<String, Object>> persist() {
        try {
            var path = manifestService.persistToDataDir();
            return ResponseEntity.ok(Map.of("ok", true, "path", path.toString()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("ok", false, "error", e.getMessage()));
        }
    }
}
