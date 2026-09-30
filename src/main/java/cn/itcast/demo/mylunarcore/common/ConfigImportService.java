package cn.itcast.demo.mylunarcore.common;

import cn.itcast.demo.mylunarcore.model.ActivityConfig;
import cn.itcast.demo.mylunarcore.repo.GameDataRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运营配置导入：支持 dry-run、原子落盘、审计与热重载编排。
 */
@Service
public class ConfigImportService {

    private static final Logger log = AppLogger.logger(LogCategory.SYSTEM, ConfigImportService.class);

    private static final Map<String, String> ALLOWED_FILES = Map.ofEntries(
            Map.entry("ActivityScheduling.json", "ActivityScheduling.json"),
            Map.entry("Banners.json", "Banners.json"),
            Map.entry("items_config.csv", "items_config.csv"),
            Map.entry("hotfix.json", "hotfix.json"),
            Map.entry("ActivityConfigs.json", "ActivityConfigs.json"),
            Map.entry("version_manifest.json", "version_manifest.json"),
            Map.entry("ShopConfigs.json", "ShopConfigs.json"),
            Map.entry("SkinConfigs.json", "SkinConfigs.json")
    );

    private final ConfigFileService configFileService;
    private final ActivityImportService activityImportService;
    private final GameDataRepository gameDataRepository;
    private final HotReloadCoordinator hotReloadCoordinator;
    private final ConfigPublishAuditService auditService;
    private final ConfigReleaseService configReleaseService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ConfigImportService(ConfigFileService configFileService,
                               ActivityImportService activityImportService,
                               GameDataRepository gameDataRepository,
                               @Lazy HotReloadCoordinator hotReloadCoordinator,
                               ConfigPublishAuditService auditService,
                               ConfigReleaseService configReleaseService) {
        this.configFileService = configFileService;
        this.activityImportService = activityImportService;
        this.gameDataRepository = gameDataRepository;
        this.hotReloadCoordinator = hotReloadCoordinator;
        this.auditService = auditService;
        this.configReleaseService = configReleaseService;
    }

    public Map<String, Object> importJsonFiles(Map<String, String> files, boolean reload) throws Exception {
        return importJsonFiles(files, reload, false);
    }

    public Map<String, Object> importJsonFiles(Map<String, String> files, boolean reload, boolean dryRun) throws Exception {
        if (files == null || files.isEmpty()) {
            throw new IllegalArgumentException("files is required");
        }
        List<String> targets = new ArrayList<>();
        for (Map.Entry<String, String> entry : files.entrySet()) {
            String target = ALLOWED_FILES.get(entry.getKey());
            if (target == null) {
                throw new IllegalArgumentException("Unsupported import file: " + entry.getKey());
            }
            if (target.endsWith(".json")) {
                configFileService.validateJsonText(entry.getValue());
            }
            targets.add(target);
        }
        if (dryRun) {
            auditService.record(currentOperator(), "importJsonFiles", targets, true, true, "validated");
            Map<String, Object> body = result(targets, false);
            body.put("dryRun", true);
            body.put("validated", targets);
            body.put("diff", buildDryRunDiff(files));
            return body;
        }
        for (Map.Entry<String, String> entry : files.entrySet()) {
            String target = ALLOWED_FILES.get(entry.getKey());
            configFileService.writeTextAtomic(target, entry.getValue(), true);
            configReleaseService.record(target, sha256(entry.getValue()), "", currentOperator(), "importJsonFiles");
        }
        boolean reloaded = false;
        if (reload) {
            HotReloadCoordinator.ReloadResult rr = hotReloadCoordinator.reloadAllStaged(currentOperator());
            reloaded = rr.success();
            if (!rr.success()) {
                auditService.record(currentOperator(), "importJsonFiles", targets, false, false, rr.message());
                throw new IllegalStateException("Import written but reload failed: " + rr.message());
            }
        }
        ConfigPublishAuditService.PublishRecord record =
                auditService.record(currentOperator(), "importJsonFiles", targets, false, true, "ok");
        Map<String, Object> body = result(targets, reloaded);
        body.put("publishVersion", record.version());
        return body;
    }

    public Map<String, Object> importGameData(String dataKey, String payloadJson, boolean reload) {
        return importGameData(dataKey, payloadJson, reload, false);
    }

