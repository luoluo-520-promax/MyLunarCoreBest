package cn.itcast.demo.mylunarcore.economy;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final /**
 * EconomyTestFixtures。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code EconomyTestFixtures}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
class EconomyTestFixtures {

    public static final int PLAYER_ID = 1001;
    public static final long PLAYER_UID = 1001L;
    public static final int CURRENCY_ID = 1;
    public static final int SHOP_ID = 1;
    public static final int SHOP_ITEM_ID = 1001;

    private EconomyTestFixtures() {
    }

    public static Map<Integer, Integer> balance(int currencyId, int amount) {
        return new HashMap<>(Map.of(currencyId, amount));
    }

    public static ShopConfigRepository.ShopItemConfig shopItem(
            int shopItemId, int itemId, int itemCount, int currencyId, int price, int dailyLimit) {
        return ShopConfigRepository.ShopItemConfig.currencyItem(
                shopItemId, itemId, itemCount, currencyId, price, dailyLimit);
    }

    public static ShopConfigRepository.ShopConfig shop(int shopId, ShopConfigRepository.ShopItemConfig... items) {
        return new ShopConfigRepository.ShopConfig(shopId, List.of(items));
    }

    public static ShopConfigRepository.ShopItemConfig iapTopup(int shopItemId, String skuId, int priceCents,
                                                              int currencyId, int amount) {
        return new ShopConfigRepository.ShopItemConfig(
                shopItemId, 0, 0, 0, 0, 0,
                "DIRECT_TOPUP", "IAP", skuId, amount + "晶石",
                priceCents, 0, 0, "CNY", null, null, null,
                List.of(new ShopConfigRepository.RewardSpec("CURRENCY", currencyId, amount, null, null, null)),
                List.of(new ShopConfigRepository.RewardSpec("CURRENCY", currencyId, amount, null, null, null)),
                null, null, 10, Boolean.TRUE);
    }

    public static ShopConfigRepository.ShopItemConfig iapDiscount(int shopItemId, String skuId,
                                                                 int priceCents, int originalCents,
                                                                 String start, String end,
                                                                 int dailyLimit, int totalLimit) {
        return new ShopConfigRepository.ShopItemConfig(
                shopItemId, 0, 0, 0, 0, dailyLimit,
                "DISCOUNT_PACK", "IAP", skuId, "特惠礼包",
                priceCents, originalCents, 50, "CNY", start, end, null,
                List.of(
                        new ShopConfigRepository.RewardSpec("CURRENCY", 2, 60, null, null, null),
                        new ShopConfigRepository.RewardSpec("ITEM", null, null, 101, 5, null)
                ),
                List.of(), totalLimit, "LIFETIME", 20, Boolean.TRUE);
    }
}
