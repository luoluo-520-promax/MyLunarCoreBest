package cn.itcast.demo.mylunarcore.economy;

import cn.itcast.demo.mylunarcore.player.PlayerAggregateService;
import cn.itcast.demo.mylunarcore.repo.ItemRepository;
import cn.itcast.demo.mylunarcore.repo.WalletRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * WalletApplicationService 钱包服务测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code WalletApplicationServiceTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("WalletApplicationService 钱包服务测试")
class WalletApplicationServiceTest {

    private static final Logger log = LoggerFactory.getLogger(WalletApplicationServiceTest.class);

    private static final int PLAYER_ID = EconomyTestFixtures.PLAYER_ID;
    private static final int CURRENCY_ID = EconomyTestFixtures.CURRENCY_ID;

    private WalletRepository walletRepository;
    private WalletApplicationService service;

    @BeforeEach
    void setUp() {
        walletRepository = mock(WalletRepository.class);
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<cn.itcast.demo.mylunarcore.tx.LocalTxLogService> txProvider =
                org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
        org.mockito.Mockito.when(txProvider.getIfAvailable()).thenReturn(null);
        service = new WalletApplicationService(
                walletRepository, new cn.itcast.demo.mylunarcore.tx.LocalDistributedLockService(), txProvider);
        log.info("钱包服务初始化: playerId={}, currencyId={}", PLAYER_ID, CURRENCY_ID);
    }

    /**
     * 验证点：getBalance 应返回仓储余额快照。
     * <p>测试方法 {@code getBalanceShouldReturnRepositorySnapshot}：
     * <ul>
     *   <li>{@code when(walletRepository.loadCurrency(PLAYER_ID)).thenReturn(EconomyTestFixtures.balance(CURRENCY_ID, 500));}</li>
     *   <li>{@code assertEquals(500, balance.get(CURRENCY_ID));}</li>
     *   <li>{@code assertEquals(1, balance.size());}</li>
     * </ul>
     */
    @Test
    @DisplayName("getBalance 应返回仓储余额快照")
    void getBalanceShouldReturnRepositorySnapshot() {
        when(walletRepository.loadCurrency(PLAYER_ID)).thenReturn(EconomyTestFixtures.balance(CURRENCY_ID, 500));

        Map<Integer, Integer> balance = service.getBalance(PLAYER_ID);

        log.info("余额查询校验: playerId={}, currencyId={}, amount={}, mapSize={}",
                PLAYER_ID, CURRENCY_ID, balance.get(CURRENCY_ID), balance.size());
        assertEquals(500, balance.get(CURRENCY_ID));
        assertEquals(1, balance.size());
    }

    /**
     * 验证点：canAfford 余额充足或零金额应返回 true。
     * <p>测试方法 {@code canAffordShouldDetectPaymentFeasibility}：
     * <ul>
     *   <li>{@code when(walletRepository.loadCurrency(PLAYER_ID)).thenReturn(EconomyTestFixtures.balance(CURRENCY_ID, 200));}</li>
     *   <li>{@code assertTrue(enough);}</li>
     *   <li>{@code assertFalse(shortfall);}</li>
     *   <li>{@code assertTrue(free);}</li>
     * </ul>
     */
    @Test
    @DisplayName("canAfford 余额充足或零金额应返回 true")
    void canAffordShouldDetectPaymentFeasibility() {
        when(walletRepository.loadCurrency(PLAYER_ID)).thenReturn(EconomyTestFixtures.balance(CURRENCY_ID, 200));

        boolean enough = service.canAfford(PLAYER_ID, CURRENCY_ID, 150);
        boolean shortfall = service.canAfford(PLAYER_ID, CURRENCY_ID, 300);
        boolean free = service.canAfford(PLAYER_ID, CURRENCY_ID, 0);

        log.info("支付可行性校验: balance=200, amount=150 canAfford={}, amount=300 canAfford={}, amount=0 canAfford={}",
                enough, shortfall, free);
        assertTrue(enough);
        assertFalse(shortfall);
        assertTrue(free);
    }

