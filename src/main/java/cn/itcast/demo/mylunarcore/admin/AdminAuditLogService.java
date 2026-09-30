package cn.itcast.demo.mylunarcore.admin;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 管理后台操作审计：含 IP、User-Agent、操作前后值对比。
 */
@Service
public class AdminAuditLogService {

    private final JdbcTemplate jdbc;

    public AdminAuditLogService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String operator, String action, String resource, String diffJson, boolean success) {
        record(operator, action, resource, diffJson, null, null, success);
    }

    public void record(String operator, String action, String resource,
                       String diffJson, String beforeJson, String afterJson, boolean success) {
        RequestMeta meta = currentRequestMeta();
        try {
            jdbc.update("""
                    INSERT INTO admin_audit_log
                    (operator, action, resource, diff_json, success, client_ip, user_agent, before_json, after_json, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    operator == null ? "anonymous" : operator,
                    action == null ? "unknown" : action,
                    resource == null ? "" : resource,
                    diffJson == null ? "{}" : diffJson,
                    success ? 1 : 0,
                    meta.clientIp(),
                    meta.userAgent(),
                    beforeJson,
                    afterJson,
                    Instant.now().toString());
        } catch (Exception ignored) {
            // 兼容旧表结构
            try {
                jdbc.update("""
                        INSERT INTO admin_audit_log (operator, action, resource, diff_json, success, created_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                        operator == null ? "anonymous" : operator,
                        action == null ? "unknown" : action,
                        resource == null ? "" : resource,
                        buildLegacyDiff(diffJson, beforeJson, afterJson, meta),
                        success ? 1 : 0,
                        Instant.now().toString());
            } catch (Exception ignored2) {
                // 表未就绪时不阻断运维操作
            }
        }
    }

    public List<Map<String, Object>> recent(int limit) {
        try {
            return jdbc.queryForList("""
                    SELECT id, operator, action, resource, diff_json, success,
                           client_ip, user_agent, before_json, after_json, created_at
                    FROM admin_audit_log ORDER BY id DESC LIMIT ?
                    """, Math.max(1, Math.min(limit, 200)));
        } catch (Exception e) {
            try {
                return jdbc.queryForList("""
                        SELECT id, operator, action, resource, diff_json, success, created_at
                        FROM admin_audit_log ORDER BY id DESC LIMIT ?
                        """, Math.max(1, Math.min(limit, 200)));
            } catch (Exception e2) {
                return List.of();
            }
        }
    }

    private static String buildLegacyDiff(String diffJson, String beforeJson, String afterJson, RequestMeta meta) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"diff\":").append(diffJson == null ? "{}" : diffJson);
        if (beforeJson != null) {
            sb.append(",\"before\":").append(beforeJson);
        }
        if (afterJson != null) {
            sb.append(",\"after\":").append(afterJson);
        }
        sb.append(",\"clientIp\":\"").append(meta.clientIp() == null ? "" : meta.clientIp()).append("\"");
        sb.append(",\"userAgent\":\"").append(escape(meta.userAgent())).append("\"}");
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static RequestMeta currentRequestMeta() {
        try {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            if (attrs == null) {
                return new RequestMeta(null, null);
            }
            HttpServletRequest req = attrs.getRequest();
            String xff = req.getHeader("X-Forwarded-For");
            String ip = (xff != null && !xff.isBlank())
                    ? xff.split(",")[0].trim()
                    : req.getRemoteAddr();
            String ua = req.getHeader("User-Agent");
            return new RequestMeta(ip, ua);
        } catch (Exception e) {
            return new RequestMeta(null, null);
        }
    }

    private record RequestMeta(String clientIp, String userAgent) {}
}
