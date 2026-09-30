package cn.itcast.demo.mylunarcore.economy;

/**
 * 钱包增量 WAL 日志条目：先内存扣减，再异步批量落盘。
 */
public record WalletDeltaLog(
        long seq,
        int playerId,
        int currencyId,
        int delta,
        String reason,
        long createdAtMs
) {}
