package cn.itcast.demo.mylunarcore.config;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Spliterators;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.StreamSupport;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * NettyGameBusinessConfiguration 业务线程池测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code NettyGameBusinessConfigurationTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("NettyGameBusinessConfiguration 业务线程池测试")
class NettyGameBusinessConfigurationTest {

    private static final Logger log = LoggerFactory.getLogger(NettyGameBusinessConfigurationTest.class);

    private static final int EXPLICIT_THREADS = 4;
    private static final int TASK_COUNT = 12;
    private static final long AWAIT_SECONDS = 10L;

    private NettyGameBusinessConfiguration configuration;
    private DefaultEventExecutorGroup executorGroup;
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @BeforeEach
    void setUp() {
        configuration = new NettyGameBusinessConfiguration();
    }

    @AfterEach
    void tearDown() {
        if (executorGroup != null) {
            configuration.shutdownBusinessExecutors();
            log.info("线程池销毁完成: shutdownGracefully=true, awaitSeconds=5");
        }
    }

    /**
     * 验证点：显式配置 businessThreads 应创建对应数量的执行器。
     * <p>测试方法 {@code explicitBusinessThreadsShouldCreateMatchingExecutorCount}：
     * <ul>
     *   <li>{@code assertEquals(EXPLICIT_THREADS, executorCount);}</li>
     *   <li>{@code assertFalse(executorGroup.isShuttingDown());}</li>
     *   <li>{@code assertFalse(executorGroup.isTerminated());}</li>
     * </ul>
     */
    @Test
    @DisplayName("显式配置 businessThreads 应创建对应数量的执行器")
    void explicitBusinessThreadsShouldCreateMatchingExecutorCount() {
        LunarCoreProperties properties = propertiesWithBusinessThreads(EXPLICIT_THREADS);
        executorGroup = configuration.gameBusinessExecutorGroup(properties, meterRegistry);

        int executorCount = countExecutors(executorGroup);
        log.info("线程池初始化: configuredThreads={}, executorCount={}, isShuttingDown={}, isTerminated={}",
                EXPLICIT_THREADS, executorCount, executorGroup.isShuttingDown(), executorGroup.isTerminated());
        assertEquals(EXPLICIT_THREADS, executorCount);
        assertFalse(executorGroup.isShuttingDown());
        assertFalse(executorGroup.isTerminated());
    }

    /**
     * 验证点：businessThreads<=0 时应按 CPU 推算默认线程数。
     * <p>测试方法 {@code zeroBusinessThreadsShouldUseCpuBasedDefault}：
     * <ul>
     *   <li>{@code assertEquals(expected, executorCount);}</li>
     * </ul>
     */
    @Test
    @DisplayName("businessThreads<=0 时应按 CPU 推算默认线程数")
    void zeroBusinessThreadsShouldUseCpuBasedDefault() {
        LunarCoreProperties properties = propertiesWithBusinessThreads(0);
        int expected = Math.max(4, Runtime.getRuntime().availableProcessors() * 2);

        executorGroup = configuration.gameBusinessExecutorGroup(properties, meterRegistry);
        int executorCount = countExecutors(executorGroup);

        log.info("默认线程数推算: businessThreads=0, availableProcessors={}, expectedThreads={}, executorCount={}",
                Runtime.getRuntime().availableProcessors(), expected, executorCount);
        assertEquals(expected, executorCount);
    }

    /**
     * 验证点：提交任务应在 game-business 线程上执行并完成。
     * <p>测试方法 {@code submittedTasksShouldRunOnBusinessThreads}：
     * <ul>
     *   <li>{@code assertTrue(finished, "任务应在超时前全部完成");}</li>
     *   <li>{@code assertEquals(TASK_COUNT, completed.get());}</li>
     *   <li>{@code assertTrue(threadNames.size() >= 1 && threadNames.size() <= EXPLICIT_THREADS);}</li>
     *   <li>{@code assertTrue(threadName.startsWith("game-business"),}</li>
     * </ul>
     */
    @Test
    @DisplayName("提交任务应在 game-business 线程上执行并完成")
    void submittedTasksShouldRunOnBusinessThreads() throws Exception {
        LunarCoreProperties properties = propertiesWithBusinessThreads(EXPLICIT_THREADS);
        executorGroup = configuration.gameBusinessExecutorGroup(properties, meterRegistry);

        CountDownLatch latch = new CountDownLatch(TASK_COUNT);
        AtomicInteger completed = new AtomicInteger();
        Set<String> threadNames = ConcurrentHashMap.newKeySet();
        List<Future<?>> futures = new ArrayList<>(TASK_COUNT);

        for (int taskId = 0; taskId < TASK_COUNT; taskId++) {
            final int id = taskId;
            Promise<Void> promise = executorGroup.next().newPromise();
            futures.add(promise);
            executorGroup.execute(() -> {
                String threadName = Thread.currentThread().getName();
                threadNames.add(threadName);
                completed.incrementAndGet();
                log.info("业务任务执行: taskId={}, thread={}, completed={}/{}",
                        id, threadName, completed.get(), TASK_COUNT);
                promise.setSuccess(null);
                latch.countDown();
            });
        }

        boolean finished = latch.await(AWAIT_SECONDS, TimeUnit.SECONDS);
        for (Future<?> future : futures) {
            future.await(AWAIT_SECONDS, TimeUnit.SECONDS);
        }

        log.info("并发任务汇总: taskCount={}, finished={}, completed={}, distinctThreads={}, threadNames={}",
                TASK_COUNT, finished, completed.get(), threadNames.size(), threadNames);
        assertTrue(finished, "任务应在超时前全部完成");
        assertEquals(TASK_COUNT, completed.get());
        assertTrue(threadNames.size() >= 1 && threadNames.size() <= EXPLICIT_THREADS);
        for (String threadName : threadNames) {
            assertTrue(threadName.startsWith("game-business"),
                    () -> "线程名应以 game-business 为前缀: " + threadName);
        }
    }

