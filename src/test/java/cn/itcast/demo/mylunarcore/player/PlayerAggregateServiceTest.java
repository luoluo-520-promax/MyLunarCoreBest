package cn.itcast.demo.mylunarcore.player;

import cn.itcast.demo.mylunarcore.model.PlayerData;
import cn.itcast.demo.mylunarcore.repo.PlayerDataRepository;
import io.netty.channel.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PlayerAggregateService 聚合写入口测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code PlayerAggregateServiceTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("PlayerAggregateService 聚合写入口测试")
class PlayerAggregateServiceTest {

    private GameSessionManager sessionManager;
    private PlayerSessionLockService lockService;
    private PlayerSyncCoordinator syncCoordinator;
    private PlayerDataPeriodicPersistenceService persistenceService;
    private PlayerDataRepository repository;
    private PlayerDataCacheService cacheService;
    private PlayerAggregateService aggregateService;

    @BeforeEach
    void setUp() {
        sessionManager = mock(GameSessionManager.class);
        lockService = new PlayerSessionLockService();
        syncCoordinator = mock(PlayerSyncCoordinator.class);
        persistenceService = mock(PlayerDataPeriodicPersistenceService.class);
        repository = mock(PlayerDataRepository.class);
        cacheService = mock(PlayerDataCacheService.class);
        aggregateService = new PlayerAggregateService(
                sessionManager, lockService, syncCoordinator, persistenceService, repository, cacheService);
    }

    /**
     * 验证点：commit 应标记 dirty 并触发异步落盘与推送。
     * <p>测试方法 {@code commitShouldMarkDirtyPersistAndPush}：
     * <ul>
     *   <li>{@code when(sessionManager.getOrNull(PlayerTestFixtures.PLAYER_UID)).thenReturn(session);}</li>
     *   <li>{@code assertEquals(46, level);}</li>
     *   <li>{@code assertTrue(session.isDirty());}</li>
     *   <li>{@code verify(persistenceService).persistAsync(PlayerTestFixtures.PLAYER_UID, SyncReason.DATA_CHANGE);}</li>
     *   <li>{@code verify(syncCoordinator).pushToSession(eq(session), eq(data), eq(SyncReason.DATA_CHANGE));}</li>
     * </ul>
     */
    @Test
    @DisplayName("commit 应标记 dirty 并触发异步落盘与推送")
    void commitShouldMarkDirtyPersistAndPush() {
        Channel channel = mock(Channel.class);
        GameSession session = new GameSession(PlayerTestFixtures.PLAYER_UID, channel, null);
        PlayerData data = PlayerTestFixtures.playerData(PlayerTestFixtures.PLAYER_UID);
        session.setPlayerData(data);
        session.bindDataVersion(3);
        when(sessionManager.getOrNull(PlayerTestFixtures.PLAYER_UID)).thenReturn(session);

        Integer level = aggregateService.commit(PlayerTestFixtures.PLAYER_UID, pd -> {
            pd.getPlayer().setLevel(pd.getPlayer().getLevel() + 1);
            return pd.getPlayer().getLevel();
        });

        assertEquals(46, level);
        assertTrue(session.isDirty());
        verify(persistenceService).persistAsync(PlayerTestFixtures.PLAYER_UID, SyncReason.DATA_CHANGE);
        verify(syncCoordinator).pushToSession(eq(session), eq(data), eq(SyncReason.DATA_CHANGE));
    }

    /**
     * 验证点：同 uid 并发 commit 最终 level 与 dirty 一致。
     * <p>测试方法 {@code concurrentCommitShouldSerializeMutations}：
     * <ul>
     *   <li>{@code when(sessionManager.getOrNull(PlayerTestFixtures.PLAYER_UID)).thenReturn(session);}</li>
     *   <li>{@code assertTrue(done.await(5, TimeUnit.SECONDS));}</li>
     *   <li>{@code assertEquals(threads, applied.get());}</li>
     *   <li>{@code assertEquals(threads, data.getPlayer().getLevel());}</li>
     *   <li>{@code assertTrue(session.isDirty());}</li>
     *   <li>{@code verify(persistenceService, times(threads))}</li>
     * </ul>
     */
    @Test
    @DisplayName("同 uid 并发 commit 最终 level 与 dirty 一致")
    void concurrentCommitShouldSerializeMutations() throws Exception {
        Channel channel = mock(Channel.class);
        GameSession session = new GameSession(PlayerTestFixtures.PLAYER_UID, channel, null);
        PlayerData data = PlayerTestFixtures.playerData(PlayerTestFixtures.PLAYER_UID);
        data.getPlayer().setLevel(0);
        session.setPlayerData(data);
        when(sessionManager.getOrNull(PlayerTestFixtures.PLAYER_UID)).thenReturn(session);

        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger applied = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.execute(() -> {
                try {
                    start.await();
                    aggregateService.commit(PlayerTestFixtures.PLAYER_UID, pd -> {
                        pd.getPlayer().setLevel(pd.getPlayer().getLevel() + 1);
                        return null;
                    });
                    applied.incrementAndGet();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(5, TimeUnit.SECONDS));
        pool.shutdownNow();

        assertEquals(threads, applied.get());
        assertEquals(threads, data.getPlayer().getLevel());
        assertTrue(session.isDirty());
        verify(persistenceService, times(threads))
                .persistAsync(PlayerTestFixtures.PLAYER_UID, SyncReason.DATA_CHANGE);
    }

    /**
     * 验证点：购买中途顶号后旧会话不再在线时 sync 应静默跳过。
     * <p>测试方法 {@code syncAfterKickShouldNoOpWhenOffline}：
     * <ul>
     *   <li>{@code when(sessionManager.getOrNull(anyLong())).thenReturn(null);}</li>
     *   <li>{@code verify(repository, times(0)).mergeScope(any(), anyLong(), any());}</li>
     * </ul>
     */
    @Test
    @DisplayName("购买中途顶号后旧会话不再在线时 sync 应静默跳过")
    void syncAfterKickShouldNoOpWhenOffline() {
        when(sessionManager.getOrNull(anyLong())).thenReturn(null);
        aggregateService.syncAfterExternalPersist(PlayerTestFixtures.PLAYER_UID, DataChangeScope.CORE);
        verify(repository, times(0)).mergeScope(any(), anyLong(), any());
    }
}
