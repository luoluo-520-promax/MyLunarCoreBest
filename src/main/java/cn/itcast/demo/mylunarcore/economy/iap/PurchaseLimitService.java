package cn.itcast.demo.mylunarcore.economy.iap;

import cn.itcast.demo.mylunarcore.economy.ShopConfigRepository;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.WeekFields;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 日/周/月/终身限购计数（内存实现，对应建议表 iap_purchase_limit）。
 */
@Service
public class PurchaseLimitService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final ConcurrentHashMap<String, AtomicInteger> counters = new ConcurrentHashMap<>();

    public int remaining(int playerId, ShopConfigRepository.ShopItemConfig item, Instant now) {
        if (item == null) {
            return 0;
        }
        Integer remain = null;
        if (item.dailyLimit() > 0) {
            int used = getCount(playerId, item.shopItemId(), periodKey("DAY", now));
            remain = Math.max(0, item.dailyLimit() - used);
        }
        int totalLimit = item.effectiveTotalLimit();
        if (totalLimit > 0) {
            int used = getCount(playerId, item.shopItemId(), periodKey(item.effectiveLimitPeriod(), now));
            int totalRemain = Math.max(0, totalLimit - used);
            remain = remain == null ? totalRemain : Math.min(remain, totalRemain);
        }
        if (remain == null) {
            return Integer.MAX_VALUE;
        }
        return remain;
    }

    public boolean canPurchase(int playerId, ShopConfigRepository.ShopItemConfig item, Instant now) {
        return remaining(playerId, item, now) > 0;
    }

    /** 购买成功后累加日限与总限计数。 */
    public void consume(int playerId, ShopConfigRepository.ShopItemConfig item, Instant now) {
        if (item == null) {
            return;
        }
        if (item.dailyLimit() > 0) {
            bump(playerId, item.shopItemId(), periodKey("DAY", now));
        }
        if (item.effectiveTotalLimit() > 0) {
            bump(playerId, item.shopItemId(), periodKey(item.effectiveLimitPeriod(), now));
        }
    }

    public int getCount(int playerId, int shopItemId, String periodKey) {
        AtomicInteger counter = counters.get(key(playerId, shopItemId, periodKey));
        return counter == null ? 0 : counter.get();
    }

    private void bump(int playerId, int shopItemId, String periodKey) {
        counters.computeIfAbsent(key(playerId, shopItemId, periodKey), k -> new AtomicInteger())
                .incrementAndGet();
    }

    static String key(int playerId, int shopItemId, String periodKey) {
        return playerId + ":" + shopItemId + ":" + periodKey;
    }

    public static String periodKey(String period, Instant now) {
        LocalDate date = now.atZone(ZONE).toLocalDate();
        String p = period == null ? "LIFETIME" : period.toUpperCase(Locale.ROOT);
        return switch (p) {
            case "DAY" -> "DAY:" + date;
            case "WEEK" -> {
                WeekFields wf = WeekFields.ISO;
                yield "WEEK:" + date.get(wf.weekBasedYear()) + "-W" + date.get(wf.weekOfWeekBasedYear());
            }
            case "MONTH" -> "MONTH:" + date.getYear() + "-" + date.getMonthValue();
            default -> "LIFETIME:ALL";
        };
    }
}
