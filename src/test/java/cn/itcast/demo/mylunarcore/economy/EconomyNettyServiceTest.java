package cn.itcast.demo.mylunarcore.economy;

import cn.itcast.demo.mylunarcore.economy.iap.IapEntitlementService;
import cn.itcast.demo.mylunarcore.economy.iap.IapOrderService;
import cn.itcast.demo.mylunarcore.economy.iap.PurchaseLimitService;
import cn.itcast.demo.mylunarcore.net.CmdIds;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.player.PlayerContextResolver;
import cn.itcast.demo.mylunarcore.protocol.EconomySystemProto;
import cn.itcast.demo.mylunarcore.skin.SkinConfigRepository;
import cn.itcast.demo.mylunarcore.skin.SkinOwnershipService;
import io.netty.channel.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * EconomyNettyService 经济协议服务测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code EconomyNettyServiceTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("EconomyNettyService 经济协议服务测试")
class EconomyNettyServiceTest {

    private static final Logger log = LoggerFactory.getLogger(EconomyNettyServiceTest.class);

    private static final int PLAYER_ID = EconomyTestFixtures.PLAYER_ID;
    private static final long PLAYER_UID = EconomyTestFixtures.PLAYER_UID;
    private static final int SHOP_ID = EconomyTestFixtures.SHOP_ID;
    private static final int SHOP_ITEM_ID = EconomyTestFixtures.SHOP_ITEM_ID;

    private ShopApplicationService shopApplicationService;
    private ShopConfigRepository shopConfigRepository;
    private PlayerContextResolver contextResolver;
    private IapOrderService iapOrderService;
    private PurchaseLimitService purchaseLimitService;
    private IapEntitlementService entitlementService;
    private WalletApplicationService walletApplicationService;
    private SkinConfigRepository skinConfigRepository;
    private SkinOwnershipService skinOwnershipService;
    private EconomyNettyService service;

    @BeforeEach
    void setUp() {
        shopApplicationService = mock(ShopApplicationService.class);
        shopConfigRepository = mock(ShopConfigRepository.class);
        contextResolver = mock(PlayerContextResolver.class);
        iapOrderService = mock(IapOrderService.class);
        purchaseLimitService = mock(PurchaseLimitService.class);
        entitlementService = mock(IapEntitlementService.class);
        walletApplicationService = mock(WalletApplicationService.class);
        skinConfigRepository = mock(SkinConfigRepository.class);
        skinOwnershipService = mock(SkinOwnershipService.class);
        when(purchaseLimitService.remaining(anyInt(), any(), any())).thenReturn(Integer.MAX_VALUE);
        when(skinConfigRepository.findByItemId(anyInt())).thenReturn(null);
        service = new EconomyNettyService(
                shopApplicationService, shopConfigRepository, contextResolver,
                iapOrderService, purchaseLimitService, entitlementService, walletApplicationService,
                skinConfigRepository, skinOwnershipService);
        log.info("经济协议服务初始化: playerId={}, uid={}, shopId={}", PLAYER_ID, PLAYER_UID, SHOP_ID);
    }

    /**
     * 验证点：GetShopList 成功应返回商品列表。
     * <p>测试方法 {@code handleGetShopListShouldReturnItems}：
     * <ul>
     *   <li>{@code when(shopConfigRepository.findShop(SHOP_ID)).thenReturn(shop);}</li>
     *   <li>{@code assertEquals(0, rsp.getRetcode());}</li>
     *   <li>{@code assertEquals(SHOP_ID, rsp.getShopId());}</li>
     *   <li>{@code assertEquals(2, rsp.getItemsCount());}</li>
     *   <li>{@code assertEquals("CURRENCY", rsp.getItems(0).getPayType());}</li>
     * </ul>
     */
    @Test
    @DisplayName("GetShopList 成功应返回商品列表")
    void handleGetShopListShouldReturnItems() {
        ShopConfigRepository.ShopConfig shop = EconomyTestFixtures.shop(
                SHOP_ID,
                EconomyTestFixtures.shopItem(1001, 101, 1, 1, 100, 10),
                EconomyTestFixtures.shopItem(1002, 23001, 1, 1, 500, 5));
        when(shopConfigRepository.findShop(SHOP_ID)).thenReturn(shop);

        EconomySystemProto.GetShopListScRsp rsp = service.handleGetShopList(
                EconomySystemProto.GetShopListCsReq.newBuilder().setShopId(SHOP_ID).build(),
                loggedInChannel());

        assertEquals(0, rsp.getRetcode());
        assertEquals(SHOP_ID, rsp.getShopId());
        assertEquals(2, rsp.getItemsCount());
        assertEquals("CURRENCY", rsp.getItems(0).getPayType());
    }

