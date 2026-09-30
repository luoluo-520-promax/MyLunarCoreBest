package cn.itcast.demo.mylunarcore.admin;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 敏感操作审批 API。
 */
@RestController
@RequestMapping("/api/admin/ops/approvals")
public class SensitiveOpApprovalController {

    private final SensitiveOpApprovalService approvalService;
    private final AdminAuditLogService auditLogService;

    public SensitiveOpApprovalController(SensitiveOpApprovalService approvalService,
                                         AdminAuditLogService auditLogService) {
        this.approvalService = approvalService;
        this.auditLogService = auditLogService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('admin:ops:write')")
    public Map<String, Object> request(@RequestBody Map<String, Object> body) {
        String opType = String.valueOf(body.getOrDefault("opType", ""));
        String payload = String.valueOf(body.getOrDefault("payload", "{}"));
        var ticket = approvalService.request(opType, payload, currentUser());
        auditLogService.record(currentUser(), "sensitive_op_request",
                ticket.ticketId(), "{\"opType\":\"" + opType + "\"}", true);
        return Map.of("ok", true, "ticketId", ticket.ticketId(), "status", ticket.status().name());
    }

    @PostMapping("/{ticketId}/approve")
    @PreAuthorize("hasAuthority('admin:ops:write')")
    public Map<String, Object> approve(@PathVariable String ticketId) {
        var ticket = approvalService.approve(ticketId, currentUser());
        if (ticket == null) {
            return Map.of("ok", false, "message", "not found");
        }
        auditLogService.record(currentUser(), "sensitive_op_approve", ticketId, "{}", true);
        return Map.of("ok", ticket.status() == SensitiveOpApprovalService.Status.APPROVED,
                "status", ticket.status().name());
    }

    @PostMapping("/{ticketId}/reject")
    @PreAuthorize("hasAuthority('admin:ops:write')")
    public Map<String, Object> reject(@PathVariable String ticketId) {
        var ticket = approvalService.reject(ticketId, currentUser());
        if (ticket == null) {
            return Map.of("ok", false, "message", "not found");
        }
        return Map.of("ok", true, "status", ticket.status().name());
    }

    private static String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null || auth.getName() == null ? "system" : auth.getName();
    }
}
