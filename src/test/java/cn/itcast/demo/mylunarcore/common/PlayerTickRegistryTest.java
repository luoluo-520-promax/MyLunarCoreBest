package cn.itcast.demo.mylunarcore.common;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PlayerTickRegistry 在线玩家注册表测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code PlayerTickRegistryTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("PlayerTickRegistry 在线玩家注册表测试")
class PlayerTickRegistryTest {

    private static final Logger log = LoggerFactory.getLogger(PlayerTickRegistryTest.class);

    private PlayerTickRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new PlayerTickRegistry();
        log.info("玩家注册表初始化: initialSize=0");
    }

    /**
     * 验证点：register 应登记 OnlinePlayer。
     * <p>测试方法 {@code registerShouldStoreOnlinePlayer}：
     * <ul>
     *   <li>{@code assertEquals(1, snapshot.size());}</li>
     *   <li>{@code assertEquals(CommonTestFixtures.PLAYER_UID, snapshot.get(0).getUid());}</li>
     * </ul>
     */
    @Test
    @DisplayName("register 应登记 OnlinePlayer")
    void registerShouldStoreOnlinePlayer() {
        OnlinePlayer player = mockOnlinePlayer(CommonTestFixtures.PLAYER_UID);
        registry.register(player);

        List<OnlinePlayer> snapshot = toList(registry.snapshotOnlinePlayers());
        log.info("注册校验: uid={}, snapshotSize={}, registeredUid={}",
                CommonTestFixtures.PLAYER_UID, snapshot.size(), snapshot.get(0).getUid());
        assertEquals(1, snapshot.size());
        assertEquals(CommonTestFixtures.PLAYER_UID, snapshot.get(0).getUid());
    }

    /**
     * 验证点：unregister 应移除指定 uid。
     * <p>测试方法 {@code unregisterShouldRemovePlayer}：
     * <ul>
     *   <li>{@code assertEquals(0, snapshot.size());}</li>
     * </ul>
     */
    @Test
    @DisplayName("unregister 应移除指定 uid")
    void unregisterShouldRemovePlayer() {
        OnlinePlayer player = mockOnlinePlayer(CommonTestFixtures.PLAYER_UID);
        registry.register(player);
        registry.unregister(CommonTestFixtures.PLAYER_UID);

        List<OnlinePlayer> snapshot = toList(registry.snapshotOnlinePlayers());
        log.info("注销校验: uid={}, snapshotSize={}", CommonTestFixtures.PLAYER_UID, snapshot.size());
        assertEquals(0, snapshot.size());
    }

    /**
     * 验证点：同 uid 重复 register 应覆盖旧实例。
     * <p>测试方法 {@code registerSameUidShouldOverwrite}：
     * <ul>
     *   <li>{@code assertEquals(1, snapshot.size());}</li>
     *   <li>{@code assertFalse(snapshot.get(0) == first);}</li>
     * </ul>
     */
    @Test
    @DisplayName("同 uid 重复 register 应覆盖旧实例")
    void registerSameUidShouldOverwrite() {
        OnlinePlayer first = mockOnlinePlayer(CommonTestFixtures.PLAYER_UID);
        OnlinePlayer second = mockOnlinePlayer(CommonTestFixtures.PLAYER_UID);
        registry.register(first);
        registry.register(second);

        List<OnlinePlayer> snapshot = toList(registry.snapshotOnlinePlayers());
        log.info("覆盖注册校验: uid={}, snapshotSize={}, sameInstance={}",
                CommonTestFixtures.PLAYER_UID, snapshot.size(), snapshot.get(0) == second);
        assertEquals(1, snapshot.size());
        assertFalse(snapshot.get(0) == first);
    }

    private static OnlinePlayer mockOnlinePlayer(long uid) {
        OnlinePlayer player = mock(OnlinePlayer.class);
        when(player.getUid()).thenReturn(uid);
        return player;
    }

    private static List<OnlinePlayer> toList(Iterable<OnlinePlayer> players) {
        List<OnlinePlayer> list = new ArrayList<>();
        players.forEach(list::add);
        return list;
    }
}