    /**
     * 验证点：add 成功应累加余额并写库与流水。
     * <p>测试方法 {@code addShouldIncreaseBalance}：
     * <ul>
     *   <li>{@code when(walletRepository.lockCurrency(PLAYER_ID))}</li>
     *   <li>{@code when(walletRepository.updateCurrencyOptimistic(eq(PLAYER_ID), any(), eq(3L))).thenReturn(true);}</li>
     *   <li>{@code assertTrue(result.success());}</li>
     *   <li>{@code assertEquals(150, result.balance().get(CURRENCY_ID));}</li>
     *   <li>{@code assertEquals("quest:2001", result.reason());}</li>
     *   <li>{@code verify(walletRepository).updateCurrencyOptimistic(eq(PLAYER_ID), any(), eq(3L));}</li>
     * </ul>
     */
    @Test
    @DisplayName("add 成功应累加余额并写库与流水")
    void addShouldIncreaseBalance() {
        when(walletRepository.lockCurrency(PLAYER_ID))
                .thenReturn(new WalletRepository.CurrencyLockRow(
                        new HashMap<>(Map.of(CURRENCY_ID, 100)), 3L));
        when(walletRepository.updateCurrencyOptimistic(eq(PLAYER_ID), any(), eq(3L))).thenReturn(true);

        WalletApplicationService.WalletChangeResult result =
                service.add(PLAYER_ID, CURRENCY_ID, 50, "quest:2001");

        log.info("加款成功校验: playerId={}, currencyId={}, amount=50, success={}, balanceAfter={}, reason={}",
                PLAYER_ID, CURRENCY_ID, result.success(),
                result.balance().get(CURRENCY_ID), result.reason());
        assertTrue(result.success());
        assertEquals(150, result.balance().get(CURRENCY_ID));
        assertEquals("quest:2001", result.reason());
        verify(walletRepository).updateCurrencyOptimistic(eq(PLAYER_ID), any(), eq(3L));
        verify(walletRepository).insertLedger(eq(PLAYER_ID), eq(CURRENCY_ID), eq(50),
                eq("quest:2001"), eq(100), eq(150), anyString());
    }

    /**
     * 验证点：add 非法金额或溢出应失败且不写库。
     * <p>测试方法 {@code addShouldFailOnInvalidOrOverflow}：
     * <ul>
     *   <li>{@code when(walletRepository.lockCurrency(PLAYER_ID))}</li>
     *   <li>{@code assertFalse(zero.success());}</li>
     *   <li>{@code assertFalse(overflow.success());}</li>
     *   <li>{@code assertEquals(Integer.MAX_VALUE, overflow.balance().get(CURRENCY_ID));}</li>
     *   <li>{@code verify(walletRepository, never()).updateCurrencyOptimistic(anyInt(), any(), anyLong());}</li>
     * </ul>
     */
    @Test
    @DisplayName("add 非法金额或溢出应失败且不写库")
    void addShouldFailOnInvalidOrOverflow() {
        when(walletRepository.lockCurrency(PLAYER_ID))
                .thenReturn(new WalletRepository.CurrencyLockRow(
                        new HashMap<>(Map.of(CURRENCY_ID, Integer.MAX_VALUE)), 1L));

        WalletApplicationService.WalletChangeResult zero =
                service.add(PLAYER_ID, CURRENCY_ID, 0, "invalid");
        WalletApplicationService.WalletChangeResult overflow =
                service.add(PLAYER_ID, CURRENCY_ID, 1, "overflow");

        log.info("加款失败校验: amount=0 success={}, amount=1 onMaxInt success={}, balanceKept={}",
                zero.success(), overflow.success(), overflow.balance().get(CURRENCY_ID));
        assertFalse(zero.success());
        assertFalse(overflow.success());
        assertEquals(Integer.MAX_VALUE, overflow.balance().get(CURRENCY_ID));
        verify(walletRepository, never()).updateCurrencyOptimistic(anyInt(), any(), anyLong());
    }

