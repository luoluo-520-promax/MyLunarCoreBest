package cn.itcast.demo.mylunarcore.economy.iap;

import java.time.Instant;

/**
 * 内存态 IAP 订单，字段对齐建议表 {@code iap_order}。
 * <p>
 * 状态机（方法均 synchronized，防并发确认/发货竞态）：
 * CREATED → markPaying → PAYING；
 * markPaid → PAID（已 GRANTED/PAID 则幂等忽略）；
 * markGranted → GRANTED；
 * markGrantRetry 仅在 PAID/GRANT_RETRY 时进入 GRANT_RETRY；
 * markFailed 在未 GRANTED 时可置 FAILED。
 */
public final class IapOrder {

    private final String orderId; // 业务订单号，客户端/渠道回传用
    private final int playerId; // 下单玩家
    private final int shopId; // 商店配置 ID
    private final int shopItemId; // 货架位 ID
    private final String skuId; // 渠道商品 SKU
    private final int amountCents; // 标价（分）
    private final Instant createdAt; // 创建时刻
    private volatile IapOrderStatus status; // 当前状态，读多写少用 volatile + 方法锁
    private volatile String channel; // 支付渠道名，如 google/apple
    private volatile String channelTxId; // 渠道交易号，对账主键
    private volatile Instant paidAt; // 确认支付成功时刻
    private volatile Instant grantedAt; // 发货完成时刻

    /**
     * 新建订单，初始状态 {@link IapOrderStatus#CREATED}。
     */
    public IapOrder(String orderId, int playerId, int shopId, int shopItemId,
                    String skuId, int amountCents, Instant createdAt) {
        this.orderId = orderId;
        this.playerId = playerId;
        this.shopId = shopId;
        this.shopItemId = shopItemId;
        this.skuId = skuId;
        this.amountCents = amountCents;
        this.createdAt = createdAt;
        this.status = IapOrderStatus.CREATED;
    }

    public String orderId() {
        return orderId;
    }

    public int playerId() {
        return playerId;
    }

    public int shopId() {
        return shopId;
    }

    public int shopItemId() {
        return shopItemId;
    }

    public String skuId() {
        return skuId;
    }

    public int amountCents() {
        return amountCents;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public IapOrderStatus status() {
        return status;
    }

    public String channel() {
        return channel;
    }

    public String channelTxId() {
        return channelTxId;
    }

    public Instant paidAt() {
        return paidAt;
    }

    public Instant grantedAt() {
        return grantedAt;
    }

    /** 仅 CREATED 可进入 PAYING（拉起支付中）。 */
    public synchronized void markPaying() {
        if (status == IapOrderStatus.CREATED) {
            status = IapOrderStatus.PAYING;
        }
    }

    /**
     * 渠道验单成功：写入 channel/txId/paidAt 并置 PAID。
     * 已是 PAID/GRANTED 则直接返回，保证幂等。
     */
    public synchronized void markPaid(String channel, String channelTxId, Instant at) {
        if (status == IapOrderStatus.GRANTED || status == IapOrderStatus.PAID) {
            return;
        }
        this.channel = channel;
        this.channelTxId = channelTxId;
        this.paidAt = at;
        this.status = IapOrderStatus.PAID;
    }

    /** 道具/权益发放完成：记录 grantedAt 并置 GRANTED。 */
    public synchronized void markGranted(Instant at) {
        this.grantedAt = at;
        this.status = IapOrderStatus.GRANTED;
    }

    /** 发货失败待重试：仅从 PAID 或已在 GRANT_RETRY 时维持/进入 GRANT_RETRY。 */
    public synchronized void markGrantRetry() {
        if (status == IapOrderStatus.PAID || status == IapOrderStatus.GRANT_RETRY) {
            status = IapOrderStatus.GRANT_RETRY;
        }
    }

    /** 终态失败：已 GRANTED 的订单不可再改成 FAILED，防止误伤已发货单。 */
    public synchronized void markFailed() {
        if (status != IapOrderStatus.GRANTED) {
            status = IapOrderStatus.FAILED;
        }
    }
}