    /**
     * 验证点：多任务应能并行使用池中不同线程。
     * <p>测试方法 {@code multipleTasksShouldUsePoolInParallel}：
     * <ul>
     *   <li>{@code assertTrue(allRunning);}</li>
     *   <li>{@code assertEquals(EXPLICIT_THREADS, activeThreads.size());}</li>
     * </ul>
     */
    @Test
    @DisplayName("多任务应能并行使用池中不同线程")
    void multipleTasksShouldUsePoolInParallel() throws Exception {
        LunarCoreProperties properties = propertiesWithBusinessThreads(EXPLICIT_THREADS);
        executorGroup = configuration.gameBusinessExecutorGroup(properties, meterRegistry);

        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch running = new CountDownLatch(EXPLICIT_THREADS);
        Set<String> activeThreads = ConcurrentHashMap.newKeySet();

        for (int i = 0; i < EXPLICIT_THREADS; i++) {
            final int slot = i;
            executorGroup.execute(() -> {
                try {
                    startGate.await(AWAIT_SECONDS, TimeUnit.SECONDS);
                    String threadName = Thread.currentThread().getName();
                    activeThreads.add(threadName);
                    log.info("并行槽位占用: slot={}, thread={}, activeCount={}",
                            slot, threadName, activeThreads.size());
                    running.countDown();
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }

        startGate.countDown();
        boolean allRunning = running.await(AWAIT_SECONDS, TimeUnit.SECONDS);

        log.info("并行度校验: expectedParallelSlots={}, observedThreads={}, allRunning={}, threads={}",
                EXPLICIT_THREADS, activeThreads.size(), allRunning, activeThreads);
        assertTrue(allRunning);
        assertEquals(EXPLICIT_THREADS, activeThreads.size());
    }

    /**
     * 验证点：shutdownBusinessExecutors 应优雅关闭线程池。
     * <p>测试方法 {@code shutdownShouldTerminateExecutorGroup}：
     * <ul>
     *   <li>{@code assertNotNull(threadName);}</li>
     *   <li>{@code assertTrue(executorGroup.isTerminated() || executorGroup.isShutdown());}</li>
     * </ul>
     */
    @Test
    @DisplayName("shutdownBusinessExecutors 应优雅关闭线程池")
    void shutdownShouldTerminateExecutorGroup() throws Exception {
        LunarCoreProperties properties = propertiesWithBusinessThreads(2);
        executorGroup = configuration.gameBusinessExecutorGroup(properties, meterRegistry);

        Promise<String> probe = executorGroup.next().newPromise();
        executorGroup.execute(() -> {
            String threadName = Thread.currentThread().getName();
            log.info("关闭前探针任务: thread={}, isShuttingDown={}", threadName, executorGroup.isShuttingDown());
            probe.setSuccess(threadName);
        });
        String threadName = probe.get(AWAIT_SECONDS, TimeUnit.SECONDS);
        assertNotNull(threadName);

        configuration.shutdownBusinessExecutors();
        log.info("关闭后状态: thread={}, isShuttingDown={}, isTerminated={}, isShutdown={}",
                threadName, executorGroup.isShuttingDown(), executorGroup.isTerminated(), executorGroup.isShutdown());
        assertTrue(executorGroup.isTerminated() || executorGroup.isShutdown());

        executorGroup = null;
    }

    private static LunarCoreProperties propertiesWithBusinessThreads(int businessThreads) {
        LunarCoreProperties properties = new LunarCoreProperties();
        properties.getNetty().setBusinessThreads(businessThreads);
        return properties;
    }

    private static int countExecutors(DefaultEventExecutorGroup group) {
        return (int) StreamSupport.stream(Spliterators.spliteratorUnknownSize(group.iterator(), 0), false).count();
    }
}
