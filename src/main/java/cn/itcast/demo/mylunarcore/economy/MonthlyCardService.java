package cn.itcast.demo.mylunarcore.economy;

import cn.itcast.demo.mylunarcore.common.PeriodicResetService;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.player.GameSession;
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
import cn.itcast.demo.mylunarcore.player.StaminaService;
import cn.itcast.demo.mylunarcore.protocol.QolSocialSystemProto;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * 月卡 / 小月卡：维护激活与剩余天数，日切时发放额外体力与货币。
 */
@Service
public class MonthlyCardService {

    private static final Logger log = LoggerFactory.getLogger(MonthlyCardService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    public static final String KIND_MONTHLY = "MONTHLY";
    public static final String KIND_MINI = "MINI_MONTHLY";

    public record CardStatus(String kind, boolean active, int remainingDays,
                             int dailyCurrencyId, int dailyCurrencyAmt, int dailyStaminaAmt) {}

    public record GrantTodayResult(CardStatus status, boolean granted) {}

    private final JdbcTemplate jdbc;
    private final WalletApplicationService wallet;
    private final ObjectProvider<StaminaService> staminaProvider;
    private final PeriodicResetService periodicResetService;
    private final ObjectProvider<GameSessionManager> sessionProvider;

    public MonthlyCardService(JdbcTemplate jdbc,
                              WalletApplicationService wallet,
                              ObjectProvider<StaminaService> staminaProvider,
                              PeriodicResetService periodicResetService) {
        this(jdbc, wallet, staminaProvider, periodicResetService, null);
    }

    public MonthlyCardService(JdbcTemplate jdbc,
                              WalletApplicationService wallet,
                              ObjectProvider<StaminaService> staminaProvider,
                              PeriodicResetService periodicResetService,
                              ObjectProvider<GameSessionManager> sessionProvider) {
        this.jdbc = jdbc;
        this.wallet = wallet;
        this.staminaProvider = staminaProvider;
        this.periodicResetService = periodicResetService;
        this.sessionProvider = sessionProvider;
    }

    @PostConstruct
    public void registerDailyGrant() {
        periodicResetService.registerDaily(this::onDailyReset);
    }

    @Transactional
    public CardStatus activate(int playerId, String kind, int days,
                               int currencyId, int currencyAmt, int staminaAmt) {
        Instant now = Instant.now();
        Instant expire = now.plus(days, ChronoUnit.DAYS);
        String cardKind = kind == null || kind.isBlank() ? KIND_MONTHLY : kind;
        jdbc.update("""
                INSERT INTO player_monthly_card
                (player_id, card_kind, active, expire_at, remaining_days,
                 daily_currency_id, daily_currency_amt, daily_stamina_amt, last_grant_day)
                VALUES (?, ?, 1, ?, ?, ?, ?, ?, '')
                ON DUPLICATE KEY UPDATE active=1, expire_at=VALUES(expire_at),
                  remaining_days=VALUES(remaining_days),
                  daily_currency_id=VALUES(daily_currency_id),
                  daily_currency_amt=VALUES(daily_currency_amt),
                  daily_stamina_amt=VALUES(daily_stamina_amt),
                  updated_at=CURRENT_TIMESTAMP
                """, playerId, cardKind, java.sql.Timestamp.from(expire), days,
                currencyId, currencyAmt, staminaAmt);
        CardStatus st = status(playerId, cardKind);
        pushNotify(playerId, st, false);
        return st;
    }

    public CardStatus status(int playerId, String kind) {
        String cardKind = kind == null || kind.isBlank() ? KIND_MONTHLY : kind;
        try {
            List<CardStatus> list = jdbc.query("""
                    SELECT card_kind, active, remaining_days, daily_currency_id,
                           daily_currency_amt, daily_stamina_amt, expire_at
                    FROM player_monthly_card WHERE player_id=? AND card_kind=?
                    """, (rs, i) -> {
                Instant expire = rs.getTimestamp("expire_at").toInstant();
                boolean active = rs.getInt("active") == 1 && Instant.now().isBefore(expire);
                int remain = active
                        ? (int) Math.max(0, ChronoUnit.DAYS.between(LocalDate.now(ZONE),
                        expire.atZone(ZONE).toLocalDate()) + 1)
                        : 0;
                return new CardStatus(rs.getString("card_kind"), active, remain,
                        rs.getInt("daily_currency_id"), rs.getInt("daily_currency_amt"),
                        rs.getInt("daily_stamina_amt"));
            }, playerId, cardKind);
            return list.isEmpty() ? new CardStatus(cardKind, false, 0, 0, 0, 0) : list.get(0);
        } catch (Exception e) {
            return new CardStatus(cardKind, false, 0, 0, 0, 0);
        }
    }

    /**
     * 若月卡有效且今日尚未发放，则补发今日奖励。
     */
    @Transactional
    public GrantTodayResult grantTodayIfNeeded(int playerId) {
        return grantTodayIfNeeded(playerId, KIND_MONTHLY);
    }

    @Transactional
    public GrantTodayResult grantTodayIfNeeded(int playerId, String kind) {
        String cardKind = kind == null || kind.isBlank() ? KIND_MONTHLY : kind;
        String today = LocalDate.now(ZONE).toString();
        CardStatus st = status(playerId, cardKind);
        if (!st.active()) {
            return new GrantTodayResult(st, false);
        }
        try {
            List<String> last = jdbc.query("""
                    SELECT last_grant_day FROM player_monthly_card
                    WHERE player_id=? AND card_kind=?
                    """, (rs, i) -> rs.getString("last_grant_day"), playerId, cardKind);
            String lastDay = last.isEmpty() || last.get(0) == null ? "" : last.get(0);
            if (today.equals(lastDay)) {
                return new GrantTodayResult(st, false);
            }
            if (st.dailyCurrencyAmt() > 0 && st.dailyCurrencyId() > 0) {
                wallet.add(playerId, st.dailyCurrencyId(), st.dailyCurrencyAmt(),
                        "monthly_card:" + cardKind);
            }
            StaminaService stamina = staminaProvider.getIfAvailable();
            if (stamina != null && st.dailyStaminaAmt() > 0) {
                try {
                    stamina.addBonus(playerId, st.dailyStaminaAmt(), "monthly_card");
                } catch (Exception ex) {
                    log.debug("stamina bonus skipped: {}", ex.getMessage());
                }
            }
            jdbc.update("""
                    UPDATE player_monthly_card SET last_grant_day=?, remaining_days=?,
                      updated_at=CURRENT_TIMESTAMP
                    WHERE player_id=? AND card_kind=?
                    """, today, st.remainingDays(), playerId, cardKind);
            CardStatus after = status(playerId, cardKind);
            pushNotify(playerId, after, true);
            return new GrantTodayResult(after, true);
        } catch (Exception e) {
            log.warn("grantTodayIfNeeded failed: {}", e.toString());
            return new GrantTodayResult(st, false);
        }
    }

    public void pushNotify(int playerId, CardStatus status, boolean grantedToday) {
        if (playerId <= 0 || status == null || sessionProvider == null) {
            return;
        }
        GameSessionManager sessions = sessionProvider.getIfAvailable();
        if (sessions == null) {
            return;
        }
        GameSession session = sessions.getOrNull(playerId);
        if (session == null) {
            return;
        }
        QolSocialSystemProto.MonthlyCardScNotify notify =
                QolSocialSystemProto.MonthlyCardScNotify.newBuilder()
                        .setCardKind(status.kind() == null ? "" : status.kind())
                        .setActive(status.active())
                        .setRemainingDays(status.remainingDays())
                        .setGrantedToday(grantedToday)
                        .setCurrencyId(status.dailyCurrencyId())
                        .setCurrencyAmt(status.dailyCurrencyAmt())
                        .setStaminaAmt(status.dailyStaminaAmt())
                        .build();
        session.send(new GamePacket(CmdIds.MONTHLY_CARD_SC_NOTIFY, notify.toByteArray()));
    }

    public void onDailyReset(LocalDate day) {
        String dayStr = day.toString();
        try {
            jdbc.query("""
                    SELECT player_id, card_kind, daily_currency_id, daily_currency_amt,
                           daily_stamina_amt, expire_at
                    FROM player_monthly_card
                    WHERE active=1 AND expire_at > CURRENT_TIMESTAMP AND last_grant_day <> ?
                    """, rs -> {
                while (rs.next()) {
                    int pid = rs.getInt("player_id");
                    String kind = rs.getString("card_kind");
                    int cid = rs.getInt("daily_currency_id");
                    int camt = rs.getInt("daily_currency_amt");
                    int stam = rs.getInt("daily_stamina_amt");
                    Instant expire = rs.getTimestamp("expire_at").toInstant();
                    int remain = (int) Math.max(0, ChronoUnit.DAYS.between(day,
                            expire.atZone(ZONE).toLocalDate()));
                    if (camt > 0 && cid > 0) {
                        wallet.add(pid, cid, camt, "monthly_card:" + kind);
                    }
                    StaminaService stamina = staminaProvider.getIfAvailable();
                    if (stamina != null && stam > 0) {
                        try {
                            stamina.addBonus(pid, stam, "monthly_card");
                        } catch (Exception ex) {
                            log.debug("stamina bonus skipped: {}", ex.getMessage());
                        }
                    }
                    int activeFlag = remain <= 0 ? 0 : 1;
                    jdbc.update("""
                            UPDATE player_monthly_card SET last_grant_day=?, remaining_days=?,
                              active=?, updated_at=CURRENT_TIMESTAMP
                            WHERE player_id=? AND card_kind=?
                            """, dayStr, remain, activeFlag, pid, kind);
                    pushNotify(pid, new CardStatus(kind, activeFlag == 1, remain, cid, camt, stam), true);
                }
                return null;
            }, dayStr);
            log.info("MonthlyCard daily grant day={} processed", dayStr);
        } catch (Exception e) {
            log.warn("MonthlyCard daily grant failed: {}", e.toString());
        }
    }
}