    /**
     * 验证点：皮肤商品列表应附加 skinId/avatarId/owned。
     * <p>测试方法 {@code handleGetShopListShouldAttachSkinMeta}：
     * <ul>
     *   <li>{@code when(shopConfigRepository.findShop(3)).thenReturn(new ShopConfigRepository.ShopConfig(}</li>
     *   <li>{@code when(skinConfigRepository.findByItemId(8101002)).thenReturn(skin);}</li>
     *   <li>{@code when(skinOwnershipService.owns(PLAYER_ID, 8001002)).thenReturn(true);}</li>
     *   <li>{@code assertEquals(0, rsp.getRetcode());}</li>
     *   <li>{@code assertEquals(1, rsp.getItemsCount());}</li>
     *   <li>{@code assertEquals(8001002, rsp.getItems(0).getSkinId());}</li>
     * </ul>
     */
    @Test
    @DisplayName("皮肤商品列表应附加 skinId/avatarId/owned")
    void handleGetShopListShouldAttachSkinMeta() {
        ShopConfigRepository.ShopItemConfig item = new ShopConfigRepository.ShopItemConfig(
                3001, 8101002, 1, 2, 1680, 0,
                "SKIN_SHOP", "CURRENCY", null, "星海礼赞",
                0, 0, 0, null, null, null, null,
                java.util.List.of(), java.util.List.of(), 1, "LIFETIME", 10, Boolean.TRUE);
        when(shopConfigRepository.findShop(3)).thenReturn(new ShopConfigRepository.ShopConfig(
                3, "皮肤商店", java.util.List.of("SKIN"), java.util.List.of(item)));
        SkinConfigRepository.SkinConfig skin = new SkinConfigRepository.SkinConfig(
                8001002, 1001, 8101002, "星海礼赞", 5, false,
                "k", "i", java.util.List.of(), "", true);
        when(skinConfigRepository.findByItemId(8101002)).thenReturn(skin);
        when(skinOwnershipService.owns(PLAYER_ID, 8001002)).thenReturn(true);

        EconomySystemProto.GetShopListScRsp rsp = service.handleGetShopList(
                EconomySystemProto.GetShopListCsReq.newBuilder().setShopId(3).build(),
                loggedInChannel());

        assertEquals(0, rsp.getRetcode());
        assertEquals(1, rsp.getItemsCount());
        assertEquals(8001002, rsp.getItems(0).getSkinId());
        assertEquals(1001, rsp.getItems(0).getAvatarId());
        assertTrue(rsp.getItems(0).getOwned());
    }

    /**
     * 验证点：商店不存在应返回 retcode=2。
     * <p>测试方法 {@code handleGetShopListMissingShopShouldFail}：
     * <ul>
     *   <li>{@code when(shopConfigRepository.findShop(SHOP_ID)).thenReturn(null);}</li>
     *   <li>{@code assertEquals(2, rsp.getRetcode());}</li>
     * </ul>
     */
    @Test
    @DisplayName("商店不存在应返回 retcode=2")
    void handleGetShopListMissingShopShouldFail() {
        when(shopConfigRepository.findShop(SHOP_ID)).thenReturn(null);

        EconomySystemProto.GetShopListScRsp rsp = service.handleGetShopList(
                EconomySystemProto.GetShopListCsReq.newBuilder().setShopId(SHOP_ID).build(),
                loggedInChannel());

        assertEquals(2, rsp.getRetcode());
    }

    /**
     * 验证点：购买成功应回传份数与余额并推送货币通知。
     * <p>测试方法 {@code handleBuyShopItemShouldSucceed}：
     * <ul>
     *   <li>{@code when(shopApplicationService.buy(PLAYER_ID, SHOP_ID, SHOP_ITEM_ID, 2))}</li>
     *   <li>{@code assertEquals(0, rsp.getRetcode());}</li>
     *   <li>{@code assertEquals(SHOP_ITEM_ID, rsp.getShopItemId());}</li>
     *   <li>{@code assertEquals(2, rsp.getBoughtCount());}</li>
     *   <li>{@code assertEquals(1, rsp.getRemainingCurrencyCount());}</li>
     *   <li>{@code assertEquals(300, rsp.getRemainingCurrency(0).getAmount());}</li>
     * </ul>
     */
    @Test
    @DisplayName("购买成功应回传份数与余额并推送货币通知")
    void handleBuyShopItemShouldSucceed() {
        WalletApplicationService.WalletChangeResult wallet =
                new WalletApplicationService.WalletChangeResult(true, Map.of(1, 300), "shop:1001");
        when(shopApplicationService.buy(PLAYER_ID, SHOP_ID, SHOP_ITEM_ID, 2))
                .thenReturn(new ShopApplicationService.BuyResult(true, 2, wallet));

        Channel channel = loggedInChannel();
        EconomySystemProto.BuyShopItemScRsp rsp = service.handleBuyShopItem(
                EconomySystemProto.BuyShopItemCsReq.newBuilder()
                        .setShopId(SHOP_ID)
                        .setShopItemId(SHOP_ITEM_ID)
                        .setCount(2)
                        .build(),
                channel);

        assertEquals(0, rsp.getRetcode());
        assertEquals(SHOP_ITEM_ID, rsp.getShopItemId());
        assertEquals(2, rsp.getBoughtCount());
        assertEquals(1, rsp.getRemainingCurrencyCount());
        assertEquals(300, rsp.getRemainingCurrency(0).getAmount());
        verify(channel).writeAndFlush(any(GamePacket.class));
    }