    /**
     * 验证点：deduct 余额充足应扣款成功。
     * <p>测试方法 {@code deductShouldSucceedWhenBalanceEnough}：
     * <ul>
     *   <li>{@code when(walletRepository.lockCurrency(PLAYER_ID))}</li>
     *   <li>{@code when(walletRepository.updateCurrencyOptimistic(eq(PLAYER_ID), any(), eq(7L))).thenReturn(true);}</li>
     *   <li>{@code assertTrue(result.success());}</li>
     *   <li>{@code assertEquals(400, result.balance().get(CURRENCY_ID));}</li>
     *   <li>{@code assertEquals("shop:1001", result.reason());}</li>
     *   <li>{@code verify(walletRepository).insertLedger(eq(PLAYER_ID), eq(CURRENCY_ID), eq(-100),}</li>
     * </ul>
     */
    @Test
    @DisplayName("deduct 余额充足应扣款成功")
    void deductShouldSucceedWhenBalanceEnough() {
        when(walletRepository.lockCurrency(PLAYER_ID))
                .thenReturn(new WalletRepository.CurrencyLockRow(
                        new HashMap<>(Map.of(CURRENCY_ID, 500)), 7L));
        when(walletRepository.updateCurrencyOptimistic(eq(PLAYER_ID), any(), eq(7L))).thenReturn(true);

        WalletApplicationService.WalletChangeResult result =
                service.deduct(PLAYER_ID, CURRENCY_ID, 100, "shop:1001");

        log.info("扣款成功校验: playerId={}, currencyId={}, amount=100, success={}, balanceAfter={}, reason={}",
                PLAYER_ID, CURRENCY_ID, result.success(),
                result.balance().get(CURRENCY_ID), result.reason());
        assertTrue(result.success());
        assertEquals(400, result.balance().get(CURRENCY_ID));
        assertEquals("shop:1001", result.reason());
        verify(walletRepository).insertLedger(eq(PLAYER_ID), eq(CURRENCY_ID), eq(-100),
                eq("shop:1001"), eq(500), eq(400), anyString());
    }

    /**
     * 验证点：deduct 余额不足应失败；零扣款视为成功。
     * <p>测试方法 {@code deductShouldFailWhenInsufficientAndSkipZero}：
     * <ul>
     *   <li>{@code when(walletRepository.lockCurrency(PLAYER_ID))}</li>
     *   <li>{@code when(walletRepository.loadCurrency(PLAYER_ID)).thenReturn(EconomyTestFixtures.balance(CURRENCY_ID, 50));}</li>
     *   <li>{@code assertFalse(insufficient.success());}</li>
     *   <li>{@code assertEquals(50, insufficient.balance().get(CURRENCY_ID));}</li>
     *   <li>{@code assertTrue(zero.success());}</li>
     *   <li>{@code verify(walletRepository, never()).updateCurrencyOptimistic(anyInt(), any(), anyLong());}</li>
     * </ul>
     */
    @Test
    @DisplayName("deduct 余额不足应失败；零扣款视为成功")
    void deductShouldFailWhenInsufficientAndSkipZero() {
        when(walletRepository.lockCurrency(PLAYER_ID))
                .thenReturn(new WalletRepository.CurrencyLockRow(
                        new HashMap<>(EconomyTestFixtures.balance(CURRENCY_ID, 50)), 1L));
        when(walletRepository.loadCurrency(PLAYER_ID)).thenReturn(EconomyTestFixtures.balance(CURRENCY_ID, 50));

        WalletApplicationService.WalletChangeResult insufficient =
                service.deduct(PLAYER_ID, CURRENCY_ID, 100, "shop:1001");
        WalletApplicationService.WalletChangeResult zero =
                service.deduct(PLAYER_ID, CURRENCY_ID, 0, "free");

        log.info("扣款边界校验: balance=50, amount=100 success={}, amount=0 success={}, failBalance={}, zeroReason={}",
                insufficient.success(), zero.success(),
                insufficient.balance().get(CURRENCY_ID), zero.reason());
        assertFalse(insufficient.success());
        assertEquals(50, insufficient.balance().get(CURRENCY_ID));
        assertTrue(zero.success());
        verify(walletRepository, never()).updateCurrencyOptimistic(anyInt(), any(), anyLong());
    }
}
