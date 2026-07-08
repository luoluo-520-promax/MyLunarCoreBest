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

@DisplayName("PlayerCoreSyncable 核心数值同步切片测试")
class PlayerCoreSyncableTest {

    private static final Logger log = LoggerFactory.getLogger(PlayerCoreSyncableTest.class);

    private PlayerCoreSyncable syncable;

    @BeforeEach
    void setUp() {
        syncable = new PlayerCoreSyncable();
        log.info("核心同步切片初始化完成");
    }

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
