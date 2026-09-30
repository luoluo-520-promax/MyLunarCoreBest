package cn.itcast.demo.mylunarcore.economy;

import cn.itcast.demo.mylunarcore.player.PlayerAggregateService;
import cn.itcast.demo.mylunarcore.repo.ItemRepository;
import cn.itcast.demo.mylunarcore.skin.SkinConfigRepository;
import cn.itcast.demo.mylunarcore.skin.SkinOwnershipService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ShopApplicationService 商店购买编排测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code ShopApplicationServiceTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("ShopApplicationService 商店购买编排测试")
class ShopApplicationServiceTest {

    private static final Logger log = LoggerFactory.getLogger(ShopApplicationServiceTest.class);

    private static final int PLAYER_ID = EconomyTestFixtures.PLAYER_ID;
    private static final int SHOP_ID = EconomyTestFixtures.SHOP_ID;
    private static final int SHOP_ITEM_ID = EconomyTestFixtures.SHOP_ITEM_ID;

    private ShopConfigRepository shopConfigRepository;
    private WalletApplicationService walletApplicationService;
    private ItemRepository itemRepository;
    private PlayerAggregateService playerAggregateService;
    private SkinConfigRepository skinConfigRepository;
    private SkinOwnershipService skinOwnershipService;
    private ShopApplicationService service;

    @BeforeEach
    void setUp() {
        shopConfigRepository = mock(ShopConfigRepository.class);
        walletApplicationService = mock(WalletApplicationService.class);
        itemRepository = mock(ItemRepository.class);
        playerAggregateService = mock(PlayerAggregateService.class);
        skinConfigRepository = mock(SkinConfigRepository.class);
        skinOwnershipService = mock(SkinOwnershipService.class);
        when(playerAggregateService.commit(anyLong(), any(Function.class))).thenReturn(null);
        when(skinConfigRepository.findByItemId(anyInt())).thenReturn(null);
        service = new ShopApplicationService(
                shopConfigRepository, walletApplicationService, itemRepository, playerAggregateService,
                skinConfigRepository, skinOwnershipService);
        log.info("商店购买服务初始化: playerId={}, shopId={}, shopItemId={}",
                PLAYER_ID, SHOP_ID, SHOP_ITEM_ID);
    }

    /**
     * 验证点：购买成功应扣款并发放道具。
     * <p>测试方法 {@code buyShouldDeductAndGrantItems}：
     * <ul>
     *   <li>{@code when(shopConfigRepository.findItem(SHOP_ID, SHOP_ITEM_ID)).thenReturn(item);}</li>
     *   <li>{@code when(walletApplicationService.deduct(PLAYER_ID, 1, 200, "shop:1001")).thenReturn(wallet);}</li>
     *   <li>{@code assertTrue(result.success());}</li>
     *   <li>{@code assertEquals(2, result.boughtCount());}</li>
     *   <li>{@code assertEquals(300, result.wallet().balance().get(1));}</li>
     *   <li>{@code verify(itemRepository).addSimpleItem(PLAYER_ID, 101, 3, 4L);}</li>
     * </ul>
     */
    @Test
    @DisplayName("购买成功应扣款并发放道具")
    void buyShouldDeductAndGrantItems() {
        ShopConfigRepository.ShopItemConfig item =
                EconomyTestFixtures.shopItem(SHOP_ITEM_ID, 101, 2, 1, 100, 10);
        when(shopConfigRepository.findItem(SHOP_ID, SHOP_ITEM_ID)).thenReturn(item);
        WalletApplicationService.WalletChangeResult wallet =
                new WalletApplicationService.WalletChangeResult(true, Map.of(1, 300), "shop:1001");
        when(walletApplicationService.deduct(PLAYER_ID, 1, 200, "shop:1001")).thenReturn(wallet);

        ShopApplicationService.BuyResult result = service.buy(PLAYER_ID, SHOP_ID, SHOP_ITEM_ID, 2);

        log.info("购买成功校验: playerId={}, shopId={}, shopItemId={}, count=2, success={}, boughtCount={}, remaining={}, totalPrice=200, totalItems=4",
                PLAYER_ID, SHOP_ID, SHOP_ITEM_ID, result.success(), result.boughtCount(),
                result.wallet().balance().get(1));
        assertTrue(result.success());
        assertEquals(2, result.boughtCount());
        assertEquals(300, result.wallet().balance().get(1));
        verify(itemRepository).addSimpleItem(PLAYER_ID, 101, 3, 4L);
    }

