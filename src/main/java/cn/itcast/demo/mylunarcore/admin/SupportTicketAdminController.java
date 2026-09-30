package cn.itcast.demo.mylunarcore.admin;

import cn.itcast.demo.mylunarcore.settings.SupportTicketApplicationService;
import cn.itcast.demo.mylunarcore.settings.SupportTicketEntity;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客服工单后台接口（含操作审计）。
 */
@RestController
@RequestMapping("/api/admin/support")
public class SupportTicketAdminController {

    private final SupportTicketApplicationService ticketService;
    private final AdminAuditLogService auditLogService;

    public SupportTicketAdminController(SupportTicketApplicationService ticketService,
                                        AdminAuditLogService auditLogService) {
        this.ticketService = ticketService;
        this.auditLogService = auditLogService;
    }

    @GetMapping("/tickets")
    @PreAuthorize("hasAuthority('admin:complaint:handle')")
    public List<Map<String, Object>> listTickets(
            @RequestParam(required = false) Integer status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return ticketService.listForAdmin(status, page, pageSize).stream()
                .map(this::toView)
                .toList();
    }

    @GetMapping("/tickets/{ticketId}")
    @PreAuthorize("hasAuthority('admin:complaint:handle')")
    public ResponseEntity<Map<String, Object>> getTicket(@PathVariable long ticketId) {
        return ticketService.find(ticketId)
                .map(t -> ResponseEntity.ok(toView(t)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/tickets/{ticketId}/reply")
    @PreAuthorize("hasAuthority('admin:complaint:handle')")
    public ResponseEntity<Map<String, Object>> reply(
            @PathVariable long ticketId,
            @RequestBody ReplyRequest body) {
        if (body == null || body.reply() == null || body.reply().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "message", "reply required"));
        }
        boolean ok = ticketService.reply(ticketId, body.reply());
        audit("ticket_reply", "ticket:" + ticketId,
                "{replyLen:" + body.reply().length() + "}", ok);
        if (!ok) {
            return ResponseEntity.notFound().build();
        }
        return ticketService.find(ticketId)
                .map(t -> ResponseEntity.ok(toView(t)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/tickets/{ticketId}/close")
    @PreAuthorize("hasAuthority('admin:complaint:handle')")
    public ResponseEntity<Map<String, Object>> close(@PathVariable long ticketId) {
        boolean ok = ticketService.close(ticketId);
        audit("ticket_close", "ticket:" + ticketId, "{}", ok);
        if (!ok) {
            return ResponseEntity.notFound().build();
        }
        return ticketService.find(ticketId)
                .map(t -> ResponseEntity.ok(toView(t)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/audit")
    @PreAuthorize("hasAuthority('admin:complaint:handle')")
    public List<Map<String, Object>> auditRecent(@RequestParam(defaultValue = "50") int limit) {
        return auditLogService.recent(limit);
    }

    private void audit(String action, String resource, String diff, boolean success) {
        String operator = SecurityContextHolder.getContext().getAuthentication() == null
                ? "anonymous"
                : SecurityContextHolder.getContext().getAuthentication().getName();
        auditLogService.record(operator, action, resource, diff, success);
    }

    private Map<String, Object> toView(SupportTicketEntity t) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", t.getId());
        map.put("playerId", t.getPlayerId());
        map.put("category", t.getCategory());
        map.put("subject", t.getSubject());
        map.put("content", t.getContent());
        map.put("status", t.getStatus());
        map.put("adminReply", t.getAdminReply());
        map.put("createdAt", t.getCreatedAt() == null ? null : t.getCreatedAt().toString());
        map.put("updatedAt", t.getUpdatedAt() == null ? null : t.getUpdatedAt().toString());
        return map;
    }

    public record ReplyRequest(String reply) {
    }
}
