package cn.itcast.demo.mylunarcore.player;

import cn.itcast.demo.mylunarcore.common.PlayerTickRegistry;
import cn.itcast.demo.mylunarcore.center.OnlinePresenceService;
import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import io.netty.channel.Channel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * GameSessionManager 会话管理器测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code GameSessionManagerTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("GameSessionManager 会话管理器测试")
class GameSessionManagerTest {

    private static final Logger log = LoggerFactory.getLogger(GameSessionManagerTest.class);

    private PlayerTickRegistry tickRegistry;
    private LunarCoreProperties properties;
    private GameSessionManager manager;

    @BeforeEach
    void setUp() {
        tickRegistry = new PlayerTickRegistry();
        properties = PlayerTestFixtures.sessionProperties(3600L, 2);
        manager = new GameSessionManager(properties, tickRegistry, new OnlinePresenceService());
        log.info("会话管理器初始化: maxOnline={}, timeoutSeconds={}",
                properties.getSession().getMaxOnlinePlayers(),
                properties.getSession().getTimeoutSeconds());
    }

    @AfterEach
    void tearDown() {
        manager.shutdownGracefully();
    }

    /**
     * 验证点：createOrReplace 应注册新会话。
     * <p>测试方法 {@code createOrReplaceShouldRegisterSession}：
     * <ul>
     *   <li>{@code assertNotNull(session);}</li>
     *   <li>{@code assertEquals(PlayerTestFixtures.PLAYER_UID, session.getUid());}</li>
     *   <li>{@code assertEquals(1, manager.getOnlinePlayerCount());}</li>
     * </ul>
     */
    @Test
    @DisplayName("createOrReplace 应注册新会话")
    void createOrReplaceShouldRegisterSession() {
        Channel channel = mock(Channel.class);
        GameSession session = manager.createOrReplace(PlayerTestFixtures.PLAYER_UID, channel, null);

        assertNotNull(session);
        log.info("创建会话校验: uid={}, onlineCount={}, channelBound={}",
                session.getUid(), manager.getOnlinePlayerCount(), session.getChannel() == channel);
        assertEquals(PlayerTestFixtures.PLAYER_UID, session.getUid());
        assertEquals(1, manager.getOnlinePlayerCount());
    }

    /**
     * 验证点：同 uid 顶号应关闭旧 Channel。
     * <p>测试方法 {@code createOrReplaceShouldCloseOldChannelOnReplace}：
     * <ul>
     *   <li>{@code when(oldChannel.isActive()).thenReturn(true);}</li>
     *   <li>{@code verify(oldChannel).close();}</li>
     *   <li>{@code assertEquals(1, manager.getOnlinePlayerCount());}</li>
     *   <li>{@code assertEquals(newChannel, manager.getOrNull(PlayerTestFixtures.PLAYER_UID).getChannel());}</li>
     * </ul>
     */
    @Test
    @DisplayName("同 uid 顶号应关闭旧 Channel")
    void createOrReplaceShouldCloseOldChannelOnReplace() {
        Channel oldChannel = mock(Channel.class);
        Channel newChannel = mock(Channel.class);
        when(oldChannel.isActive()).thenReturn(true);

        manager.createOrReplace(PlayerTestFixtures.PLAYER_UID, oldChannel, null);
        manager.createOrReplace(PlayerTestFixtures.PLAYER_UID, newChannel, null);

        log.info("顶号校验: uid={}, onlineCount={}, oldChannelClosed=true",
                PlayerTestFixtures.PLAYER_UID, manager.getOnlinePlayerCount());
        verify(oldChannel).close();
        assertEquals(1, manager.getOnlinePlayerCount());
        assertEquals(newChannel, manager.getOrNull(PlayerTestFixtures.PLAYER_UID).getChannel());
    }

    /**
     * 验证点：bindSessionToken 应颁发令牌并使旧令牌失效。
     * <p>测试方法 {@code bindSessionTokenShouldRotateToken}：
     * <ul>
     *   <li>{@code assertEquals(64, token1.length());}</li>
     *   <li>{@code assertEquals(64, token2.length());}</li>
     *   <li>{@code assertNull(resolvedOld);}</li>
     *   <li>{@code assertEquals(PlayerTestFixtures.PLAYER_UID, resolvedNew);}</li>
     * </ul>
     */
    @Test
    @DisplayName("bindSessionToken 应颁发令牌并使旧令牌失效")
    void bindSessionTokenShouldRotateToken() {
        Channel channel = mock(Channel.class);
        manager.createOrReplace(PlayerTestFixtures.PLAYER_UID, channel, null);

        String token1 = manager.bindSessionToken(PlayerTestFixtures.PLAYER_UID);
        String token2 = manager.bindSessionToken(PlayerTestFixtures.PLAYER_UID);
        Long resolvedOld = manager.resolveToken(token1);
        Long resolvedNew = manager.resolveToken(token2);

        log.info("令牌轮换校验: uid={}, token1Len={}, token2Len={}, resolvedOld={}, resolvedNew={}",
                PlayerTestFixtures.PLAYER_UID, token1.length(), token2.length(), resolvedOld, resolvedNew);
        assertEquals(64, token1.length());
        assertEquals(64, token2.length());
        assertNull(resolvedOld);
        assertEquals(PlayerTestFixtures.PLAYER_UID, resolvedNew);
    }

