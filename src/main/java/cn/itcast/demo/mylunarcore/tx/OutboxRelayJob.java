package cn.itcast.demo.mylunarcore.tx;

import cn.itcast.demo.mylunarcore.common.AppLogger;
import cn.itcast.demo.mylunarcore.common.LogCategory;
import cn.itcast.demo.mylunarcore.common.ClusterJobLock;
import org.slf4j.Logger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Outbox 投递预演：扫描 {@code local_tx_log} PENDING 记录并标记为已投递/失败，
 * 为微服务拆分后的可靠消息投递铺路（当前仅日志 + 状态推进）。
 */
@Component
public class OutboxRelayJob {

    private static final Logger log = AppLogger.logger(LogCategory.SYSTEM, OutboxRelayJob.class);

    private final LocalTxLogService localTxLogService;
    private final JdbcTemplate jdbc;
    private final ClusterJobLock clusterJobLock;
    private final boolean enabled;

    public OutboxRelayJob(LocalTxLogService localTxLogService,
                          JdbcTemplate jdbc,
                          ObjectProvider<ClusterJobLock> clusterJobLockProvider,
                          @Value("${lunarcore.outbox.relay-enabled:false}") boolean enabled) {
        this.localTxLogService = localTxLogService;
        this.jdbc = jdbc;
        this.clusterJobLock = clusterJobLockProvider.getIfAvailable();
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${lunarcore.outbox.relay-interval-ms:15000}")
    public void relay() {
        if (!enabled) {
            return;
        }
        Runnable work = () -> {
            List<Map<String, Object>> pending = localTxLogService.listPending(50);
            for (Map<String, Object> row : pending) {
                String txId = String.valueOf(row.get("tx_id"));
                String bizType = String.valueOf(row.get("biz_type"));
                try {
                    // 预演：真实拆分时在此投递 MQ / HTTP 事件
                    log.info("outbox_relay dry-run txId={} bizType={}", txId, bizType);
                    localTxLogService.markCommitted(txId);
                    touchCursor(txId);
                } catch (Exception e) {
                    log.warn("outbox_relay failed txId={}: {}", txId, e.toString());
                    localTxLogService.markFailed(txId);
                }
            }
        };
        if (clusterJobLock != null) {
            clusterJobLock.tryRun("outbox-relay", Duration.ofSeconds(30), work);
        } else {
            work.run();
        }
    }

    private void touchCursor(String txId) {
        try {
            jdbc.update("""
                    INSERT INTO outbox_relay_cursor (worker_name, last_tx_id, updated_at)
                    VALUES ('default', ?, ?)
                    ON DUPLICATE KEY UPDATE last_tx_id = VALUES(last_tx_id), updated_at = VALUES(updated_at)
                    """, txId, Instant.now().toString());
        } catch (Exception ignored) {
        }
    }
}
