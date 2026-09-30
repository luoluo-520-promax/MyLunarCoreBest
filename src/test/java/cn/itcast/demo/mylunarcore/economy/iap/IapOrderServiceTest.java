package cn.itcast.demo.mylunarcore.economy.iap;

import cn.itcast.demo.mylunarcore.economy.EconomyTestFixtures;
import cn.itcast.demo.mylunarcore.economy.ShopConfigRepository;
import cn.itcast.demo.mylunarcore.economy.WalletApplicationService;
import cn.itcast.demo.mylunarcore.player.PlayerAggregateService;
import cn.itcast.demo.mylunarcore.repo.ItemRepository;
import cn.itcast.demo.mylunarcore.security.BizReplayGuardService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * IAP 下单验单发货测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code IapOrderServiceTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("IAP 下单验单发货测试")
class IapOrderServiceTest {

    private static final int PLAYER_ID = EconomyTestFixtures.PLAYER_ID;
    private static final int SHOP_ID = 2;

    private IapProductCatalog catalog;
    private PurchaseLimitService purchaseLimitService;
    private IapVerifyGateway verifyGateway;
    private WalletApplicationService walletApplicationService;
    private ItemRepository itemRepository;
    private PlayerAggregateService playerAggregateService;
    private IapEntitlementService entitlementService;
    private IapGrantService grantService;
    private IapOrderService orderService;

    @BeforeEach
    void setUp() {
        catalog = mock(IapProductCatalog.class);
        purchaseLimitService = new PurchaseLimitService();
        MockIapChannelVerifier mockVerifier = new MockIapChannelVerifier();
        verifyGateway = new IapVerifyGateway(true, java.util.List.of(mockVerifier), mockVerifier);
        walletApplicationService = mock(WalletApplicationService.class);
        itemRepository = mock(ItemRepository.class);
        playerAggregateService = mock(PlayerAggregateService.class);
        entitlementService = new IapEntitlementService();
        when(playerAggregateService.commit(anyLong(), any(Function.class))).thenReturn(null);
        when(walletApplicationService.getBalance(PLAYER_ID)).thenReturn(Map.of(2, 0));
        when(walletApplicationService.add(anyInt(), anyInt(), anyInt(), anyString()))
                .thenAnswer(inv -> {
                    int currencyId = inv.getArgument(1);
                    int amount = inv.getArgument(2);
                    String reason = inv.getArgument(3);
                    return new WalletApplicationService.WalletChangeResult(
                            true, Map.of(currencyId, amount), reason);
                });
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<cn.itcast.demo.mylunarcore.common.BusinessMetrics> metricsProvider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(metricsProvider.getIfAvailable()).thenReturn(null);
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<cn.itcast.demo.mylunarcore.economy.TopUpBonusService> topUpProvider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(topUpProvider.getIfAvailable()).thenReturn(null);
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<cn.itcast.demo.mylunarcore.economy.MonthlyCardService> monthlyProvider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(monthlyProvider.getIfAvailable()).thenReturn(null);
        grantService = new IapGrantService(
                walletApplicationService, itemRepository, playerAggregateService,
                entitlementService, purchaseLimitService,
                mock(cn.itcast.demo.mylunarcore.skin.SkinConfigRepository.class),
                mock(cn.itcast.demo.mylunarcore.skin.SkinOwnershipService.class),
                metricsProvider, topUpProvider, monthlyProvider);
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<cn.itcast.demo.mylunarcore.security.BizReplayGuardService> replayProvider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(replayProvider.getIfAvailable()).thenReturn(null);
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<cn.itcast.demo.mylunarcore.security.RedisBizReplayGuard> redisReplayProvider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(redisReplayProvider.getIfAvailable()).thenReturn(null);
        orderService = new IapOrderService(
                catalog, purchaseLimitService, verifyGateway, grantService, walletApplicationService,
                replayProvider, redisReplayProvider);
    }

