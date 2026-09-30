package cn.itcast.demo.mylunarcore.tx;

import cn.itcast.demo.mylunarcore.economy.WalletApplicationService;
import cn.itcast.demo.mylunarcore.repo.WalletRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分布式锁串行化钱包扣费，防止并发双扣。
 */
@DisplayName("钱包分布式锁业务流程")
class DistributedLockWalletFlowTest {

    @Test
    @DisplayName("同玩家并发扣费应串行进入仓库更新")
    void concurrentDeductSerializedByLock() throws Exception {
        WalletRepository repo = mock(WalletRepository.class);
        when(repo.lockCurrency(1001)).thenAnswer(inv ->
                new WalletRepository.CurrencyLockRow(Map.of(1, 1000), 1L));
        when(repo.updateCurrencyOptimistic(eq(1001), anyMap(), eq(1L))).thenReturn(true);
        when(repo.loadCurrency(1001)).thenReturn(Map.of(1, 1000));

        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<LocalTxLogService> txProvider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        when(txProvider.getIfAvailable()).thenReturn(null);
        WalletApplicationService wallet = new WalletApplicationService(repo, new LocalDistributedLockService(), txProvider);

        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();

        for (int i = 0; i < threads; i++) {
            pool.execute(() -> {
                try {
                    start.await();
                    if (wallet.deduct(1001, 1, 10, "stress").success()) {
                        success.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS));
        pool.shutdown();

        assertEquals(threads, success.get());
        verify(repo, times(threads)).updateCurrencyOptimistic(eq(1001), anyMap(), eq(1L));
        verify(repo, times(threads)).insertLedger(eq(1001), eq(1), eq(-10), anyString(), anyInt(), anyInt(), anyString());
    }

    @Test
    @DisplayName("锁获取超时应抛 LockAcquireException")
    void lockTimeoutThrows() throws Exception {
        LocalDistributedLockService locks = new LocalDistributedLockService();
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> locks.withLock("lunar:lock:wallet:9", 1, 5, TimeUnit.SECONDS, () -> {
            held.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        }));
        holder.start();
        assertTrue(held.await(2, TimeUnit.SECONDS));

        assertThrows(DistributedLockService.LockAcquireException.class,
                () -> locks.withLock("lunar:lock:wallet:9", 50, 1, TimeUnit.MILLISECONDS, () -> "x"));

        release.countDown();
        holder.join(2000);
    }
}
