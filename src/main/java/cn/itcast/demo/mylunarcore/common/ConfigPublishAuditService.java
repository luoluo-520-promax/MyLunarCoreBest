package cn.itcast.demo.mylunarcore.common;

import cn.itcast.demo.mylunarcore.admin.AdminAuditLogService;
import org.slf4j.Logger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 配置发布审计：内存最近记录 + 持久化 admin_audit_log。
 */
@Service
public class ConfigPublishAuditService {

    private static final Logger log = AppLogger.logger(LogCategory.SYSTEM, ConfigPublishAuditService.class);

    private final AtomicLong versionSeq = new AtomicLong(1);
    private final CopyOnWriteArrayList<PublishRecord> history = new CopyOnWriteArrayList<>();
    private volatile PublishRecord lastSuccessful;
    private final AdminAuditLogService adminAuditLogService;

    public ConfigPublishAuditService(ObjectProvider<AdminAuditLogService> adminAuditLogServiceProvider) {
        this.adminAuditLogService = adminAuditLogServiceProvider == null
                ? null
                : adminAuditLogServiceProvider.getIfAvailable();
    }

    /** 单测便捷构造：不写库。 */
    public ConfigPublishAuditService() {
        this(null);
    }

    public record PublishRecord(
            long version,
            String operator,
            String action,
            List<String> files,
            boolean dryRun,
            boolean success,
            String message,
            Instant at
    ) {
    }

    public PublishRecord record(String operator, String action, List<String> files,
                                boolean dryRun, boolean success, String message) {
        PublishRecord record = new PublishRecord(
                versionSeq.getAndIncrement(),
                operator == null ? "system" : operator,
                action,
                files == null ? List.of() : List.copyOf(files),
                dryRun,
                success,
                message == null ? "" : message,
                Instant.now()
        );
        history.add(record);
        if (history.size() > 200) {
            history.remove(0);
        }
        if (success && !dryRun) {
            lastSuccessful = record;
        }
        log.info("Config publish audit: version={}, operator={}, action={}, dryRun={}, success={}, files={}",
                record.version(), record.operator(), record.action(), dryRun, success, record.files());
        if (adminAuditLogService != null) {
            String msg = record.message().replace('"', '\'');
            adminAuditLogService.record(
                    record.operator(),
                    record.action(),
                    String.join(",", record.files()),
                    "{dryRun:" + dryRun + ",message:'" + msg + "'}",
                    success);
        }
        return record;
    }

    public PublishRecord lastSuccessful() {
        return lastSuccessful;
    }

    public List<PublishRecord> recent(int limit) {
        int n = Math.max(1, Math.min(limit, history.size()));
        return new ArrayList<>(history.subList(Math.max(0, history.size() - n), history.size()));
    }

    public Map<String, Object> toMap(PublishRecord record) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("version", record.version());
        body.put("operator", record.operator());
        body.put("action", record.action());
        body.put("files", record.files());
        body.put("dryRun", record.dryRun());
        body.put("success", record.success());
        body.put("message", record.message());
        body.put("at", record.at().toString());
        return body;
    }
}
