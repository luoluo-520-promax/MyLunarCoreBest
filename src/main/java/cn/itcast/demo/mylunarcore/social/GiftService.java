package cn.itcast.demo.mylunarcore.social;

import cn.itcast.demo.mylunarcore.economy.WalletApplicationService;
import cn.itcast.demo.mylunarcore.repo.MailRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 好友赠礼：扣除发送方货币后，以邮件附件投递给接收方。
 */
@Service
public class GiftService {

    public record SendResult(boolean success, int retcode, String mailHint) {}

    private static final int DAILY_GIFT_LIMIT = 10;
    private static final int DEFAULT_GIFT_CURRENCY = 2;

    private final JdbcTemplate jdbc;
    private final WalletApplicationService wallet;
    private final MailRepository mailRepository;
    private final ConcurrentHashMap<String, AtomicInteger> dailyCount = new ConcurrentHashMap<>();

    public GiftService(JdbcTemplate jdbc,
                       WalletApplicationService wallet,
                       ObjectProvider<MailRepository> mailProvider) {
        this.jdbc = jdbc;
        this.wallet = wallet;
        this.mailRepository = mailProvider.getIfAvailable();
    }

    @Transactional
    public SendResult sendCurrencyGift(int fromPlayerId, int toPlayerId, int currencyId, int amount, String message) {
        if (fromPlayerId <= 0 || toPlayerId <= 0 || fromPlayerId == toPlayerId || amount <= 0) {
            return new SendResult(false, 1, "");
        }
        if (amount > 100_000) {
            return new SendResult(false, 2, "amount_cap");
        }
        String day = java.time.LocalDate.now().toString();
        int used = dailyCount.computeIfAbsent(fromPlayerId + ":" + day, k -> new AtomicInteger()).incrementAndGet();
        if (used > DAILY_GIFT_LIMIT) {
            dailyCount.get(fromPlayerId + ":" + day).decrementAndGet();
            return new SendResult(false, 3, "daily_limit");
        }
        int cid = currencyId > 0 ? currencyId : DEFAULT_GIFT_CURRENCY;
        WalletApplicationService.WalletChangeResult deduct = wallet.deduct(fromPlayerId, cid, amount, "gift_send");
        if (!deduct.success()) {
            dailyCount.get(fromPlayerId + ":" + day).decrementAndGet();
            return new SendResult(false, 4, "insufficient");
        }
        String body = (message == null || message.isBlank()) ? "送你一份心意" : message.trim();
        String attachments = "[{\"type\":\"currency\",\"id\":" + cid + ",\"count\":" + amount + "}]";
        String hint = "currency:" + cid + "x" + amount;
        if (mailRepository != null) {
            try {
                Timestamp expire = Timestamp.from(Instant.now().plus(14, ChronoUnit.DAYS));
                mailRepository.insertMail(toPlayerId, "好友赠礼", body, attachments, expire);
            } catch (Exception ignored) {
            }
        }
        recordGift(fromPlayerId, toPlayerId, cid, amount, body);
        return new SendResult(true, 0, hint);
    }

    private void recordGift(int from, int to, int currencyId, int amount, String msg) {
        try {
            jdbc.update("""
                    INSERT INTO player_gift_log (from_player_id, to_player_id, currency_id, amount, message, created_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, from, to, currencyId, amount, msg, Instant.now().toString());
        } catch (Exception ignored) {
        }
    }

    public Map<String, Object> dailyRemaining(int playerId) {
        String day = java.time.LocalDate.now().toString();
        int used = dailyCount.getOrDefault(playerId + ":" + day, new AtomicInteger()).get();
        return Map.of("used", used, "limit", DAILY_GIFT_LIMIT, "remain", Math.max(0, DAILY_GIFT_LIMIT - used));
    }
}
