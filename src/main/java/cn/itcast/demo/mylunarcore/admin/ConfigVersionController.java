package cn.itcast.demo.mylunarcore.admin;

import cn.itcast.demo.mylunarcore.common.ConfigReleaseService;
import cn.itcast.demo.mylunarcore.common.UnifiedConfigAccessor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 配置版本列表 / 对比：供运营后台可视化，而非仅 POST 回滚。
 */
@RestController
@RequestMapping("/api/admin/config-versions")
public class ConfigVersionController {

    private final ConfigReleaseService configReleaseService;
    private final UnifiedConfigAccessor unifiedConfigAccessor;

    public ConfigVersionController(ConfigReleaseService configReleaseService,
                                   UnifiedConfigAccessor unifiedConfigAccessor) {
        this.configReleaseService = configReleaseService;
        this.unifiedConfigAccessor = unifiedConfigAccessor;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('admin:ops:read') or hasAuthority('admin:ops:write')")
    public Map<String, Object> list(@RequestParam(defaultValue = "50") int limit,
                                    @RequestParam(required = false) String configName) {
        List<Map<String, Object>> rows = configReleaseService.list(configName, limit);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("items", rows);
        out.put("count", rows.size());
        out.put("inGrayDefault", unifiedConfigAccessor.inGray(0L, ""));
        return out;
    }

    @GetMapping("/diff")
    @PreAuthorize("hasAuthority('admin:ops:read') or hasAuthority('admin:ops:write')")
    public Map<String, Object> diff(@RequestParam String configName,
                                    @RequestParam long leftId,
                                    @RequestParam long rightId) {
        return configReleaseService.diff(configName, leftId, rightId);
    }

    @GetMapping("/rollback-impact")
    @PreAuthorize("hasAuthority('admin:ops:read') or hasAuthority('admin:ops:write')")
    public Map<String, Object> rollbackImpact(@RequestParam String configName,
                                              @RequestParam long targetReleaseId) {
        return unifiedConfigAccessor.analyzeRollbackImpact(configName, targetReleaseId);
    }
}