    /**
     * 验证点：直接氪金：Create→Confirm 应发货且重复 Confirm 幂等。
     * <p>测试方法 {@code directTopupShouldGrantIdempotently}：
     * <ul>
     *   <li>{@code when(catalog.find(SHOP_ID, 2001)).thenReturn(item);}</li>
     *   <li>{@code when(catalog.canCreateOrder(eq(item), any())).thenReturn(true);}</li>
     *   <li>{@code assertTrue(created.success());}</li>
     *   <li>{@code assertNotNull(created.order().orderId());}</li>
     *   <li>{@code assertTrue(confirmed.success());}</li>
     *   <li>{@code verify(walletApplicationService, times(2)).add(eq(PLAYER_ID), eq(2), eq(60), anyString());}</li>
     * </ul>
     */
    @Test
    @DisplayName("直接氪金：Create→Confirm 应发货且重复 Confirm 幂等")
    void directTopupShouldGrantIdempotently() {
        ShopConfigRepository.ShopItemConfig item =
                EconomyTestFixtures.iapTopup(2001, "com.mylunarcore.crystal.60", 600, 2, 60);
        when(catalog.find(SHOP_ID, 2001)).thenReturn(item);
        when(catalog.canCreateOrder(eq(item), any())).thenReturn(true);

        IapOrderService.CreateResult created = orderService.createOrder(PLAYER_ID, SHOP_ID, 2001);
        assertTrue(created.success());
        assertNotNull(created.order().orderId());

        IapOrderService.ConfirmResult confirmed = orderService.confirmOrder(
                PLAYER_ID, created.order().orderId(), "mock", "receipt-1");
        assertTrue(confirmed.success());
        verify(walletApplicationService, times(2)).add(eq(PLAYER_ID), eq(2), eq(60), anyString());

        IapOrderService.ConfirmResult again = orderService.confirmOrder(
                PLAYER_ID, created.order().orderId(), "mock", "receipt-1");
        assertTrue(again.success());
        verify(walletApplicationService, times(2)).add(eq(PLAYER_ID), eq(2), eq(60), anyString());
        assertTrue(entitlementService.isFirstTopupDone(PLAYER_ID));
    }

    /**
     * 验证点：打折礼包：窗外不可下单，限购用尽后 Create 失败。
     * <p>测试方法 {@code discountPackShouldEnforceWindowAndLimit}：
     * <ul>
     *   <li>{@code when(catalog.find(SHOP_ID, 2101)).thenReturn(item);}</li>
     *   <li>{@code when(catalog.canCreateOrder(eq(item), any())).thenReturn(false);}</li>
     *   <li>{@code assertFalse(expired.success());}</li>
     *   <li>{@code assertEquals(2, expired.retcode());}</li>
     *   <li>{@code when(catalog.find(SHOP_ID, 2102)).thenReturn(open);}</li>
     *   <li>{@code when(catalog.canCreateOrder(eq(open), any())).thenReturn(true);}</li>
     * </ul>
     */
    @Test
    @DisplayName("打折礼包：窗外不可下单，限购用尽后 Create 失败")
    void discountPackShouldEnforceWindowAndLimit() {
        ShopConfigRepository.ShopItemConfig item = EconomyTestFixtures.iapDiscount(
                2101, "com.mylunarcore.pack.starter.6", 600, 3000,
                "2020-01-01T00:00:00+08:00", "2020-01-02T00:00:00+08:00",
                1, 1);
        when(catalog.find(SHOP_ID, 2101)).thenReturn(item);
        when(catalog.canCreateOrder(eq(item), any())).thenReturn(false);

        IapOrderService.CreateResult expired = orderService.createOrder(PLAYER_ID, SHOP_ID, 2101);
        assertFalse(expired.success());
        assertEquals(2, expired.retcode());

        ShopConfigRepository.ShopItemConfig open = EconomyTestFixtures.iapDiscount(
                2102, "com.mylunarcore.pack.weekend.68", 6800, 12800,
                "2020-01-01T00:00:00+08:00", "2099-01-01T00:00:00+08:00",
                1, 1);
        when(catalog.find(SHOP_ID, 2102)).thenReturn(open);
        when(catalog.canCreateOrder(eq(open), any())).thenReturn(true);

        IapOrderService.CreateResult first = orderService.createOrder(PLAYER_ID, SHOP_ID, 2102);
        assertTrue(first.success());
        assertTrue(orderService.confirmOrder(
                PLAYER_ID, first.order().orderId(), "mock", "r2").success());

        IapOrderService.CreateResult second = orderService.createOrder(PLAYER_ID, SHOP_ID, 2102);
        assertFalse(second.success());
        assertEquals(3, second.retcode());
    }

