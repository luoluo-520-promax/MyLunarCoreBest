package cn.itcast.demo.mylunarcore.player;

import cn.itcast.demo.mylunarcore.model.PlayerData;
import cn.itcast.demo.mylunarcore.model.PlayerEntity;
import cn.itcast.demo.mylunarcore.protocol.PlayerSessionProto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * PlayerCoreSyncable 核心数值同步切片测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code PlayerCoreSyncableTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("PlayerCoreSyncable 核心数值同步切片测试")
class PlayerCoreSyncableTest {

    private static final Logger log = LoggerFactory.getLogger(PlayerCoreSyncableTest.class);

    private PlayerCoreSyncable syncable;

    @BeforeEach
    void setUp() {
        syncable = new PlayerCoreSyncable();
        log.info("核心同步切片初始化完成");
    }

    /**
     * 验证点：onSync 应将 PlayerEntity 写入 builder。
     * <p>测试方法 {@code onSyncShouldWriteCoreFields}：
     * <ul>
     *   <li>{@code assertEquals(45, notify.getLevel());}</li>
     *   <li>{@code assertEquals(12_000, notify.getExp());}</li>
     *   <li>{@code assertEquals(180, notify.getStamina());}</li>
     *   <li>{@code assertEquals(5, notify.getWorldLevel());}</li>
     *   <li>{@code assertEquals(PlayerTestFixtures.NICKNAME, notify.getNickname());}</li>
     *   <li>{@code assertEquals(1000, notify.getCurrencyOrDefault(1, 0));}</li>
     * </ul>
     */
    @Test
    @DisplayName("onSync 应将 PlayerEntity 写入 builder")
    void onSyncShouldWriteCoreFields() {
        PlayerData data = PlayerTestFixtures.playerData(PlayerTestFixtures.PLAYER_UID);
        PlayerSessionProto.PlayerUnifiedSyncScNotify.Builder builder =
                PlayerSessionProto.PlayerUnifiedSyncScNotify.newBuilder();

        syncable.onSync(builder, data);
        PlayerSessionProto.PlayerUnifiedSyncScNotify notify = builder.build();

        log.info("核心字段同步: level={}, exp={}, stamina={}, worldLevel={}, nickname={}, currency1={}, currency2={}",
                notify.getLevel(), notify.getExp(), notify.getStamina(), notify.getWorldLevel(),
                notify.getNickname(), notify.getCurrencyOrDefault(1, 0), notify.getCurrencyOrDefault(2, 0));
        assertEquals(45, notify.getLevel());
        assertEquals(12_000, notify.getExp());
        assertEquals(180, notify.getStamina());
        assertEquals(5, notify.getWorldLevel());
        assertEquals(PlayerTestFixtures.NICKNAME, notify.getNickname());
        assertEquals(1000, notify.getCurrencyOrDefault(1, 0));
        assertEquals(50, notify.getCurrencyOrDefault(2, 0));
    }

    /**
     * 验证点：player 为 null 时不应写入业务字段。
     * <p>测试方法 {@code onSyncWithNullPlayerShouldSkip}：
     * <ul>
     *   <li>{@code assertEquals(0, notify.getLevel());}</li>
     *   <li>{@code assertFalse(notify.getCurrencyMap().containsKey(1));}</li>
     * </ul>
     */
    @Test
    @DisplayName("player 为 null 时不应写入业务字段")
    void onSyncWithNullPlayerShouldSkip() {
        PlayerData data = new PlayerData();
        PlayerSessionProto.PlayerUnifiedSyncScNotify.Builder builder =
                PlayerSessionProto.PlayerUnifiedSyncScNotify.newBuilder();

        syncable.onSync(builder, data);
        PlayerSessionProto.PlayerUnifiedSyncScNotify notify = builder.build();

        log.info("空主实体同步: level={}, currencySize={}", notify.getLevel(), notify.getCurrencyCount());
        assertEquals(0, notify.getLevel());
        assertFalse(notify.getCurrencyMap().containsKey(1));
    }

    /**
     * 验证点：负值字段应钳制为非负。
     * <p>测试方法 {@code onSyncShouldClampNegativeValues}：
     * <ul>
     *   <li>{@code assertEquals(0, notify.getLevel());}</li>
     *   <li>{@code assertEquals(0, notify.getExp());}</li>
     *   <li>{@code assertEquals(0, notify.getStamina());}</li>
     *   <li>{@code assertEquals(0, notify.getWorldLevel());}</li>
     * </ul>
     */
    @Test
    @DisplayName("负值字段应钳制为非负")
    void onSyncShouldClampNegativeValues() {
        PlayerEntity entity = PlayerTestFixtures.playerEntity(PlayerTestFixtures.PLAYER_UID);
        entity.setLevel(-3);
        entity.setExp(-100L);
        entity.setStamina(-10);
        entity.setWorldLevel(-1);
        PlayerData data = new PlayerData();
        data.setPlayer(entity);

        PlayerSessionProto.PlayerUnifiedSyncScNotify.Builder builder =
                PlayerSessionProto.PlayerUnifiedSyncScNotify.newBuilder();
        syncable.onSync(builder, data);
        PlayerSessionProto.PlayerUnifiedSyncScNotify notify = builder.build();

        log.info("负值钳制: level={}, exp={}, stamina={}, worldLevel={}",
                notify.getLevel(), notify.getExp(), notify.getStamina(), notify.getWorldLevel());
        assertEquals(0, notify.getLevel());
        assertEquals(0, notify.getExp());
        assertEquals(0, notify.getStamina());
        assertEquals(0, notify.getWorldLevel());
    }
}
