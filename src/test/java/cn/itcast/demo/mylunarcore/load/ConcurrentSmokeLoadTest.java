package cn.itcast.demo.mylunarcore.load;

import cn.itcast.demo.mylunarcore.center.InMemoryMultiNodeRoutingClient;
import cn.itcast.demo.mylunarcore.center.RemoteCenterServer;
import cn.itcast.demo.mylunarcore.gacha.GachaBannerConfig;
import cn.itcast.demo.mylunarcore.gacha.GachaBannerType;
import cn.itcast.demo.mylunarcore.gacha.GachaDrawEngine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 轻量端到端压测冒烟：并发抽卡引擎 + 并发中心路由，不依赖启动完整 Spring 容器。
 */
@DisplayName("E2E 压测冒烟")
class ConcurrentSmokeLoadTest {

    @Test
    @DisplayName("并发 200 次抽卡硬保底应全部成功返回")
    void concurrentDrawsShouldComplete() throws Exception {
        GachaDrawEngine engine = new GachaDrawEngine();
        GachaBannerConfig banner = new GachaBannerConfig();
        banner.setRateUpItems5(List.of(10001));
        banner.setRateUpItems4(List.of(20001));

        int tasks = 200;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch done = new CountDownLatch(tasks);
        AtomicInteger ok = new AtomicInteger();
        try {
            for (int i = 0; i < tasks; i++) {
                pool.execute(() -> {
                    try {
                        var r = engine.doOneDraw(GachaBannerType.NORMAL, banner, null, 89, 0, 0);
                        if (r.itemId() > 0 && r.pity5After() == 0) {
                            ok.incrementAndGet();
                        }
                    } catch (Throwable t) {
                        // surface classloader races as failed count (ok stays low)
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(done.await(30, TimeUnit.SECONDS));
            assertEquals(tasks, ok.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("并发路由查询应稳定返回 node 分配")
    void concurrentCenterRoutingShouldBeStable() throws Exception {
        RemoteCenterServer center = new RemoteCenterServer(
                new InMemoryMultiNodeRoutingClient(List.of("n1", "n2", "n3")), "n1");
        int tasks = 300;
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch done = new CountDownLatch(tasks);
        AtomicInteger ok = new AtomicInteger();
        try {
            for (int i = 0; i < tasks; i++) {
                final int plane = i;
                pool.execute(() -> {
                    try {
                        var plan = center.planMigration(plane, 1);
                        if (plan.nodeId() != null && plan.zoneId() > 0) {
                            ok.incrementAndGet();
                        }
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(done.await(30, TimeUnit.SECONDS));
            assertEquals(tasks, ok.get());
        } finally {
            pool.shutdownNow();
        }
    }
}
