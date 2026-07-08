package cn.itcast.demo.mylunarcore.player;

import cn.itcast.demo.mylunarcore.config.LunarCoreProperties;
import cn.itcast.demo.mylunarcore.model.PlayerData;
import cn.itcast.demo.mylunarcore.model.PlayerEntity;

/**
 * player 包单元测试共享构造器与常量。
 */
final class PlayerTestFixtures {

    static final long PLAYER_UID = 77L;
    static final long OTHER_UID = 88L;
    static final String NICKNAME = "开拓者";
    static final String CURRENCY_JSON = "{\"1\":\"1000\",\"2\":\"50\"}";

    private PlayerTestFixtures() {
    }

    static LunarCoreProperties sessionProperties(long timeoutSeconds, int maxOnlinePlayers) {
        LunarCoreProperties properties = new LunarCoreProperties();
        properties.getSession().setTimeoutSeconds(timeoutSeconds);
        properties.getSession().setMaxOnlinePlayers(maxOnlinePlayers);
        return properties;
    }

    static PlayerEntity playerEntity(long uid) {
        PlayerEntity entity = new PlayerEntity();
        entity.setUid(uid);
        entity.setNickname(NICKNAME);
        entity.setLevel(45);
        entity.setExp(12_000L);
        entity.setStamina(180);
        entity.setWorldLevel(5);
        entity.setCurrencyJson(CURRENCY_JSON);
        return entity;
    }

    static PlayerData playerData(long uid) {
        PlayerData data = new PlayerData();
        data.setPlayer(playerEntity(uid));
        return data;
    }
}
