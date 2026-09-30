package cn.itcast.demo.mylunarcore.tx;

import cn.itcast.demo.mylunarcore.common.AppLogger;
import cn.itcast.demo.mylunarcore.common.ClusterJobLock;
import cn.itcast.demo.mylunarcore.common.LogCategory;
import cn.itcast.demo.mylunarcore.economy.WalletApplicationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 失败事务补偿：扫描 {@code local_tx_log} FAILED 记录，对已扣费未发奖的 gacha 做退款，
 * 并对 wallet_deduct 中明确标记需补偿的条目做回补。
 */
@Component
public class TxCompensationJob {

    private static final Logger log = AppLogger.logger(LogCategory.SYSTEM, TxCompensationJob.class);

    private final LocalTxLogService localTxLogService;
    private final WalletApplicationService walletApplicationService;
    private final ObjectMapper objectMapper;
    private final ClusterJobLock clusterJobLock;
    private final boolean enabled;

    public TxCompensationJob(LocalTxLogService localTxLogService,
                             WalletApplicationService walletApplicationService,
                             ObjectMapper objectMapper,
                             ObjectProvider<ClusterJobLock> clusterJobLockProvider,
                             @Value("${lunarcore.tx.compensation-enabled:true}") boolean enabled) {
        this.localTxLogService = localTxLogService;
        this.walletApplicationService = walletApplicationService;
        this.objectMapper = objectMapper;
        this.clusterJobLock = clusterJobLockProvider.getIfAvailable();
        this.enabled = enabled;
    }

    @Scheduled(fixedDelayString = "${lunarcore.tx.compensation-interval-ms:30000}")
    public void compensate() {
        if (!enabled) {
            return;
        }
        Runnable work = () -> {
            List<Map<String, Object>> failed = localTxLogService.listFailed(30);
            for (Map<String, Object> row : failed) {
                String txId = String.valueOf(row.get("tx_id"));
                String bizType = String.valueOf(row.get("biz_type"));
                int playerId = ((Number) row.getOrDefault("player_id", 0)).intValue();
                String payload = String.valueOf(row.getOrDefault("payload_json", "{}"));
                try {
                    localTxLogService.markCompensating(txId);
                    boolean ok = compensateOne(bizType, playerId, txId, payload);
                    if (ok) {
                        localTxLogService.markCompensated(txId);
                        log.info("tx_compensated txId={} bizType={} playerId={}", txId, bizType, playerId);
                    } else {
                        localTxLogService.markFailed(txId);
                        log.warn("tx_compensate_skip txId={} bizType={}", txId, bizType);
                    }
                } catch (Exception e) {
                    localTxLogService.markFailed(txId);
                    log.warn("tx_compensate_failed txId={}: {}", txId, e.toString());
                }
            }
        };
        if (clusterJobLock != null) {
            clusterJobLock.tryRun("tx-compensation", Duration.ofSeconds(45), work);
        } else {
            work.run();
        }
    }

    private boolean compensateOne(String bizType, int playerId, String txId, String payload) throws Exception {
        if (playerId <= 0) {
            return false;
        }
        JsonNode node = objectMapper.readTree(payload == null || payload.isBlank() ? "{}" : payload);
        if ("gacha".equals(bizType)) {
            int currencyId = node.path("costCurrencyId").asInt(0);
            int totalCost = node.path("totalCost").asInt(0);
            if (currencyId <= 0 || totalCost <= 0) {
                // 未扣费或配置关闭扣费：无需退款，直接结案
                return true;
            }
            WalletApplicationService.WalletChangeResult refund =
                    walletApplicationService.add(playerId, currencyId, totalCost, "gacha_refund:" + txId);
            return refund.success();
        }
        if ("wallet_deduct".equals(bizType) && node.path("compensate").asBoolean(false)) {
            int currencyId = node.path("currencyId").asInt(0);
            int delta = node.path("delta").asInt(0);
            if (currencyId <= 0 || delta >= 0) {
                return false;
            }
            WalletApplicationService.WalletChangeResult refund =
                    walletApplicationService.add(playerId, currencyId, -delta, "wallet_refund:" + txId);
            return refund.success();
        }
        // 其它业务失败默认结案（避免无限重试），由人工对账
        return true;
    }
}
