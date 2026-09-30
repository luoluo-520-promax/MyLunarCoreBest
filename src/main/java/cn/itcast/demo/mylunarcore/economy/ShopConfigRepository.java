// 商店静态配置仓储：从 data/ShopConfigs.json 加载并构建 shopId 内存索引
package cn.itcast.demo.mylunarcore.economy;

import cn.itcast.demo.mylunarcore.common.ConfigFileService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 商店静态配置仓储：从 data/ShopConfigs.json 读取商品与价格配置。
 * 热更时调用 reload() 整体替换 shopsById，保证并发读一致性。
 * 兼容游戏币商品与 IAP 氪金商品（productCategory / payType / rewards 等扩展字段）。
 */
@Repository
public class ShopConfigRepository {

    private final ConfigFileService configFileService;

    private Map<Integer, ShopConfig> shopsById = Collections.emptyMap();

    public ShopConfigRepository(ConfigFileService configFileService) {
        this.configFileService = configFileService;
    }

    @PostConstruct
    public void load() {
        if (!reload()) {
            throw new IllegalStateException("ShopConfigs.json load failed");
        }
    }

    /**
     * 重新读取 ShopConfigs.json 并重建 shopId 索引。
     * 通过整体替换不可变 Map 保证并发读取时不会出现「读到一半旧一半新」的中间态。
     *
     * @return 成功 true；失败 false（保留旧索引）
     */
    public boolean reload() {
        try {
            List<ShopConfig> list = configFileService.readJsonList("ShopConfigs.json",
                    new TypeReference<>() {});
            Map<Integer, ShopConfig> map = new HashMap<>();
            for (ShopConfig shop : list) {
                map.put(shop.shopId(), shop);
            }
            shopsById = Collections.unmodifiableMap(map);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** @return 不存在时 null */
    public ShopConfig findShop(int shopId) {
        return shopsById.get(shopId);
    }

    public List<ShopConfig> listAll() {
        return List.copyOf(shopsById.values());
    }

    /** 热更回滚用：返回当前商店配置快照。 */
    public Map<Integer, ShopConfig> snapshot() {
        return shopsById;
    }

    public void restore(Map<Integer, ShopConfig> previous) {
        if (previous != null) {
            shopsById = previous;
        }
    }

    /** @return 商店不存在或商品 ID 不匹配时 null */
    public ShopItemConfig findItem(int shopId, int shopItemId) {
        ShopConfig shop = shopsById.get(shopId);
        if (shop == null) {
            return null;
        }
        return shop.items().stream()
                .filter(i -> i.shopItemId() == shopItemId)
                .findFirst()
                .orElse(null);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ShopConfig(
            int shopId,
            String shopName,
            List<String> tabs,
            List<ShopItemConfig> items
    ) {
        public ShopConfig {
            if (tabs == null) {
                tabs = List.of();
            }
            if (items == null) {
                items = List.of();
            }
        }

        /** 兼容旧构造：仅 shopId + items。 */
        public ShopConfig(int shopId, List<ShopItemConfig> items) {
            this(shopId, null, List.of(), items);
        }
    }

    /**
     * 发货条目：CURRENCY / ITEM / DAILY_CLAIM。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RewardSpec(
            String type,
            Integer currencyId,
            Integer amount,
            Integer itemId,
            Integer count,
            Integer days
    ) {
        public boolean isCurrency() {
            return "CURRENCY".equalsIgnoreCase(type)
                    && currencyId != null && amount != null && amount > 0;
        }

        public boolean isItem() {
            return "ITEM".equalsIgnoreCase(type)
                    && itemId != null && count != null && count > 0;
        }

        public boolean isDailyClaim() {
            return "DAILY_CLAIM".equalsIgnoreCase(type)
                    && currencyId != null && amount != null && amount > 0
                    && days != null && days > 0;
        }
    }

    /**
     * 单个商品配置：旧字段保留；新字段缺省时按游戏币商品处理。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ShopItemConfig(
            int shopItemId,
            int itemId,
            int itemCount,
            int currencyId,
            int price,
            int dailyLimit,
            String productCategory,
            String payType,
            String skuId,
            String displayName,
            int priceCents,
            int originalPriceCents,
            int discountRate,
            String currencyCode,
            String saleStartAt,
            String saleEndAt,
            String packKind,
            List<RewardSpec> rewards,
            List<RewardSpec> firstPurchaseBonus,
            Integer totalLimit,
            String limitPeriod,
            int sortOrder,
            Boolean enabled
    ) {
        public ShopItemConfig {
            if (productCategory == null || productCategory.isBlank()) {
                productCategory = "CURRENCY_SHOP";
            }
            if (payType == null || payType.isBlank()) {
                payType = "CURRENCY";
            }
            if (rewards == null) {
                rewards = List.of();
            }
            if (firstPurchaseBonus == null) {
                firstPurchaseBonus = List.of();
            }
            if (enabled == null) {
                enabled = Boolean.TRUE;
            }
        }

        /** 兼容旧游戏币商品 6 字段构造。 */
        public static ShopItemConfig currencyItem(
                int shopItemId, int itemId, int itemCount, int currencyId, int price, int dailyLimit) {
            return new ShopItemConfig(
                    shopItemId, itemId, itemCount, currencyId, price, dailyLimit,
                    "CURRENCY_SHOP", "CURRENCY", null, null,
                    0, 0, 0, null, null, null, null,
                    List.of(), List.of(), null, null, 0, Boolean.TRUE);
        }

        public boolean isIap() {
            return "IAP".equalsIgnoreCase(payType);
        }

        public boolean isCurrencyPay() {
            return !isIap();
        }

        /** SKIN / SKIN_SHOP / SKIN_PACK 皮肤相关品类。 */
        public boolean isSkinProduct() {
            if (productCategory == null || productCategory.isBlank()) {
                return false;
            }
            String c = productCategory.toUpperCase();
            return "SKIN".equals(c) || "SKIN_SHOP".equals(c) || "SKIN_PACK".equals(c);
        }

        public boolean isEnabled() {
            return enabled == null || enabled;
        }

        public boolean inSaleWindow(Instant now) {
            Instant start = parseInstant(saleStartAt);
            Instant end = parseInstant(saleEndAt);
            if (start != null && now.isBefore(start)) {
                return false;
            }
            if (end != null && now.isAfter(end)) {
                return false;
            }
            return true;
        }

        public long saleEndAtMs() {
            Instant end = parseInstant(saleEndAt);
            return end == null ? 0L : end.toEpochMilli();
        }

        public int effectiveTotalLimit() {
            return totalLimit == null ? 0 : totalLimit;
        }

        public String effectiveLimitPeriod() {
            if (limitPeriod != null && !limitPeriod.isBlank()) {
                return limitPeriod.toUpperCase();
            }
            return effectiveTotalLimit() > 0 ? "LIFETIME" : "DAY";
        }

        private static Instant parseInstant(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            try {
                return Instant.parse(raw);
            } catch (Exception ignored) {
                try {
                    return java.time.OffsetDateTime.parse(raw).toInstant();
                } catch (Exception e) {
                    return null;
                }
            }
        }
    }
}
