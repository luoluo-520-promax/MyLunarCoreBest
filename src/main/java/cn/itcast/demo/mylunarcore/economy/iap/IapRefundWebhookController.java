package cn.itcast.demo.mylunarcore.economy.iap;

import cn.itcast.demo.mylunarcore.common.AppLogger;
import cn.itcast.demo.mylunarcore.common.LogCategory;
import org.slf4j.Logger;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * IAP Server-to-Server 退款/撤销通知入口（Google RTDN / Apple ASN 适配层）。
 * <p>
 * 生产环境应校验 JWT/签名；此处先落库意图并触发负资产冻结。
 */
@RestController
@RequestMapping("/api/iap/notify")
public class IapRefundWebhookController {

    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_DATA, IapRefundWebhookController.class);

    private final NegativeBalanceFreezeService freezeService;
    private final IapOrderService orderService;

    public IapRefundWebhookController(NegativeBalanceFreezeService freezeService, IapOrderService orderService) {
        this.freezeService = freezeService;
        this.orderService = orderService;
    }

    @PostMapping("/google-rtdn")
    public ResponseEntity<Map<String, Object>> googleRtdn(@RequestBody Map<String, Object> body) {
        return handleRefund("GOOGLE", body);
    }

    @PostMapping("/apple-asn")
    public ResponseEntity<Map<String, Object>> appleAsn(@RequestBody Map<String, Object> body) {
        return handleRefund("APPLE", body);
    }

    private ResponseEntity<Map<String, Object>> handleRefund(String channel, Map<String, Object> body) {
        String notificationType = String.valueOf(body.getOrDefault("notificationType",
                body.getOrDefault("type", "REFUND")));
        if (!notificationType.toUpperCase().contains("REFUND")
                && !notificationType.toUpperCase().contains("REVOKE")
                && !notificationType.toUpperCase().contains("VOIDED")) {
            return ResponseEntity.ok(Map.of("ok", true, "ignored", true, "type", notificationType));
        }
        int playerId = toInt(body.get("playerId"), toInt(body.get("uid"), 0));
        String channelTx = String.valueOf(body.getOrDefault("channelTxId",
                body.getOrDefault("purchaseToken", body.getOrDefault("transactionId", ""))));
        int currencyId = toInt(body.get("currencyId"), 101);
        int amount = toInt(body.get("amount"), toInt(body.get("clawbackAmount"), 0));

        // 若订单服务能解析到已发货金额则优先使用
        if (amount <= 0 && !channelTx.isBlank()) {
            amount = orderService.estimateClawbackAmount(channelTx);
        }
        if (playerId <= 0 || amount <= 0) {
            log.warn("iap refund notify incomplete channel={} body={}", channel, body);
            return ResponseEntity.badRequest().body(Map.of("ok", false, "reason", "missing_player_or_amount"));
        }
        var state = freezeService.applyRefundClawback(playerId, currencyId, amount, channelTx,
                NegativeBalanceFreezeService.FreezeReason.IAP_REFUND);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("channel", channel);
        out.put("frozen", state != null && state.frozen());
        out.put("playerId", playerId);
        out.put("amount", amount);
        return ResponseEntity.ok(out);
    }

    private static int toInt(Object v, int def) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v != null) {
            try {
                return Integer.parseInt(String.valueOf(v));
            } catch (NumberFormatException ignored) {
            }
        }
        return def;
    }
}
