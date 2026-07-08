package cn.itcast.demo.mylunarcore.battle;

import cn.itcast.demo.mylunarcore.protocol.BattleSystemProto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("BattleStatisticsUtil 战斗统计工具测试")
class BattleStatisticsUtilTest {

    private static final Logger log = LoggerFactory.getLogger(BattleStatisticsUtilTest.class);

    @Test
    @DisplayName("null 统计应返回空 JSON 对象")
    void nullStatisticsShouldReturnEmptyJson() {
        String json = BattleStatisticsUtil.toJson(null);
        log.info("null 统计序列化校验: input=null, json={}", json);
        assertEquals("{}", json);
    }

    @Test
    @DisplayName("应正确序列化战斗统计字段")
    void shouldSerializeStatisticsFields() {
        BattleSystemProto.BattleStatistics stats = BattleSystemProto.BattleStatistics.newBuilder()
                .setDamageDealt(1200)
                .setDamageTaken(300)
                .setTurnCount(5)
                .setKillCount(2)
                .setExtraDataJson("{\"mvp\":101}")
                .build();
        String json = BattleStatisticsUtil.toJson(stats);

        log.info("统计序列化校验: damageDealt={}, damageTaken={}, turnCount={}, killCount={}, json={}",
                stats.getDamageDealt(), stats.getDamageTaken(), stats.getTurnCount(),
                stats.getKillCount(), json);
        assertTrue(json.contains("\"damage_dealt\":1200"));
        assertTrue(json.contains("\"damage_taken\":300"));
        assertTrue(json.contains("\"turn_count\":5"));
        assertTrue(json.contains("\"kill_count\":2"));
        assertTrue(json.contains("\"extra_data\":\"{\\\"mvp\\\":101}\""));
    }
}
