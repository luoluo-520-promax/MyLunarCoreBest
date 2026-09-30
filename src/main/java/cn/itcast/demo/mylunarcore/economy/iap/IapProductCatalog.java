package cn.itcast.demo.mylunarcore.economy.iap;

import cn.itcast.demo.mylunarcore.economy.ShopConfigRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * IAP 商品目录：从扩展后的 ShopConfigs 筛选可售真钱商品。
 */
@Component
public class IapProductCatalog {

    private final ShopConfigRepository shopConfigRepository;

    public IapProductCatalog(ShopConfigRepository shopConfigRepository) {
        this.shopConfigRepository = shopConfigRepository;
    }

    public ShopConfigRepository.ShopItemConfig find(int shopId, int shopItemId) {
        ShopConfigRepository.ShopItemConfig item = shopConfigRepository.findItem(shopId, shopItemId);
        if (item == null || !item.isIap()) {
            return null;
        }
        return item;
    }

    public List<ShopConfigRepository.ShopItemConfig> listSellable(int shopId) {
        ShopConfigRepository.ShopConfig shop = shopConfigRepository.findShop(shopId);
        if (shop == null) {
            return List.of();
        }
        List<ShopConfigRepository.ShopItemConfig> result = new ArrayList<>();
        for (ShopConfigRepository.ShopItemConfig item : shop.items()) {
            if (item == null || !item.isEnabled()) {
                continue;
            }
            result.add(item);
        }
        result.sort(Comparator.comparingInt(ShopConfigRepository.ShopItemConfig::sortOrder)
                .thenComparingInt(ShopConfigRepository.ShopItemConfig::shopItemId));
        return result;
    }

    public boolean canCreateOrder(ShopConfigRepository.ShopItemConfig item, Instant now) {
        if (item == null || !item.isEnabled() || !item.isIap()) {
            return false;
        }
        if (item.skuId() == null || item.skuId().isBlank()) {
            return false;
        }
        return item.inSaleWindow(now);
    }
}
