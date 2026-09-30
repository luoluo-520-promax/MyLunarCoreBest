package cn.itcast.demo.mylunarcore.item;

import cn.itcast.demo.mylunarcore.economy.WalletApplicationService;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.protocol.ItemSystemProto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 过期活动道具灰烬转化：按汇率换成信用点并推送 {@code ItemRecycleNotify}，禁止静默删除。
 */
@Service
public class ExpiredItemRecycleService {

    public static final int CREDIT_CURRENCY_ID = 101;

    public record RecycleResult(int playerId, int itemId, int count, int credits) {}

    private static final Logger log = LoggerFactory.getLogger(ExpiredItemRecycleService.class);

    private final JdbcTemplate jdbc;
    private final ObjectProvider<WalletApplicationService> walletProvider;
    private final ObjectProvider<GameSessionManager> sessionProvider;

    public ExpiredItemRecycleService(JdbcTemplate jdbc,
                                     ObjectProvider<WalletApplicationService> walletProvider,
                                     ObjectProvider<GameSessionManager> sessionProvider) {
        this.jdbc = jdbc;
        this.walletProvider = walletProvider;
        this.sessionProvider = sessionProvider;
    }

    /**
     * 扫描并转化已过期的限时道具。由 {@link cn.itcast.demo.mylunarcore.archive.DataArchiveJob} 触发。
     */
    public List<RecycleResult> recycleDue() {
        List<RecycleResult> out = new ArrayList<>();
        if (jdbc == null) {
            return out;
        }
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList("""
                    SELECT player_id, item_id, count, COALESCE(credit_per_count, 10) AS rate
                    FROM player_timed_item
                    WHERE expire_at < NOW() AND recycled = 0 AND count > 0
                    LIMIT 500
                    """);
        } catch (Exception e) {
            log.debug("expired item scan skipped: {}", e.getMessage());
            return out;
        }
        WalletApplicationService wallet = walletProvider == null ? null : walletProvider.getIfAvailable();
        GameSessionManager sessions = sessionProvider == null ? null : sessionProvider.getIfAvailable();
        for (Map<String, Object> row : rows) {
            int playerId = ((Number) row.get("player_id")).intValue();
            int itemId = ((Number) row.get("item_id")).intValue();
            int count = ((Number) row.get("count")).intValue();
            int rate = ((Number) row.get("rate")).intValue();
            int credits = Math.max(0, count * Math.max(1, rate));
            try {
                jdbc.update("""
                        UPDATE player_timed_item SET recycled = 1, recycled_credits = ?, recycled_at = NOW()
                        WHERE player_id = ? AND item_id = ? AND recycled = 0
                        """, credits, playerId, itemId);
            } catch (Exception e) {
                continue;
            }
            if (wallet != null && credits > 0) {
                wallet.add(playerId, CREDIT_CURRENCY_ID, credits, "item_recycle_expired");
            }
            RecycleResult result = new RecycleResult(playerId, itemId, count, credits);
            out.add(result);
            pushNotify(sessions, result);
        }
        if (!out.isEmpty()) {
            log.info("expired item recycled rows={}", out.size());
        }
        return out;
    }

    /**
     * 单测/运营入口：转化指定条目（内存汇率）。
     */
    public RecycleResult recycleOne(int playerId, int itemId, int count, int creditPerCount) {
        int credits = Math.max(0, count * Math.max(1, creditPerCount));
        WalletApplicationService wallet = walletProvider == null ? null : walletProvider.getIfAvailable();
        if (wallet != null && credits > 0) {
            wallet.add(playerId, CREDIT_CURRENCY_ID, credits, "item_recycle_expired");
        }
        RecycleResult result = new RecycleResult(playerId, itemId, count, credits);
        GameSessionManager sessions = sessionProvider == null ? null : sessionProvider.getIfAvailable();
        pushNotify(sessions, result);
        return result;
    }

    private void pushNotify(GameSessionManager sessions, RecycleResult result) {
        if (sessions == null) {
            return;
        }
        GameSession session = sessions.getOrNull(result.playerId());
        if (session == null) {
            return;
        }
        ItemSystemProto.ItemRecycleNotify notify = ItemSystemProto.ItemRecycleNotify.newBuilder()
                .setItemId(result.itemId())
                .setRecycledCount(result.count())
                .setCreditCurrencyId(CREDIT_CURRENCY_ID)
                .setCreditAmount(result.credits())
                .setReason("expired")
                .build();
        session.send(new GamePacket(CmdIds.ITEM_RECYCLE_SC_NOTIFY, notify.toByteArray()));
    }
}