    public Map<String, Object> importGameData(String dataKey, String payloadJson, boolean reload, boolean dryRun) {
        if (dataKey == null || dataKey.isBlank()) {
            throw new IllegalArgumentException("dataKey is required");
        }
        if (payloadJson == null || payloadJson.isBlank()) {
            throw new IllegalArgumentException("payloadJson is required");
        }
        try {
            objectMapper.readTree(payloadJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("payloadJson is not valid JSON", e);
        }
        if (dryRun) {
            auditService.record(currentOperator(), "importGameData", List.of(dataKey), true, true, "validated");
            return Map.of("ok", true, "dataKey", dataKey, "dryRun", true, "reloaded", false);
        }
        gameDataRepository.upsert(dataKey, payloadJson);
        boolean reloaded = false;
        if (reload) {
            HotReloadCoordinator.ReloadResult rr = hotReloadCoordinator.reloadAllStaged(currentOperator());
            reloaded = rr.success();
        }
        auditService.record(currentOperator(), "importGameData", List.of(dataKey), false, true, "ok");
        return Map.of("ok", true, "dataKey", dataKey, "reloaded", reloaded);
    }

    public Map<String, Object> importUnifiedActivity(ActivityConfig config, boolean reload) throws Exception {
        return importUnifiedActivity(config, reload, false);
    }

    public Map<String, Object> importUnifiedActivity(ActivityConfig config, boolean reload, boolean dryRun) throws Exception {
        if (config == null || config.getActivityId() <= 0) {
            throw new IllegalArgumentException("activityId is required");
        }
        if (dryRun) {
            List<String> planned = List.of(
                    "ActivityScheduling.json",
                    "Banners.json",
                    "items_config.csv",
                    "ActivityConfigs.json",
                    "activities/activity." + config.getActivityId() + ".json"
            );
            auditService.record(currentOperator(), "importActivity", planned, true, true, "validated");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("ok", true);
            body.put("dryRun", true);
            body.put("activityId", config.getActivityId());
            body.put("plannedFiles", planned);
            body.put("reloaded", false);
            return body;
        }
        ActivityImportService.ImportResult result = activityImportService.importUnified(config);
        boolean reloaded = false;
        if (reload) {
            HotReloadCoordinator.ReloadResult rr = hotReloadCoordinator.reloadAllStaged(currentOperator());
            reloaded = rr.success();
        }
        List<String> files = List.of(result.detailFile());
        ConfigPublishAuditService.PublishRecord record =
                auditService.record(currentOperator(), "importActivity", files, false, true, "ok");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("activityId", result.activityId());
        body.put("detailFile", result.detailFile());
        body.put("gameDataKey", result.gameDataKey());
        body.put("reloaded", reloaded);
        body.put("publishVersion", record.version());
        return body;
    }

    /**
     * 将白名单配置文件从 {@code .bak} 回滚，并可选热更。
     */
    public Map<String, Object> rollbackFiles(List<String> relativePaths, boolean reload) throws Exception {
        if (relativePaths == null || relativePaths.isEmpty()) {
            throw new IllegalArgumentException("files is required");
        }
        List<String> rolled = new ArrayList<>();
        for (String path : relativePaths) {
            String target = ALLOWED_FILES.getOrDefault(path, path);
            if (!ALLOWED_FILES.containsValue(target) && !target.startsWith("activities/")) {
                throw new IllegalArgumentException("Unsupported rollback file: " + path);
            }
            if (configFileService.rollbackFromBackup(target)) {
                rolled.add(target);
            }
        }
        boolean reloaded = false;
        if (reload && !rolled.isEmpty()) {
            HotReloadCoordinator.ReloadResult rr = hotReloadCoordinator.reloadAllStaged(currentOperator());
            reloaded = rr.success();
        }
        ConfigPublishAuditService.PublishRecord record =
                auditService.record(currentOperator(), "rollbackFiles", rolled, false, !rolled.isEmpty(),
                        rolled.isEmpty() ? "no backup found" : "ok");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", !rolled.isEmpty());
        body.put("rolledBack", rolled);
        body.put("reloaded", reloaded);
        body.put("publishVersion", record.version());
        return body;
    }

    private Map<String, Object> result(List<String> written, boolean reload) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", true);
        body.put("written", written);
        body.put("reloaded", reload);
        return body;
    }

    private static String currentOperator() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || auth.getName() == null) {
            return "anonymous";
        }
        return auth.getName();
    }

    /** dry-run：对比新旧内容 SHA-256，输出 changed/unchanged/new。 */
    private List<Map<String, Object>> buildDryRunDiff(Map<String, String> files) {
        List<Map<String, Object>> diffs = new ArrayList<>();
        for (Map.Entry<String, String> entry : files.entrySet()) {
            String target = ALLOWED_FILES.get(entry.getKey());
            String incomingHash = sha256(entry.getValue());
            String status = "new";
            String currentHash = "";
            try {
                String current = configFileService.readText(target);
                currentHash = sha256(current);
                status = currentHash.equals(incomingHash) ? "unchanged" : "changed";
            } catch (Exception ignored) {
                // 文件不存在视为 new
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("file", target);
            row.put("status", status);
            row.put("currentHash", currentHash);
            row.put("incomingHash", incomingHash);
            diffs.add(row);
        }
        return diffs;
    }

    private static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(dig);
        } catch (Exception e) {
            return "";
        }
    }
}
