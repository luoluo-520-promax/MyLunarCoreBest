package cn.itcast.demo.mylunarcore.admin;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 敏感操作多人复核：申请人提交 → 至少 N 名其他管理员批准后执行。
 * 抽卡概率等超敏操作需总经理级审批。
 */
@Service
public class SensitiveOpApprovalService {

    public enum Status { PENDING, APPROVED, REJECTED, CONSUMED }

    public static final int DEFAULT_REQUIRED_APPROVALS = 2;
    public static final String EXEC_ROLE = "executive";

    public record ApprovalTicket(String ticketId, String opType, String payloadJson,
                                 String requester, String approver, Status status, long createdAtMs,
                                 int requiredApprovals, java.util.List<String> approvers,
                                 boolean requiresExecutive) {
        public ApprovalTicket(String ticketId, String opType, String payloadJson,
                              String requester, String approver, Status status, long createdAtMs) {
            this(ticketId, opType, payloadJson, requester, approver, status, createdAtMs,
                    DEFAULT_REQUIRED_APPROVALS, List.of(), false);
        }
    }

    private final JdbcTemplate jdbc;
    private final Map<String, ApprovalTicket> memory = new ConcurrentHashMap<>();

    public SensitiveOpApprovalService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ApprovalTicket request(String opType, String payloadJson, String requester) {
        return request(opType, payloadJson, requester, DEFAULT_REQUIRED_APPROVALS);
    }

    public ApprovalTicket request(String opType, String payloadJson, String requester, int requiredApprovals) {
        String id = "apr-" + UUID.randomUUID().toString().substring(0, 12);
        boolean exec = isExecutiveRequired(opType, payloadJson);
        int need = Math.max(1, requiredApprovals);
        if (exec) {
            need = Math.max(need, DEFAULT_REQUIRED_APPROVALS);
        }
        ApprovalTicket t = new ApprovalTicket(id, opType, payloadJson == null ? "{}" : payloadJson,
                requester == null ? "unknown" : requester, "", Status.PENDING, System.currentTimeMillis(),
                need, List.of(), exec);
        memory.put(id, t);
        persist(t);
        return t;
    }

    public ApprovalTicket approve(String ticketId, String approver) {
        return approve(ticketId, approver, null);
    }

    public ApprovalTicket approve(String ticketId, String approver, String role) {
        ApprovalTicket cur = get(ticketId);
        if (cur == null || cur.status() != Status.PENDING) {
            return cur;
        }
        if (approver != null && approver.equals(cur.requester())) {
            return cur;
        }
        java.util.List<String> nextApprovers = new java.util.ArrayList<>(cur.approvers());
        if (approver != null && !approver.isBlank() && !nextApprovers.contains(approver)) {
            nextApprovers.add(approver);
        }
        boolean hasExec = !cur.requiresExecutive()
                || (role != null && EXEC_ROLE.equalsIgnoreCase(role))
                || nextApprovers.stream().anyMatch(a -> a != null && a.startsWith("exec:"));
        if (cur.requiresExecutive() && role != null && EXEC_ROLE.equalsIgnoreCase(role)
                && approver != null && !approver.startsWith("exec:")) {
            // 标记总经理已批
            nextApprovers.remove(approver);
            nextApprovers.add("exec:" + approver);
            hasExec = true;
        }
        Status status = (nextApprovers.size() >= cur.requiredApprovals() && hasExec)
                ? Status.APPROVED : Status.PENDING;
        ApprovalTicket next = new ApprovalTicket(cur.ticketId(), cur.opType(), cur.payloadJson(),
                cur.requester(), String.join(",", nextApprovers), status, cur.createdAtMs(),
                cur.requiredApprovals(), List.copyOf(nextApprovers), cur.requiresExecutive());
        memory.put(ticketId, next);
        persist(next);
        return next;
    }

    static boolean isExecutiveRequired(String opType, String payloadJson) {
        if (opType != null && (opType.contains("GACHA_RATE") || opType.contains("GACHA_PROB"))) {
            return true;
        }
        if (payloadJson != null) {
            String p = payloadJson.toLowerCase();
            return p.contains("pity") || p.contains("probability") || p.contains("\"rate\"")
                    || p.contains("gacha_rate");
        }
        return false;
    }

    public ApprovalTicket reject(String ticketId, String approver) {
        ApprovalTicket cur = get(ticketId);
        if (cur == null || cur.status() != Status.PENDING) {
            return cur;
        }
        ApprovalTicket next = new ApprovalTicket(cur.ticketId(), cur.opType(), cur.payloadJson(),
                cur.requester(), approver == null ? "" : approver, Status.REJECTED, cur.createdAtMs(),
                cur.requiredApprovals(), cur.approvers(), cur.requiresExecutive());
        memory.put(ticketId, next);
        persist(next);
        return next;
    }

    /** 执行前核销：仅 APPROVED 可消费一次。 */
    public boolean consumeIfApproved(String ticketId, String expectedOpType) {
        ApprovalTicket cur = get(ticketId);
        if (cur == null || cur.status() != Status.APPROVED) {
            return false;
        }
        if (expectedOpType != null && !expectedOpType.equals(cur.opType())) {
            return false;
        }
        ApprovalTicket next = new ApprovalTicket(cur.ticketId(), cur.opType(), cur.payloadJson(),
                cur.requester(), cur.approver(), Status.CONSUMED, cur.createdAtMs(),
                cur.requiredApprovals(), cur.approvers(), cur.requiresExecutive());
        memory.put(ticketId, next);
        persist(next);
        return true;
    }

    public ApprovalTicket get(String ticketId) {
        if (ticketId == null || ticketId.isBlank()) {
            return null;
        }
        ApprovalTicket mem = memory.get(ticketId);
        if (mem != null) {
            return mem;
        }
        return load(ticketId);
    }

    private void persist(ApprovalTicket t) {
        if (jdbc == null) {
            return;
        }
        try {
            jdbc.update("""
                    INSERT INTO admin_sensitive_approval
                      (ticket_id, op_type, payload_json, requester, approver, status, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, NOW())
                    ON DUPLICATE KEY UPDATE approver=VALUES(approver), status=VALUES(status)
                    """, t.ticketId(), t.opType(), t.payloadJson(), t.requester(), t.approver(), t.status().name());
        } catch (Exception ignored) {
            // 表未建时仅内存
        }
    }

    private ApprovalTicket load(String ticketId) {
        if (jdbc == null) {
            return null;
        }
        try {
            return jdbc.query("""
                    SELECT ticket_id, op_type, payload_json, requester, approver, status,
                           UNIX_TIMESTAMP(created_at)*1000
                    FROM admin_sensitive_approval WHERE ticket_id = ?
                    """, rs -> {
                if (!rs.next()) {
                    return null;
                }
                return new ApprovalTicket(rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getString(4), rs.getString(5), Status.valueOf(rs.getString(6)), rs.getLong(7));
            }, ticketId);
        } catch (Exception e) {
            return null;
        }
    }
}
