package cn.itcast.demo.mylunarcore.economy.iap;

/**
 * IAP 订单状态机：
 * CREATED → PAYING → PAID → GRANTED
 *              ↘ FAILED / CLOSED
 * PAID 若发货失败 → GRANT_RETRY
 */
public enum IapOrderStatus {
    CREATED,
    PAYING,
    PAID,
    GRANTED,
    GRANT_RETRY,
    FAILED,
    CLOSED
}