    /**
     * 验证点：未登录购买应返回 retcode=1。
     * <p>测试方法 {@code handleBuyShopItemWithoutLoginShouldFail}：
     * <ul>
     *   <li>{@code assertEquals(1, rsp.getRetcode());}</li>
     *   <li>{@code verify(shopApplicationService, never()).buy(anyInt(), anyInt(), anyInt(), anyInt());}</li>
     * </ul>
     */
    @Test
    @DisplayName("未登录购买应返回 retcode=1")
    void handleBuyShopItemWithoutLoginShouldFail() {
        EconomySystemProto.BuyShopItemScRsp rsp = service.handleBuyShopItem(
                EconomySystemProto.BuyShopItemCsReq.newBuilder()
                        .setShopId(SHOP_ID)
                        .setShopItemId(SHOP_ITEM_ID)
                        .setCount(1)
                        .build(),
                loggedOutChannel());

        assertEquals(1, rsp.getRetcode());
        verify(shopApplicationService, never()).buy(anyInt(), anyInt(), anyInt(), anyInt());
    }

    /**
     * 验证点：购买失败应返回 retcode=2。
     * <p>测试方法 {@code handleBuyShopItemShouldFail}：
     * <ul>
     *   <li>{@code when(shopApplicationService.buy(PLAYER_ID, SHOP_ID, SHOP_ITEM_ID, 1))}</li>
     *   <li>{@code assertEquals(2, rsp.getRetcode());}</li>
     * </ul>
     */
    @Test
    @DisplayName("购买失败应返回 retcode=2")
    void handleBuyShopItemShouldFail() {
        when(shopApplicationService.buy(PLAYER_ID, SHOP_ID, SHOP_ITEM_ID, 1))
                .thenReturn(new ShopApplicationService.BuyResult(false, 0, null));

        EconomySystemProto.BuyShopItemScRsp rsp = service.handleBuyShopItem(
                EconomySystemProto.BuyShopItemCsReq.newBuilder()
                        .setShopId(SHOP_ID)
                        .setShopItemId(SHOP_ITEM_ID)
                        .setCount(1)
                        .build(),
                loggedInChannel());

        assertEquals(2, rsp.getRetcode());
    }

    /**
     * 验证点：notifyCurrencyAndData 应推送 CURRENCY_CHANGE_SC_NOTIFY。
     * <p>测试方法 {@code notifyCurrencyAndDataShouldPushPacket}：
     * <ul>
     *   <li>{@code verify(channel).writeAndFlush(packetCaptor.capture());}</li>
     *   <li>{@code assertEquals(CmdIds.CURRENCY_CHANGE_SC_NOTIFY, packet.getCmdId());}</li>
     *   <li>{@code assertTrue(packet.getPayload().length > 0);}</li>
     * </ul>
     */
    @Test
    @DisplayName("notifyCurrencyAndData 应推送 CURRENCY_CHANGE_SC_NOTIFY")
    void notifyCurrencyAndDataShouldPushPacket() {
        Channel channel = loggedInChannel();
        Map<Integer, Integer> balance = Map.of(1, 888);

        service.notifyCurrencyAndData(channel, PLAYER_ID, balance, "quest:2001");

        ArgumentCaptor<GamePacket> packetCaptor = ArgumentCaptor.forClass(GamePacket.class);
        verify(channel).writeAndFlush(packetCaptor.capture());
        GamePacket packet = packetCaptor.getValue();
        assertEquals(CmdIds.CURRENCY_CHANGE_SC_NOTIFY, packet.getCmdId());
        assertTrue(packet.getPayload().length > 0);
    }

    private Channel loggedInChannel() {
        Channel channel = mock(Channel.class);
        when(contextResolver.resolvePlayerId(channel)).thenReturn(PLAYER_ID);
        when(contextResolver.resolveUid(channel)).thenReturn(OptionalLong.of(PLAYER_UID));
        when(channel.writeAndFlush(any())).thenReturn(null);
        return channel;
    }

    private Channel loggedOutChannel() {
        Channel channel = mock(Channel.class);
        when(contextResolver.resolvePlayerId(channel)).thenReturn(0);
        when(contextResolver.resolveUid(channel)).thenReturn(OptionalLong.empty());
        return channel;
    }
}
