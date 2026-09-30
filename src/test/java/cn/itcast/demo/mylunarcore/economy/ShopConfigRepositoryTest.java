package cn.itcast.demo.mylunarcore.economy;

import cn.itcast.demo.mylunarcore.common.ConfigFileService;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ShopConfigRepository 商店配置仓储测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code ShopConfigRepositoryTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("ShopConfigRepository 商店配置仓储测试")
class ShopConfigRepositoryTest {

    private static final Logger log = LoggerFactory.getLogger(ShopConfigRepositoryTest.class);

    private ConfigFileService configFileService;
    private ShopConfigRepository repository;

    @BeforeEach
    void setUp() throws Exception {
        configFileService = mock(ConfigFileService.class);
        repository = new ShopConfigRepository(configFileService);

        ShopConfigRepository.ShopItemConfig itemA = EconomyTestFixtures.shopItem(1001, 101, 1, 1, 100, 10);
        ShopConfigRepository.ShopItemConfig itemB = EconomyTestFixtures.shopItem(1002, 23001, 1, 1, 500, 5);
        ShopConfigRepository.ShopConfig shop = EconomyTestFixtures.shop(1, itemA, itemB);
        stubShopConfigs(List.of(shop));
        repository.reload();
        log.info("商店配置加载: shopId=1, itemCount={}, firstShopItemId={}, firstPrice={}",
                shop.items().size(), itemA.shopItemId(), itemA.price());
    }

    @SuppressWarnings("unchecked")
    private void stubShopConfigs(List<ShopConfigRepository.ShopConfig> shops) throws Exception {
        when(configFileService.readJsonList(eq("ShopConfigs.json"), any(TypeReference.class)))
                .thenReturn(shops);
    }

    /**
     * 验证点：findShop 应按 shopId 返回商店配置。
     * <p>测试方法 {@code findShopShouldReturnConfig}：
     * <ul>
     *   <li>{@code assertNotNull(shop);}</li>
     *   <li>{@code assertEquals(1, shop.shopId());}</li>
     *   <li>{@code assertEquals(2, shop.items().size());}</li>
     *   <li>{@code assertNull(missing);}</li>
     * </ul>
     */
    @Test
    @DisplayName("findShop 应按 shopId 返回商店配置")
    void findShopShouldReturnConfig() {
        ShopConfigRepository.ShopConfig shop = repository.findShop(1);
        ShopConfigRepository.ShopConfig missing = repository.findShop(999);

        log.info("商店查询校验: shopId=1 found={}, itemCount={}, shopId=999 found={}",
                shop != null, shop != null ? shop.items().size() : 0, missing != null);
        assertNotNull(shop);
        assertEquals(1, shop.shopId());
        assertEquals(2, shop.items().size());
        assertNull(missing);
    }

    /**
     * 验证点：findItem 应按 shopId+shopItemId 定位商品。
     * <p>测试方法 {@code findItemShouldLocateShopItem}：
     * <ul>
     *   <li>{@code assertNotNull(item);}</li>
     *   <li>{@code assertEquals(100, item.price());}</li>
     *   <li>{@code assertEquals(101, item.itemId());}</li>
     *   <li>{@code assertEquals(1, item.currencyId());}</li>
     *   <li>{@code assertNull(missingItem);}</li>
     *   <li>{@code assertNull(missingShop);}</li>
     * </ul>
     */
    @Test
    @DisplayName("findItem 应按 shopId+shopItemId 定位商品")
    void findItemShouldLocateShopItem() {
        ShopConfigRepository.ShopItemConfig item = repository.findItem(1, 1001);
        ShopConfigRepository.ShopItemConfig missingItem = repository.findItem(1, 9999);
        ShopConfigRepository.ShopItemConfig missingShop = repository.findItem(999, 1001);

        log.info("商品查询校验: shopId=1 shopItemId=1001 price={}, itemId={}, shopItemId=9999 null={}, shopId=999 null={}",
                item != null ? item.price() : null,
                item != null ? item.itemId() : null,
                missingItem == null,
                missingShop == null);
        assertNotNull(item);
        assertEquals(100, item.price());
        assertEquals(101, item.itemId());
        assertEquals(1, item.currencyId());
        assertNull(missingItem);
        assertNull(missingShop);
    }

    /**
     * 验证点：reload 应整体替换商店索引。
     * <p>测试方法 {@code reloadShouldReplaceIndex}：
     * <ul>
     *   <li>{@code assertNull(oldShop);}</li>
     *   <li>{@code assertNotNull(loaded);}</li>
     *   <li>{@code assertEquals(50, item.price());}</li>
     *   <li>{@code assertEquals(2, item.currencyId());}</li>
     * </ul>
     */
    @Test
    @DisplayName("reload 应整体替换商店索引")
    void reloadShouldReplaceIndex() throws Exception {
        ShopConfigRepository.ShopItemConfig newItem = EconomyTestFixtures.shopItem(2001, 501, 2, 2, 50, 0);
        ShopConfigRepository.ShopConfig newShop = EconomyTestFixtures.shop(2, newItem);
        stubShopConfigs(List.of(newShop));

        repository.reload();
        ShopConfigRepository.ShopConfig oldShop = repository.findShop(1);
        ShopConfigRepository.ShopConfig loaded = repository.findShop(2);
        ShopConfigRepository.ShopItemConfig item = repository.findItem(2, 2001);

        log.info("热更替换校验: oldShopId=1 present={}, newShopId=2 present={}, shopItemId={}, price={}, currencyId={}",
                oldShop != null, loaded != null,
                item != null ? item.shopItemId() : null,
                item != null ? item.price() : null,
                item != null ? item.currencyId() : null);
        assertNull(oldShop);
        assertNotNull(loaded);
        assertEquals(50, item.price());
        assertEquals(2, item.currencyId());
    }
}
