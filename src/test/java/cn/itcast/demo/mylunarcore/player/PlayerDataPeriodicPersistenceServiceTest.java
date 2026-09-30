package cn.itcast.demo.mylunarcore.player;

import cn.itcast.demo.mylunarcore.common.PlayerTickRegistry;
import cn.itcast.demo.mylunarcore.center.OnlinePresenceService;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.model.PlayerEntity;
import cn.itcast.demo.mylunarcore.repo.PlayerDataRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.channel.Channel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PlayerDataPeriodicPersistenceService 停机落盘测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code PlayerDataPeriodicPersistenceServiceTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("PlayerDataPeriodicPersistenceService 停机落盘测试")
class PlayerDataPeriodicPersistenceServiceTest {

    private GameSessionManager sessionManager;
    private PlayerDataRepository repository;
    private PlayerDataPeriodicPersistenceService service;
    private PlayerDataMetrics metrics;

    @BeforeEach
    void setUp() {
        LunarCoreProperties properties = PlayerTestFixtures.sessionProperties(3600L, 0);
        sessionManager = new GameSessionManager(properties, new PlayerTickRegistry(), new OnlinePresenceService());
        repository = mock(PlayerDataRepository.class);
        metrics = new PlayerDataMetrics(new SimpleMeterRegistry());
        service = new PlayerDataPeriodicPersistenceService(
                repository, sessionManager, new PlayerSessionLockService(), properties, metrics);
    }

    @AfterEach
    void tearDown() {
        service.shutdownGracefully(1);
        sessionManager.shutdownGracefully();
    }

    /**
     * 验证点：停机落盘应对失败 uid 汇总且不挂死。
     * <p>测试方法 {@code shutdownPersistShouldSummarizeFailures}：
     * <ul>
     *   <li>{@code when(repository.persistPlayerSnapshot(any(PlayerEntity.class))).thenReturn(0);}</li>
     *   <li>{@code assertEquals(1, result.online());}</li>
     *   <li>{@code assertTrue(result.hasFailures());}</li>
     *   <li>{@code assertEquals(1, result.failedUids().size());}</li>
     *   <li>{@code assertEquals(PlayerTestFixtures.PLAYER_UID, result.failedUids().get(0));}</li>
     *   <li>{@code assertTrue(metrics.getPersistFailTotal() >= 1 || metrics.getVersionConflictTotal() >= 1);}</li>
     * </ul>
     */
    @Test
    @DisplayName("停机落盘应对失败 uid 汇总且不挂死")
    void shutdownPersistShouldSummarizeFailures() {
        Channel channel = mock(Channel.class);
        GameSession session = sessionManager.createOrReplace(PlayerTestFixtures.PLAYER_UID, channel, null);
        session.setPlayerData(PlayerTestFixtures.playerData(PlayerTestFixtures.PLAYER_UID));
        session.markDirty();

        when(repository.persistPlayerSnapshot(any(PlayerEntity.class))).thenReturn(0);

        ShutdownPersistResult result = service.persistAllOnlineSync(5_000L);

        assertEquals(1, result.online());
        assertTrue(result.hasFailures());
        assertEquals(1, result.failedUids().size());
        assertEquals(PlayerTestFixtures.PLAYER_UID, result.failedUids().get(0));
        assertTrue(metrics.getPersistFailTotal() >= 1 || metrics.getVersionConflictTotal() >= 1);
    }

    /**
     * 验证点：停机落盘成功应清除 dirty。
     * <p>测试方法 {@code shutdownPersistShouldClearDirtyOnSuccess}：
     * <ul>
     *   <li>{@code when(repository.persistPlayerSnapshot(any(PlayerEntity.class))).thenAnswer(inv -> {}</li>
     *   <li>{@code assertFalse(result.hasFailures());}</li>
     *   <li>{@code assertEquals(1, result.succeeded());}</li>
     *   <li>{@code assertFalse(session.isDirty());}</li>
     *   <li>{@code assertEquals(2, session.getDataVersion());}</li>
     * </ul>
     */
    @Test
    @DisplayName("停机落盘成功应清除 dirty")
    void shutdownPersistShouldClearDirtyOnSuccess() {
        Channel channel = mock(Channel.class);
        GameSession session = sessionManager.createOrReplace(PlayerTestFixtures.PLAYER_UID, channel, null);
        session.setPlayerData(PlayerTestFixtures.playerData(PlayerTestFixtures.PLAYER_UID));
        session.bindDataVersion(1);
        session.markDirty();

        when(repository.persistPlayerSnapshot(any(PlayerEntity.class))).thenAnswer(inv -> {
            PlayerEntity p = inv.getArgument(0);
            p.setDataVersion(p.getDataVersion() + 1);
            return 1;
        });

        ShutdownPersistResult result = service.persistAllOnlineSync(5_000L);

        assertFalse(result.hasFailures());
        assertEquals(1, result.succeeded());
        assertFalse(session.isDirty());
        assertEquals(2, session.getDataVersion());
    }
}
