package cn.itcast.demo.mylunarcore.admin;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

/**
 * 客服时间线：{@code GET /api/admin/ops/timeline/{uid}}。
 */
@RestController
@RequestMapping("/api/admin/ops/timeline")
public class PlayerTimelineController {

    private final PlayerTimelineService timelineService;

    public PlayerTimelineController(PlayerTimelineService timelineService) {
        this.timelineService = timelineService;
    }

    @GetMapping("/{uid}")
    @PreAuthorize("hasAnyAuthority('admin:ops:read','admin:support:read','admin:audit:read')")
    public Map<String, Object> timeline(
            @PathVariable int uid,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "500") int limit) {
        return timelineService.asTree(uid, from, to, limit);
    }
}
