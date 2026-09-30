package cn.itcast.demo.mylunarcore.admin;

import cn.itcast.demo.mylunarcore.common.ConfigImportService;
import cn.itcast.demo.mylunarcore.common.ConfigPublishAuditService;
import cn.itcast.demo.mylunarcore.model.ActivityConfig;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 运营配置导入 HTTP 入口：支持 dry-run、回滚与审计查询。
 */
@RestController
@RequestMapping("/api/admin/ops/import")
public class ConfigImportController {

    private final ConfigImportService configImportService;
    private final ConfigPublishAuditService auditService;

    public ConfigImportController(ConfigImportService configImportService,
                                  ConfigPublishAuditService auditService) {
        this.configImportService = configImportService;
        this.auditService = auditService;
    }

    @PostMapping("/json-files")
    @PreAuthorize("hasAuthority('admin:ops:write')")
    public Map<String, Object> importJsonFiles(@RequestBody JsonFilesImportRequest request) throws Exception {
        boolean reload = request.reload == null || request.reload;
        boolean dryRun = Boolean.TRUE.equals(request.dryRun);
        return configImportService.importJsonFiles(request.files, reload, dryRun);
    }

    @PostMapping("/game-data")
    @PreAuthorize("hasAuthority('admin:ops:write')")
    public Map<String, Object> importGameData(@RequestBody GameDataImportRequest request) {
        boolean reload = request.reload == null || request.reload;
        boolean dryRun = Boolean.TRUE.equals(request.dryRun);
        return configImportService.importGameData(request.dataKey, request.payloadJson, reload, dryRun);
    }

    @PostMapping("/activity")
    @PreAuthorize("hasAuthority('admin:ops:write')")
    public Map<String, Object> importActivity(@RequestBody ActivityImportRequest request) throws Exception {
        boolean reload = request.reload == null || request.reload;
        boolean dryRun = Boolean.TRUE.equals(request.dryRun);
        return configImportService.importUnifiedActivity(request.activity, reload, dryRun);
    }

    @PostMapping("/rollback")
    @PreAuthorize("hasAuthority('admin:ops:write')")
    public Map<String, Object> rollback(@RequestBody RollbackRequest request) throws Exception {
        boolean reload = request.reload == null || request.reload;
        return configImportService.rollbackFiles(request.files, reload);
    }

    @GetMapping("/audit")
    @PreAuthorize("hasAuthority('admin:ops:write')")
    public Map<String, Object> audit() {
        List<Map<String, Object>> recent = auditService.recent(20).stream()
                .map(auditService::toMap)
                .toList();
        ConfigPublishAuditService.PublishRecord last = auditService.lastSuccessful();
        return Map.of(
                "ok", true,
                "lastSuccessful", last == null ? Map.of() : auditService.toMap(last),
                "recent", recent
        );
    }

    public static class JsonFilesImportRequest {
        public Map<String, String> files;
        public Boolean reload;
        public Boolean dryRun;
    }

    public static class GameDataImportRequest {
        public String dataKey;
        public String payloadJson;
        public Boolean reload;
        public Boolean dryRun;
    }

    public static class ActivityImportRequest {
        public ActivityConfig activity;
        public Boolean reload;
        public Boolean dryRun;
    }

    public static class RollbackRequest {
        public List<String> files;
        public Boolean reload;
    }
}