    /**
     * 验证点：非法 count 应修正为 1。
     * <p>测试方法 {@code buyShouldNormalizeNonPositiveCount}：
     * <ul>
     *   <li>{@code when(shopConfigRepository.findItem(SHOP_ID, SHOP_ITEM_ID)).thenReturn(item);}</li>
     *   <li>{@code when(walletApplicationService.deduct(PLAYER_ID, 1, 100, "shop:1001"))}</li>
     *   <li>{@code assertTrue(result.success());}</li>
     *   <li>{@code assertEquals(1, result.boughtCount());}</li>
     *   <li>{@code verify(walletApplicationService).deduct(PLAYER_ID, 1, 100, "shop:1001");}</li>
     *   <li>{@code verify(itemRepository).addSimpleItem(PLAYER_ID, 101, 3, 1L);}</li>
     * </ul>
     */
    @Test
    @DisplayName("非法 count 应修正为 1")
    void buyShouldNormalizeNonPositiveCount() {
        ShopConfigRepository.ShopItemConfig item =
                EconomyTestFixtures.shopItem(SHOP_ITEM_ID, 101, 1, 1, 100, 10);
        when(shopConfigRepository.findItem(SHOP_ID, SHOP_ITEM_ID)).thenReturn(item);
        when(walletApplicationService.deduct(PLAYER_ID, 1, 100, "shop:1001"))
                .thenReturn(new WalletApplicationService.WalletChangeResult(true, Map.of(1, 400), "shop:1001"));

        ShopApplicationService.BuyResult result = service.buy(PLAYER_ID, SHOP_ID, SHOP_ITEM_ID, 0);

        log.info("份数修正校验: rawCount=0, boughtCount={}, success={}, deductedPrice=100",
                result.boughtCount(), result.success());
        assertTrue(result.success());
        assertEquals(1, result.boughtCount());
        verify(walletApplicationService).deduct(PLAYER_ID, 1, 100, "shop:1001");
        verify(itemRepository).addSimpleItem(PLAYER_ID, 101, 3, 1L);
    }

    /**
     * 验证点：商品不存在应购买失败。
     * <p>测试方法 {@code buyShouldFailWhenItemMissing}：
     * <ul>
     *   <li>{@code when(shopConfigRepository.findItem(SHOP_ID, SHOP_ITEM_ID)).thenReturn(null);}</li>
     *   <li>{@code assertFalse(result.success());}</li>
     *   <li>{@code assertEquals(0, result.boughtCount());}</li>
     *   <li>{@code assertNull(result.wallet());}</li>
     *   <li>{@code verify(walletApplicationService, never()).deduct(anyInt(), anyInt(), anyInt(), anyString());}</li>
     *   <li>{@code verify(itemRepository, never()).addSimpleItem(anyInt(), anyInt(), anyInt(), anyLong());}</li>
     * </ul>
     */
    @Test
    @DisplayName("商品不存在应购买失败")
    void buyShouldFailWhenItemMissing() {
        when(shopConfigRepository.findItem(SHOP_ID, SHOP_ITEM_ID)).thenReturn(null);

        ShopApplicationService.BuyResult result = service.buy(PLAYER_ID, SHOP_ID, SHOP_ITEM_ID, 1);

        log.info("商品缺失校验: shopId={}, shopItemId={}, success={}, boughtCount={}, walletNull={}",
                SHOP_ID, SHOP_ITEM_ID, result.success(), result.boughtCount(), result.wallet() == null);
        assertFalse(result.success());
        assertEquals(0, result.boughtCount());
        assertNull(result.wallet());
        verify(walletApplicationService, never()).deduct(anyInt(), anyInt(), anyInt(), anyString());
        verify(itemRepository, never()).addSimpleItem(anyInt(), anyInt(), anyInt(), anyLong());
    }

    /**
     * 验证点：扣款失败应不发货。
     * <p>测试方法 {@code buyShouldNotGrantWhenDeductFails}：
     * <ul>
     *   <li>{@code when(shopConfigRepository.findItem(SHOP_ID, SHOP_ITEM_ID)).thenReturn(item);}</li>
     *   <li>{@code when(walletApplicationService.deduct(eq(PLAYER_ID), eq(1), eq(100), eq("shop:1001")))}</li>
     *   <li>{@code assertFalse(result.success());}</li>
     *   <li>{@code assertEquals(0, result.boughtCount());}</li>
     *   <li>{@code assertFalse(result.wallet().success());}</li>
     *   <li>{@code verify(itemRepository, never()).addSimpleItem(anyInt(), anyInt(), anyInt(), anyLong());}</li>
     * </ul>
     */
    @Test
    @DisplayName("扣款失败应不发货")
    void buyShouldNotGrantWhenDeductFails() {
        ShopConfigRepository.ShopItemConfig item =
                EconomyTestFixtures.shopItem(SHOP_ITEM_ID, 101, 1, 1, 100, 10);
        when(shopConfigRepository.findItem(SHOP_ID, SHOP_ITEM_ID)).thenReturn(item);
        when(walletApplicationService.deduct(eq(PLAYER_ID), eq(1), eq(100), eq("shop:1001")))
                .thenReturn(new WalletApplicationService.WalletChangeResult(false, Map.of(1, 50), "shop:1001"));

        ShopApplicationService.BuyResult result = service.buy(PLAYER_ID, SHOP_ID, SHOP_ITEM_ID, 1);

        log.info("扣款失败校验: success={}, boughtCount={}, walletSuccess={}, remaining={}, itemGranted={}",
                result.success(), result.boughtCount(),
                result.wallet().success(), result.wallet().balance().get(1), false);
        assertFalse(result.success());
        assertEquals(0, result.boughtCount());
        assertFalse(result.wallet().success());
        verify(itemRepository, never()).addSimpleItem(anyInt(), anyInt(), anyInt(), anyLong());
    }

