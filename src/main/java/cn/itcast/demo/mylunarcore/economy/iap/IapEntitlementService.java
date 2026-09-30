package cn.itcast.demo.mylunarcore.economy.iap;

import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.BitSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 月卡/基金等权益（内存实现，对应建议表 iap_entitlement）及首充标记。
 */
@Service
public class IapEntitlementService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    public record Entitlement(
            int playerId,
            String packKind,
            Instant expireAt,
            Instant createdAt,
            BitSet claimBitmap,
            int dailyCurrencyId,
            int dailyAmount,
            int totalDays
    ) {
    }

    private final ConcurrentHashMap<String, Entitlement> entitlements = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, Boolean> firstTopupDone = new ConcurrentHashMap<>();

    public boolean isFirstTopupDone(int playerId) {
        return Boolean.TRUE.equals(firstTopupDone.get(playerId));
    }

    public void markFirstTopupDone(int playerId) {
        firstTopupDone.put(playerId, Boolean.TRUE);
    }

    public Entitlement get(int playerId, String packKind) {
        return entitlements.get(key(playerId, packKind));
    }

    public Entitlement grantMonthlyCard(int playerId, int currencyId, int dailyAmount, int days, Instant now) {
        Instant expireAt = now.plusSeconds(days * 86_400L);
        Entitlement entitlement = new Entitlement(
                playerId, "MONTHLY_CARD", expireAt, now, new BitSet(days),
                currencyId, dailyAmount, days);
        entitlements.put(key(playerId, "MONTHLY_CARD"), entitlement);
        return entitlement;
    }

    public Entitlement grantLifetimeMarker(int playerId, String packKind, Instant now) {
        Entitlement entitlement = new Entitlement(
                playerId, packKind, Instant.MAX, now, new BitSet(), 0, 0, 0);
        entitlements.put(key(playerId, packKind), entitlement);
        return entitlement;
    }

    /**
     * 月卡日领：按创建日起第 N 天领取，过期或已领返回 null。
     *
     * @return 领取到的 (currencyId, amount)；失败 null
     */
    public int[] claimDaily(int playerId, Instant now) {
        Entitlement entitlement = get(playerId, "MONTHLY_CARD");
        if (entitlement == null) {
            return null;
        }
        if (now.isAfter(entitlement.expireAt())) {
            return null;
        }
        LocalDate start = entitlement.createdAt().atZone(ZONE).toLocalDate();
        LocalDate today = now.atZone(ZONE).toLocalDate();
        int dayIndex = (int) (today.toEpochDay() - start.toEpochDay());
        if (dayIndex < 0 || dayIndex >= entitlement.totalDays()) {
            return null;
        }
        synchronized (entitlement.claimBitmap()) {
            if (entitlement.claimBitmap().get(dayIndex)) {
                return null;
            }
            entitlement.claimBitmap().set(dayIndex);
        }
        return new int[]{entitlement.dailyCurrencyId(), entitlement.dailyAmount()};
    }

    public int remainingDays(int playerId, Instant now) {
        Entitlement entitlement = get(playerId, "MONTHLY_CARD");
        if (entitlement == null || now.isAfter(entitlement.expireAt())) {
            return 0;
        }
        long seconds = entitlement.expireAt().getEpochSecond() - now.getEpochSecond();
        return (int) Math.max(0, (seconds + 86_399) / 86_400);
    }

    static String key(int playerId, String packKind) {
        return playerId + ":" + (packKind == null ? "" : packKind);
    }
}
