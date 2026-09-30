package cn.itcast.demo.mylunarcore.admin;

import cn.itcast.demo.mylunarcore.common.ConfigPublishAuditService;
import cn.itcast.demo.mylunarcore.common.HotReloadCoordinator;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 鉴权 HTTP 热更新入口，替代 stdin {@code /reload}。
 */
@RestController
@RequestMapping("/api/admin/ops")
public class HotReloadController {

    private final HotReloadCoordinator hotReloadCoordinator;
    private final ConfigPublishAuditService auditService;

    public HotReloadController(HotReloadCoordinator hotReloadCoordinator,
                               ConfigPublishAuditService auditService) {
        this.hotReloadCoordinator = hotReloadCoordinator;
        this.auditService = auditService;
    }

    @PostMapping("/reload")
    @PreAuthorize("hasAuthority('admin:ops:write')")
    public Map<String, Object> reload() {
        String operator = SecurityContextHolder.getContext().getAuthentication() == null
                ? "anonymous"
                : SecurityContextHolder.getContext().getAuthentication().getName();
        HotReloadCoordinator.ReloadResult result = hotReloadCoordinator.reloadAllStaged(operator);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", result.success());
        body.put("message", result.message());
        ConfigPublishAuditService.PublishRecord last = auditService.lastSuccessful();
        if (last != null) {
            body.put("publishVersion", last.version());
        }
        return body;
    }
}
