package cn.itcast.demo.mylunarcore.admin;

import cn.itcast.demo.mylunarcore.common.ConfigImportService;
import cn.itcast.demo.mylunarcore.common.ConfigPublishAuditService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 运维一键回滚：{@code POST /api/admin/ops/rollback?version=}，需二次确认。
 */
@RestController
@RequestMapping("/api/admin/ops")
public class OpsRollbackController {

    private final ConfigImportService configImportService;
    private final ConfigPublishAuditService auditService;
    private final AdminAuditLogService adminAuditLogService;

    public OpsRollbackController(ConfigImportService configImportService,
                                 ConfigPublishAuditService auditService,
                                 AdminAuditLogService adminAuditLogService) {
        this.configImportService = configImportService;
        this.auditService = auditService;
        this.adminAuditLogService = adminAuditLogService;
    }

    /**
     * @param version     备份后缀或逻辑版本号（写入审计）
     * @param confirm     必须为 {@code ROLLBACK} 才执行
     * @param reason      回滚原因（必填）
     * @param requestBody files 列表；空则拒绝
     */
    @PostMapping("/rollback")
    @PreAuthorize("hasAuthority('admin:ops:write')")
    public Map<String, Object> rollback(@RequestParam(required = false) String version,
                                        @RequestParam String confirm,
                                        @RequestParam String reason,
                                        @RequestBody(required = false) RollbackBody requestBody) throws Exception {
        if (!"ROLLBACK".equals(confirm)) {
            return Map.of("ok", false, "retcode", 1,
                    "message", "二次确认失败：confirm 必须为 ROLLBACK");
        }
        if (reason == null || reason.isBlank() || reason.length() < 4) {
            return Map.of("ok", false, "retcode", 2,
                    "message", "必须填写回滚原因（>=4 字符）");
        }
        List<String> files = requestBody == null ? List.of() : requestBody.files;
        if (files == null || files.isEmpty()) {
            return Map.of("ok", false, "retcode", 3, "message", "files 不能为空");
        }
        boolean reload = requestBody.reload == null || requestBody.reload;
        Map<String, Object> result = configImportService.rollbackFiles(files, reload);
        adminAuditLogService.record("ops", "config_rollback",
                "version=" + (version == null ? "" : version),
                "{\"reason\":\"" + reason.replace("\"", "'") + "\",\"files\":" + files.size() + "}",
                Boolean.TRUE.equals(result.get("ok")) || !result.containsKey("ok"));
        result.put("version", version == null ? "" : version);
        result.put("reason", reason);
        result.put("lastSuccessful", auditService.lastSuccessful() == null
                ? Map.of() : auditService.toMap(auditService.lastSuccessful()));
        return result;
    }

    public static class RollbackBody {
        public List<String> files;
        public Boolean reload;
    }
}