    /**
     * 验证点：resolveToken 对空令牌应返回 null。
     * <p>测试方法 {@code resolveTokenShouldRejectBlank}：
     * <ul>
     *   <li>{@code assertNull(nullToken);}</li>
     *   <li>{@code assertNull(blankToken);}</li>
     * </ul>
     */
    @Test
    @DisplayName("resolveToken 对空令牌应返回 null")
    void resolveTokenShouldRejectBlank() {
        Long nullToken = manager.resolveToken(null);
        Long blankToken = manager.resolveToken("   ");

        log.info("空令牌校验: nullResult={}, blankResult={}", nullToken, blankToken);
        assertNull(nullToken);
        assertNull(blankToken);
    }

    /**
     * 验证点：canAcceptNewOnlineSlot 应遵守最大在线人数。
     * <p>测试方法 {@code canAcceptNewOnlineSlotShouldRespectMaxOnline}：
     * <ul>
     *   <li>{@code assertTrue(sameUid);}</li>
     *   <li>{@code assertFalse(newUidWhenFull);}</li>
     * </ul>
     */
    @Test
    @DisplayName("canAcceptNewOnlineSlot 应遵守最大在线人数")
    void canAcceptNewOnlineSlotShouldRespectMaxOnline() {
        Channel ch1 = mock(Channel.class);
        Channel ch2 = mock(Channel.class);
        manager.createOrReplace(PlayerTestFixtures.PLAYER_UID, ch1, null);
        manager.createOrReplace(PlayerTestFixtures.OTHER_UID, ch2, null);

        boolean sameUid = manager.canAcceptNewOnlineSlot(PlayerTestFixtures.PLAYER_UID);
        boolean newUidWhenFull = manager.canAcceptNewOnlineSlot(99L);

        log.info("在线名额校验: maxOnline=2, onlineCount={}, sameUid={}, newUidWhenFull={}",
                manager.getOnlinePlayerCount(), sameUid, newUidWhenFull);
        assertTrue(sameUid);
        assertFalse(newUidWhenFull);
    }

    /**
     * 验证点：removeSession 应清理会话与令牌。
     * <p>测试方法 {@code removeSessionShouldClearSessionAndToken}：
     * <ul>
     *   <li>{@code assertEquals(0, manager.getOnlinePlayerCount());}</li>
     *   <li>{@code assertTrue(found.isEmpty());}</li>
     *   <li>{@code assertNull(resolved);}</li>
     * </ul>
     */
    @Test
    @DisplayName("removeSession 应清理会话与令牌")
    void removeSessionShouldClearSessionAndToken() {
        Channel channel = mock(Channel.class);
        manager.createOrReplace(PlayerTestFixtures.PLAYER_UID, channel, null);
        String token = manager.bindSessionToken(PlayerTestFixtures.PLAYER_UID);

        manager.removeSession(PlayerTestFixtures.PLAYER_UID);
        Optional<GameSession> found = manager.findByUid(PlayerTestFixtures.PLAYER_UID);
        Long resolved = manager.resolveToken(token);

        log.info("移除会话校验: uid={}, onlineCount={}, found={}, tokenResolved={}",
                PlayerTestFixtures.PLAYER_UID, manager.getOnlinePlayerCount(), found.isPresent(), resolved);
        assertEquals(0, manager.getOnlinePlayerCount());
        assertTrue(found.isEmpty());
        assertNull(resolved);
    }

    /**
     * 验证点：updateActive 应刷新最后活跃时间。
     * <p>测试方法 {@code updateActiveShouldRefreshTimestamp}：
     * <ul>
     *   <li>{@code assertTrue(lastActive > 1L);}</li>
     * </ul>
     */
    @Test
    @DisplayName("updateActive 应刷新最后活跃时间")
    void updateActiveShouldRefreshTimestamp() {
        Channel channel = mock(Channel.class);
        GameSession session = manager.createOrReplace(PlayerTestFixtures.PLAYER_UID, channel, null);
        session.setLastActiveMillis(1L);

        manager.updateActive(PlayerTestFixtures.PLAYER_UID);
        long lastActive = session.getLastActiveMillis();

        log.info("活跃刷新校验: uid={}, lastActiveBefore=1, lastActiveAfter={}",
                PlayerTestFixtures.PLAYER_UID, lastActive);
        assertTrue(lastActive > 1L);
    }
}
