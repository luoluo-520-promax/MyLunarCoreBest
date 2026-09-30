package cn.itcast.demo.mylunarcore.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * 结构化 JSON 日志：统一输出 traceId/spanId/userIdHash/cmdId/costMs，便于 Loki 检索。
 * UID 哈希化：保留 4 位前缀便于关联，后缀不可逆（合规增强）。
 */
public final class StructuredJsonLogger {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /** 日志保留提示天数（由 logback/运维策略执行清理） */
    public static final int LOG_RETENTION_DAYS = 30;

    private StructuredJsonLogger() {}

    public static void info(Class<?> source, String event, long userId, int cmdId, long costMs, String message) {
        Logger log = LoggerFactory.getLogger(source);
        log.info(toJson("INFO", event, userId, cmdId, costMs, message));
    }

    public static void warn(Class<?> source, String event, long userId, int cmdId, long costMs, String message) {
        Logger log = LoggerFactory.getLogger(source);
        log.warn(toJson("WARN", event, userId, cmdId, costMs, message));
    }

    public static void error(Class<?> source, String event, long userId, int cmdId, long costMs, String message, Throwable t) {
        Logger log = LoggerFactory.getLogger(source);
        log.error(toJson("ERROR", event, userId, cmdId, costMs, message), t);
    }

    public static String toJson(String level, String event, long userId, int cmdId, long costMs, String message) {
        try {
            ObjectNode n = MAPPER.createObjectNode();
            n.put("level", level);
            n.put("event", event == null ? "" : event);
            n.put("traceId", nullToEmpty(MDC.get("traceId")));
            n.put("spanId", nullToEmpty(MDC.get("spanId")));
            n.put("userIdHash", hashUserId(userId));
            n.put("userIdPrefix", userIdPrefix(userId));
            n.put("cmdId", cmdId);
            n.put("costMs", costMs);
            n.put("message", SensitiveDataMasker.mask(message == null ? "" : message));
            n.put("ts", System.currentTimeMillis());
            n.put("retentionDays", LOG_RETENTION_DAYS);
            return n.toString();
        } catch (Exception e) {
            return "{\"level\":\"" + level + "\",\"message\":\"" + message + "\"}";
        }
    }

    /** 不可逆哈希；同 UID 稳定。 */
    public static String hashUserId(long userId) {
        if (userId <= 0) {
            return "";
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] dig = md.digest(("lunar-uid:" + userId).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(dig).substring(0, 16);
        } catch (Exception e) {
            return "hash_err";
        }
    }

    /** 保留前缀用于值班关联（不足 4 位则全给）。 */
    public static String userIdPrefix(long userId) {
        if (userId <= 0) {
            return "";
        }
        String s = Long.toString(userId);
        return s.length() <= 4 ? s : s.substring(0, 4);
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
