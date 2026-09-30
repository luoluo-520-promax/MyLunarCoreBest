package cn.itcast.demo.mylunarcore.admin;

import cn.itcast.demo.mylunarcore.activity.ActivityResourceService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 活动预加载：{@code POST /api/admin/ops/preload}。
 */
@RestController
@RequestMapping("/api/admin/ops")
public class ActivityPreloadController {

    public record PreloadRequest(int versionActivityId, String cdnBundleVersion, String cdnManifestUrl,
                                 String configJson, Instant activateAt) {}

    private final ActivityResourceService activityResourceService;

    public ActivityPreloadController(ActivityResourceService activityResourceService) {
        this.activityResourceService = activityResourceService;
    }

    @PostMapping("/preload")
    @PreAuthorize("hasAuthority('admin:ops:write')")
    public Map<String, Object> preload(@RequestBody PreloadRequest req) {
        String operator = SecurityContextHolder.getContext().getAuthentication() == null
                ? "anonymous"
                : SecurityContextHolder.getContext().getAuthentication().getName();
        ActivityResourceService.PreloadResult r = activityResourceService.preload(
                req.versionActivityId(), req.cdnBundleVersion(), req.cdnManifestUrl(),
                req.configJson(), req.activateAt(), operator);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("ok", r.ok());
        body.put("message", r.message());
        body.put("cacheKey", r.cacheKey());
        return body;
    }

    @PostMapping("/activate")
    @PreAuthorize("hasAuthority('admin:ops:write')")
    public Map<String, Object> activate(@RequestBody Map<String, Object> body) {
        int id = body.get("versionActivityId") instanceof Number n ? n.intValue() : 0;
        boolean ok = activityResourceService.activate(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", ok);
        out.put("versionActivityId", id);
        return out;
    }
}
