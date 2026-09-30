package cn.itcast.demo.mylunarcore.admin;

import cn.itcast.demo.mylunarcore.profile.PlayerProfileService;
import cn.itcast.demo.mylunarcore.social.CustomEmoteService;
import cn.itcast.demo.mylunarcore.social.PlayerReportBlockService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 举报处理 / 自定义表情审核 / 状态模板配置。
 */
@RestController
@RequestMapping("/api/admin/social")
public class ReportAdminController {

    private final PlayerReportBlockService reportBlockService;
    private final CustomEmoteService customEmoteService;
    private final PlayerProfileService profileService;

    public ReportAdminController(PlayerReportBlockService reportBlockService,
                                 CustomEmoteService customEmoteService,
                                 PlayerProfileService profileService) {
        this.reportBlockService = reportBlockService;
        this.customEmoteService = customEmoteService;
        this.profileService = profileService;
    }

    @GetMapping("/reports")
    @PreAuthorize("hasAuthority('admin:ops:read')")
    public Map<String, Object> listReports(@RequestParam(defaultValue = "50") int limit) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", reportBlockService.listOpenReports(limit));
        return out;
    }

    @PostMapping("/reports/resolve")
    @PreAuthorize("hasAuthority('admin:ops:write')")
    public Map<String, Object> resolveReport(@RequestParam long reportId,
                                             @RequestParam String result,
                                             @RequestParam(defaultValue = "") String note,
                                             @RequestParam(defaultValue = "admin") String operator) {
        boolean ok = reportBlockService.resolveReport(reportId, result, note, operator);
        return Map.of("ok", ok, "reportId", reportId, "result", result);
    }

    @GetMapping("/custom-emotes/pending")
    @PreAuthorize("hasAuthority('admin:ops:read')")
    public Map<String, Object> pendingEmotes(@RequestParam(defaultValue = "50") int limit) {
        return Map.of("items", customEmoteService.listPending(limit));
    }

    @PostMapping("/custom-emotes/review")
    @PreAuthorize("hasAuthority('admin:ops:write')")
    public Map<String, Object> reviewEmote(@RequestParam int customEmoteId,
                                           @RequestParam String status) {
        boolean ok = customEmoteService.setReviewStatus(customEmoteId, status);
        return Map.of("ok", ok, "customEmoteId", customEmoteId, "status", status);
    }

    @GetMapping("/status-presets")
    @PreAuthorize("hasAuthority('admin:ops:read')")
    public Map<String, Object> statusPresets() {
        return Map.of("presets", profileService.presetsForAdmin());
    }

    @PostMapping("/status-presets")
    @PreAuthorize("hasAuthority('admin:ops:write')")
    public Map<String, Object> replacePresets(@RequestBody List<PlayerProfileService.StatusTemplate> presets) {
        profileService.replacePresets(presets);
        return Map.of("ok", true, "presets", profileService.presetsForAdmin());
    }
}
