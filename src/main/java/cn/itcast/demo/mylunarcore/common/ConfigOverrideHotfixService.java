package cn.itcast.demo.mylunarcore.common;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 配置覆盖式热补丁：按 configId + JSON Path 覆写内存值，不碰整文件，避免回滚结构二次故障。
 */
@Service
public class ConfigOverrideHotfixService {

    private static final Logger log = AppLogger.logger(LogCategory.SYSTEM, ConfigOverrideHotfixService.class);

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path persistFile;
    private final ConfigDeltaPatchService deltaPatchService;
    /** configId → path → value */
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Object>> overrides = new ConcurrentHashMap<>();

    public ConfigOverrideHotfixService(LunarCoreProperties properties, ConfigDeltaPatchService deltaPatchService) {
        this.persistFile = Path.of(properties.getDataDir(), "hotfix-overrides.json");
        this.deltaPatchService = deltaPatchService;
    }

    @PostConstruct
    public void loadPersisted() {
        if (!Files.isRegularFile(persistFile)) {
            return;
        }
        try {
            JsonNode root = mapper.readTree(Files.readString(persistFile));
            if (root != null && root.isObject()) {
                root.fields().forEachRemaining(e -> {
                    ConcurrentHashMap<String, Object> paths = new ConcurrentHashMap<>();
                    if (e.getValue().isObject()) {
                        e.getValue().fields().forEachRemaining(p ->
                                paths.put(p.getKey(), mapper.convertValue(p.getValue(), Object.class)));
                    }
                    overrides.put(e.getKey(), paths);
                    deltaPatchService.applyDelta(e.getKey(), paths);
                });
            }
            log.info("Loaded config overrides configs={}", overrides.size());
        } catch (Exception e) {
            log.warn("load hotfix-overrides failed: {}", e.toString());
        }
    }

    public synchronized Map<String, Object> applyOverride(String configId, Map<String, Object> pathValues, String reason) {
        if (configId == null || configId.isBlank() || pathValues == null || pathValues.isEmpty()) {
            return Map.of("ok", false, "message", "invalid_args");
        }
        ConcurrentHashMap<String, Object> bucket = overrides.computeIfAbsent(configId, k -> new ConcurrentHashMap<>());
        bucket.putAll(pathValues);
        String json = deltaPatchService.applyDelta(configId, pathValues);
        persist();
        log.warn("hotfix override applied configId={} paths={} reason={}", configId, pathValues.keySet(), reason);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("configId", configId);
        out.put("applied", pathValues);
        out.put("reason", reason == null ? "" : reason);
        out.put("at", Instant.now().toString());
        out.put("snapshotJson", json);
        return out;
    }

    public synchronized Map<String, Object> clearOverride(String configId) {
        if (configId == null || configId.isBlank()) {
            overrides.clear();
            persist();
            return Map.of("ok", true, "cleared", "all");
        }
        overrides.remove(configId);
        persist();
        return Map.of("ok", true, "cleared", configId);
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> list = new ArrayList<>();
        overrides.forEach((id, paths) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("configId", id);
            row.put("paths", Map.copyOf(paths));
            list.add(row);
        });
        out.put("overrides", list);
        out.put("count", list.size());
        return out;
    }

    public Object resolve(String configId, String path, Object fallback) {
        ConcurrentHashMap<String, Object> bucket = overrides.get(configId);
        if (bucket == null) {
            return fallback;
        }
        return bucket.getOrDefault(path, fallback);
    }

    private void persist() {
        try {
            Files.createDirectories(persistFile.getParent());
            ObjectNode root = mapper.createObjectNode();
            overrides.forEach((id, paths) -> root.set(id, mapper.valueToTree(paths)));
            mapper.writerWithDefaultPrettyPrinter().writeValue(persistFile.toFile(), root);
        } catch (Exception e) {
            log.warn("persist hotfix-overrides failed: {}", e.toString());
        }
    }
}