    /**
     * 验证点：IAP 商品禁止走 BuyShopItem。
     * <p>测试方法 {@code buyShouldRejectIapItems}：
     * <ul>
     *   <li>{@code when(shopConfigRepository.findItem(2, 2001)).thenReturn(item);}</li>
     *   <li>{@code assertFalse(result.success());}</li>
     *   <li>{@code verify(walletApplicationService, never()).deduct(anyInt(), anyInt(), anyInt(), anyString());}</li>
     *   <li>{@code verify(itemRepository, never()).addSimpleItem(anyInt(), anyInt(), anyInt(), anyLong());}</li>
     * </ul>
     */
    @Test
    @DisplayName("IAP 商品禁止走 BuyShopItem")
    void buyShouldRejectIapItems() {
        ShopConfigRepository.ShopItemConfig item =
                EconomyTestFixtures.iapTopup(2001, "com.mylunarcore.crystal.60", 600, 2, 60);
        when(shopConfigRepository.findItem(2, 2001)).thenReturn(item);

        ShopApplicationService.BuyResult result = service.buy(PLAYER_ID, 2, 2001, 1);

        assertFalse(result.success());
        verify(walletApplicationService, never()).deduct(anyInt(), anyInt(), anyInt(), anyString());
        verify(itemRepository, never()).addSimpleItem(anyInt(), anyInt(), anyInt(), anyLong());
    }

    /**
     * 验证点：已拥有皮肤应拒绝重复购买。
     * <p>测试方法 {@code buyShouldRejectAlreadyOwnedSkin}：
     * <ul>
     *   <li>{@code when(shopConfigRepository.findItem(3, 3001)).thenReturn(item);}</li>
     *   <li>{@code when(skinConfigRepository.findByItemId(8101002)).thenReturn(skin);}</li>
     *   <li>{@code when(skinOwnershipService.owns(PLAYER_ID, 8001002)).thenReturn(true);}</li>
     *   <li>{@code assertFalse(result.success());}</li>
     *   <li>{@code verify(walletApplicationService, never()).deduct(anyInt(), anyInt(), anyInt(), anyString());}</li>
     *   <li>{@code verify(itemRepository, never()).addSimpleItem(anyInt(), anyInt(), anyInt(), anyLong());}</li>
     * </ul>
     */
    @Test
    @DisplayName("已拥有皮肤应拒绝重复购买")
    void buyShouldRejectAlreadyOwnedSkin() {
        ShopConfigRepository.ShopItemConfig item = new ShopConfigRepository.ShopItemConfig(
                3001, 8101002, 1, 2, 1680, 0,
                "SKIN_SHOP", "CURRENCY", null, "星海礼赞",
                0, 0, 0, null, null, null, null,
                List.of(), List.of(), 1, "LIFETIME", 10, Boolean.TRUE);
        SkinConfigRepository.SkinConfig skin = new SkinConfigRepository.SkinConfig(
                8001002, 1001, 8101002, "星海礼赞", 5, false,
                "avatar/1001/skin_star_ocean", "ui/skin/8001002",
                List.of("LIMITED"), "皮肤商店", true);
        when(shopConfigRepository.findItem(3, 3001)).thenReturn(item);
        when(skinConfigRepository.findByItemId(8101002)).thenReturn(skin);
        when(skinOwnershipService.owns(PLAYER_ID, 8001002)).thenReturn(true);

        ShopApplicationService.BuyResult result = service.buy(PLAYER_ID, 3, 3001, 1);

        assertFalse(result.success());
        verify(walletApplicationService, never()).deduct(anyInt(), anyInt(), anyInt(), anyString());
        verify(itemRepository, never()).addSimpleItem(anyInt(), anyInt(), anyInt(), anyLong());
    }
}
