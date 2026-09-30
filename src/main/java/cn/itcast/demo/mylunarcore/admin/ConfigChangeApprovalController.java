package cn.itcast.demo.mylunarcore.admin;

import cn.itcast.demo.mylunarcore.common.HotReloadCoordinator;
import cn.itcast.demo.mylunarcore.tools.PresentationMockClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 配置变更审批工作流 + 表现层 Mock 预览 API。
 */
@RestController
@RequestMapping("/api/admin")
public class ConfigChangeApprovalController {

    private final SensitiveOpApprovalService approval;
    private final HotReloadCoordinator hotReload;

    public ConfigChangeApprovalController(SensitiveOpApprovalService approval,
                                          ObjectProvider<HotReloadCoordinator> hotReloadProvider) {
        this.approval = approval;
        this.hotReload = hotReloadProvider == null ? null : hotReloadProvider.getIfAvailable();
    }

    @PostMapping("/config-change/approve")
    @PreAuthorize("hasAuthority('admin:ops:write')")
    public Map<String, Object> approve(@RequestParam String ticketId,
                                       @RequestParam String approver,
                                       @RequestParam(required = false) String role) {
        SensitiveOpApprovalService.ApprovalTicket t = approval.approve(ticketId, approver, role);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", t != null && t.status() == SensitiveOpApprovalService.Status.APPROVED);
        out.put("ticket", t);
        out.put("hint", t != null && t.requiresExecutive()
                ? "抽卡概率等超敏操作需 role=executive 总经理审批，且至少 2 人批准"
                : "需达到 requiredApprovals 人次批准后方可 apply");
        return out;
    }

    @PostMapping("/config-change/request")
    @PreAuthorize("hasAuthority('admin:ops:write')")
    public Map<String, Object> requestChange(@RequestBody Map<String, Object> body,
                                             @RequestParam String requester,
                                             @RequestParam(defaultValue = "2") int requiredApprovals) {
        String configName = String.valueOf(body.getOrDefault("configName", "Banners.json"));
        String content = String.valueOf(body.getOrDefault("contentJson", "{}"));
        String raw = "{\"configName\":\"" + configName + "\",\"diffNote\":\""
                + body.getOrDefault("diffNote", "") + "\",\"contentJson\":" + content + "}";
        String opType = String.valueOf(body.getOrDefault("opType", "CONFIG_CHANGE"));
        SensitiveOpApprovalService.ApprovalTicket t =
                approval.request(opType, raw, requester, requiredApprovals);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("ticketId", t.ticketId());
        out.put("status", t.status().name());
        out.put("requiredApprovals", t.requiredApprovals());
        out.put("requiresExecutive", t.requiresExecutive());
        out.put("hint", "需至少 " + t.requiredApprovals() + " 名其他管理员 approve 后调用 /config-change/apply");
        return out;
    }

    @PostMapping("/config-change/apply")
    @PreAuthorize("hasAuthority('admin:ops:write')")
    public Map<String, Object> apply(@RequestParam String ticketId, @RequestParam String executor) {
        Map<String, Object> out = new LinkedHashMap<>();
        SensitiveOpApprovalService.ApprovalTicket cur = approval.get(ticketId);
        String opType = cur == null ? "CONFIG_CHANGE" : cur.opType();
        boolean consumed = approval.consumeIfApproved(ticketId, opType);
        if (!consumed) {
            out.put("ok", false);
            out.put("message", "ticket_not_approved_or_wrong_type");
            return out;
        }
        if (hotReload != null) {
            hotReload.reloadAll();
        }
        out.put("ok", true);
        out.put("ticketId", ticketId);
        out.put("executor", executor);
        out.put("opType", opType);
        out.put("appliedAt", System.currentTimeMillis());
        return out;
    }

    /** 策划/美术预览服务端特效参数（无需完整联调）。 */
    @GetMapping("/mock/presentation-preview")
    @PreAuthorize("hasAuthority('admin:ops:read') or hasAuthority('admin:ops:write')")
    public Map<String, Object> presentationPreview() {
        return PresentationMockClient.previewCatalog();
    }
}
