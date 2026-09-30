package cn.itcast.demo.mylunarcore.tx;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 本地事务日志（outbox）：记录扣费/发奖意图，供分布式拆分后的补偿与对账。
 * 当前单体场景与业务同库同事务写入；未来拆服务可改为可靠消息投递。
 */
@Service
public class LocalTxLogService {

    public enum TxStatus {
        PENDING,
        COMMITTED,
        COMPENSATING,
        COMPENSATED,
        FAILED
    }

    private final JdbcTemplate jdbc;

    public LocalTxLogService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String begin(String bizType, int playerId, String payloadJson) {
        String txId = UUID.randomUUID().toString().replace("-", "");
        try {
            jdbc.update("""
                    INSERT INTO local_tx_log (tx_id, biz_type, player_id, payload_json, status, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    txId, bizType, playerId, payloadJson == null ? "{}" : payloadJson,
                    TxStatus.PENDING.name(), Instant.now().toString(), Instant.now().toString());
        } catch (Exception ignored) {
            // 表未就绪时仍返回 txId，供业务日志关联
        }
        return txId;
    }

    @Transactional
    public void markCommitted(String txId) {
        try {
            updateStatus(txId, TxStatus.COMMITTED);
        } catch (Exception ignored) {
        }
    }

    @Transactional
    public void markFailed(String txId) {
        try {
            updateStatus(txId, TxStatus.FAILED);
        } catch (Exception ignored) {
        }
    }

    @Transactional
    public void markCompensated(String txId) {
        try {
            updateStatus(txId, TxStatus.COMPENSATED);
        } catch (Exception ignored) {
        }
    }

    @Transactional
    public void markCompensating(String txId) {
        try {
            updateStatus(txId, TxStatus.COMPENSATING);
        } catch (Exception ignored) {
        }
    }

    public List<Map<String, Object>> listFailed(int limit) {
        try {
            return jdbc.queryForList("""
                    SELECT tx_id, biz_type, player_id, payload_json, status, created_at
                    FROM local_tx_log WHERE status = ? ORDER BY created_at ASC LIMIT ?
                    """, TxStatus.FAILED.name(), Math.max(1, limit));
        } catch (Exception e) {
            return List.of();
        }
    }

    public List<Map<String, Object>> listPending(int limit) {
        try {
            return jdbc.queryForList("""
                    SELECT tx_id, biz_type, player_id, payload_json, status, created_at
                    FROM local_tx_log WHERE status = ? ORDER BY created_at ASC LIMIT ?
                    """, TxStatus.PENDING.name(), Math.max(1, limit));
        } catch (Exception e) {
            return List.of();
        }
    }

    private void updateStatus(String txId, TxStatus status) {
        jdbc.update("UPDATE local_tx_log SET status = ?, updated_at = ? WHERE tx_id = ?",
                status.name(), Instant.now().toString(), txId);
    }
}