    /**
     * 验证点：空收据验签失败。
     * <p>测试方法 {@code emptyReceiptShouldFail}：
     * <ul>
     *   <li>{@code when(catalog.find(SHOP_ID, 2001)).thenReturn(item);}</li>
     *   <li>{@code when(catalog.canCreateOrder(eq(item), any())).thenReturn(true);}</li>
     *   <li>{@code assertFalse(confirmed.success());}</li>
     *   <li>{@code assertEquals(5, confirmed.retcode());}</li>
     * </ul>
     */
    @Test
    @DisplayName("空收据验签失败")
    void emptyReceiptShouldFail() {
        ShopConfigRepository.ShopItemConfig item =
                EconomyTestFixtures.iapTopup(2001, "com.mylunarcore.crystal.60", 600, 2, 60);
        when(catalog.find(SHOP_ID, 2001)).thenReturn(item);
        when(catalog.canCreateOrder(eq(item), any())).thenReturn(true);

        IapOrderService.CreateResult created = orderService.createOrder(PLAYER_ID, SHOP_ID, 2001);
        IapOrderService.ConfirmResult confirmed = orderService.confirmOrder(
                PLAYER_ID, created.order().orderId(), "mock", "");
        assertFalse(confirmed.success());
        assertEquals(5, confirmed.retcode());
    }

    /**
     * 验证点：相同 channelTx 二次 Confirm 应被防重放拒绝；已支付待发货重试不受影响。
     * <p>测试方法 {@code channelTxReplayRejectedButGrantRetryAllowed}：
     * <ul>
     *   <li>{@code when(catalog.find(SHOP_ID, 2001)).thenReturn(item);}</li>
     *   <li>{@code when(catalog.canCreateOrder(eq(item), any())).thenReturn(true);}</li>
     *   <li>{@code when(jdbc.update(anyString(), any(), any(), any(), any()))}</li>
     *   <li>{@code when(provider.getIfAvailable()).thenReturn(guard);}</li>
     *   <li>{@code when(gateway.verify(anyString(), anyString(), anyString(), anyString()))}</li>
     *   <li>{@code when(redisProvider.getIfAvailable()).thenReturn(null);}</li>
     * </ul>
     */
    @Test
    @DisplayName("相同 channelTx 二次 Confirm 应被防重放拒绝；已支付待发货重试不受影响")
    void channelTxReplayRejectedButGrantRetryAllowed() {
        ShopConfigRepository.ShopItemConfig item =
                EconomyTestFixtures.iapTopup(2001, "com.mylunarcore.crystal.60", 600, 2, 60);
        when(catalog.find(SHOP_ID, 2001)).thenReturn(item);
        when(catalog.canCreateOrder(eq(item), any())).thenReturn(true);

        cn.itcast.demo.mylunarcore.common.BusinessMetrics metrics =
                new cn.itcast.demo.mylunarcore.common.BusinessMetrics(
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry());
        org.springframework.jdbc.core.JdbcTemplate jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        when(jdbc.update(anyString(), any(), any(), any(), any()))
                .thenReturn(1)
                .thenThrow(new org.springframework.dao.DuplicateKeyException("dup"));
        BizReplayGuardService guard = new BizReplayGuardService(jdbc, metrics);

        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<BizReplayGuardService> provider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(guard);

        IapVerifyGateway gateway = mock(IapVerifyGateway.class);
        when(gateway.verify(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(new IapVerifyGateway.VerifyResult(true, "shared-tx-1", "ok"));

        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<cn.itcast.demo.mylunarcore.security.RedisBizReplayGuard> redisProvider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(redisProvider.getIfAvailable()).thenReturn(null);
        IapOrderService svc = new IapOrderService(
                catalog, purchaseLimitService, gateway, grantService, walletApplicationService, provider, redisProvider);

        IapOrderService.CreateResult o1 = svc.createOrder(PLAYER_ID, SHOP_ID, 2001);
        assertTrue(svc.confirmOrder(PLAYER_ID, o1.order().orderId(), "mock", "r1").success());

        IapOrderService.CreateResult o2 = svc.createOrder(PLAYER_ID, SHOP_ID, 2001);
        IapOrderService.ConfirmResult replay = svc.confirmOrder(PLAYER_ID, o2.order().orderId(), "mock", "r2");
        assertFalse(replay.success());
        assertEquals(7, replay.retcode());

        // 模拟已支付待发货：手动 markPaid 后再 Confirm 应跳过防重放并允许补发
        IapOrderService.CreateResult o3 = svc.createOrder(PLAYER_ID, SHOP_ID, 2001);
        o3.order().markPaid("mock", "shared-tx-retry", Instant.now());
        o3.order().markGrantRetry();
        IapOrderService.ConfirmResult retry = svc.confirmOrder(PLAYER_ID, o3.order().orderId(), "mock", "r3");
        assertTrue(retry.success());
    }
}
