package cn.itcast.demo.mylunarcore.repo;

import cn.itcast.demo.mylunarcore.player.PlayerCurrencyHelper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 玩家钱包数据访问层。
 * <p>
 * 货币余额以 JSON 字符串存储在 {@code player.currency} 列；
 * 变更通过 {@code SELECT ... FOR UPDATE} + {@code data_version} 条件更新，并写入 {@code wallet_ledger}。
 * 扣费语义等价于：{@code UPDATE ... SET balance=balance-cost WHERE uid=? AND version=? AND balance>=cost}；
 * 因货币为 JSON 多币种快照，采用读版本→改 Map→{@code updateCurrencyOptimistic} CAS，影响行数为 0 则重试/拒绝。
 */
@Repository
public class WalletRepository {

    private final JdbcTemplate jdbcTemplate;

    public WalletRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 行锁加载货币与 data_version，需在事务内调用。
     */
    public CurrencyLockRow lockCurrency(int playerId) {
        String sql = "SELECT currency, data_version FROM player WHERE uid = ? LIMIT 1 FOR UPDATE";
        return jdbcTemplate.query(sql, rs -> {
            if (!rs.next()) {
                return null;
            }
            String json = rs.getString("currency");
            long version = rs.getLong("data_version");
            return new CurrencyLockRow(new HashMap<>(PlayerCurrencyHelper.parseCurrency(json)), version);
        }, playerId);
    }

    public Map<Integer, Integer> loadCurrency(int playerId) {
        String sql = "SELECT currency FROM player WHERE uid = ? LIMIT 1";
        String json = jdbcTemplate.query(sql, rs -> rs.next() ? rs.getString("currency") : null, playerId);
        return new HashMap<>(PlayerCurrencyHelper.parseCurrency(json));
    }

    /**
     * 条件更新货币快照并递增 data_version。
     *
     * @return 是否更新成功（版本冲突或 uid 不存在时为 false）
     */
    public boolean updateCurrencyOptimistic(int playerId, Map<Integer, Integer> currency, long expectedVersion) {
        String json = PlayerCurrencyHelper.toCurrencyJson(currency);
        String sql = "UPDATE player SET currency = ?, data_version = data_version + 1, updated_at = NOW() "
                + "WHERE uid = ? AND data_version = ?";
        return jdbcTemplate.update(sql, json, playerId, expectedVersion) > 0;
    }

    /**
     * @deprecated 无版本条件全量覆写会丢失并发更新；请使用 {@link #updateCurrencyOptimistic}。
     * 调用将直接抛出异常，防止误用。
     */
    @Deprecated
    public boolean updateCurrency(int playerId, Map<Integer, Integer> currency) {
        throw new UnsupportedOperationException(
                "WalletRepository.updateCurrency is disabled; use updateCurrencyOptimistic with data_version");
    }

    public void insertLedger(int playerId,
                             int currencyId,
                             int delta,
                             String reason,
                             int balanceBefore,
                             int balanceAfter,
                             String txId) {
        String sql = "INSERT INTO wallet_ledger(uid, currency_id, delta, reason, balance_before, balance_after, tx_id, created_at) "
                + "VALUES(?, ?, ?, ?, ?, ?, ?, NOW())";
        jdbcTemplate.update(sql,
                playerId,
                currencyId,
                delta,
                reason == null ? "" : reason,
                balanceBefore,
                balanceAfter,
                txId == null || txId.isBlank() ? UUID.randomUUID().toString() : txId);
    }

    public record CurrencyLockRow(Map<Integer, Integer> balance, long dataVersion) {}
}
