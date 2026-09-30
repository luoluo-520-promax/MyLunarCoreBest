package cn.itcast.demo.mylunarcore.battle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WaveRuntime}：保存波次序号与怪物列表；null/空列表均安全。
 */
@DisplayName("WaveRuntime 波次运行时测试")
class WaveRuntimeTest {

    private static final Logger log = LoggerFactory.getLogger(WaveRuntimeTest.class);

    /** 波次 1 含两只怪，getMonsters().get(0) 配置 ID 为 101。 */
    @Test
    @DisplayName("应保存波次序号与怪物列表")
    void shouldHoldWaveOrderAndMonsters() {
        MonsterRuntime m1 = new MonsterRuntime(101, 2, 200);
        MonsterRuntime m2 = new MonsterRuntime(102, 2, 200);
        WaveRuntime wave = new WaveRuntime(1, List.of(m1, m2));

        log.info("波次属性校验: waveOrder={}, monsterCount={}, firstMonsterId={}",
                wave.getWaveOrder(), wave.getMonsters().size(),
                wave.getMonsters().get(0).getConfigMonsterId());
        assertEquals(1, wave.getWaveOrder());
        assertEquals(2, wave.getMonsters().size());
        assertEquals(101, wave.getMonsters().get(0).getConfigMonsterId());
    }

    /** monsters=null 时内部转为 empty，避免 NPE。 */
    @Test
    @DisplayName("怪物列表为 null 时应使用空列表")
    void nullMonstersShouldBecomeEmptyList() {
        WaveRuntime wave = new WaveRuntime(2, null);

        log.info("null 怪物列表兜底校验: waveOrder={}, monsterCount={}, isEmpty={}",
                wave.getWaveOrder(), wave.getMonsters().size(), wave.getMonsters().isEmpty());
        assertTrue(wave.getMonsters().isEmpty());
    }

    /** 显式 emptyList 同样 isEmpty。 */
    @Test
    @DisplayName("空怪物列表应正常工作")
    void emptyMonstersShouldWork() {
        WaveRuntime wave = new WaveRuntime(3, Collections.emptyList());

        log.info("空怪物列表校验: waveOrder={}, monsterCount={}",
                wave.getWaveOrder(), wave.getMonsters().size());
        assertTrue(wave.getMonsters().isEmpty());
    }
}
