package cn.itcast.demo.mylunarcore.battle;

import cn.itcast.demo.mylunarcore.repo.BattleMonsterWaveRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BattleMonsterWaveSimpleFactory}：从 WaveConfig 的 monstersJson 生成 {@link WaveRuntime}。
 * 血量约定：maxHp ≈ baseHp * level（本测例 base=100 时 level3→300、level1→100）。
 */
@DisplayName("BattleMonsterWaveSimpleFactory 波次工厂测试")
class BattleMonsterWaveSimpleFactoryTest {

    private static final Logger log = LoggerFactory.getLogger(BattleMonsterWaveSimpleFactoryTest.class);

    /**
     * monstersJson=[101,102]、customLevel=3 → 两只怪，首只 id=101、level=3、maxHp=300。
     */
    @Test
    @DisplayName("应从合法 JSON 解析怪物并计算血量")
    void shouldCreateWaveFromValidConfig() {
        BattleMonsterWaveRepository.WaveConfig cfg =
                BattleTestFixtures.waveConfig(1, 100, 1, "[101,102]", 3);
        WaveRuntime wave = BattleMonsterWaveSimpleFactory.createWaveFromConfig(cfg);
        MonsterRuntime first = wave.getMonsters().get(0);

        log.info("合法波次解析校验: waveOrder={}, monsterCount={}, firstId={}, level={}, maxHp={}",
                wave.getWaveOrder(), wave.getMonsters().size(),
                first.getConfigMonsterId(), first.getLevel(), first.getMaxHp());
        assertEquals(1, wave.getWaveOrder());
        assertEquals(2, wave.getMonsters().size());
        assertEquals(101, first.getConfigMonsterId());
        assertEquals(3, first.getLevel());
        assertEquals(300, first.getMaxHp());
    }

    /** customLevel=0 时等级回落为 1，maxHp=100。 */
    @Test
    @DisplayName("customLevel 为 0 时应默认等级 1")
    void zeroCustomLevelShouldDefaultToOne() {
        BattleMonsterWaveRepository.WaveConfig cfg =
                BattleTestFixtures.waveConfig(2, 100, 2, "[201]", 0);
        WaveRuntime wave = BattleMonsterWaveSimpleFactory.createWaveFromConfig(cfg);
        MonsterRuntime monster = wave.getMonsters().get(0);

        log.info("默认等级校验: customLevel=0, level={}, maxHp={}",
                monster.getLevel(), monster.getMaxHp());
        assertEquals(1, monster.getLevel());
        assertEquals(100, monster.getMaxHp());
    }

    /** 空串、非 JSON、非数组 JSON 均得到空怪物列表，不抛异常。 */
    @Test
    @DisplayName("非法或空 monstersJson 应返回空怪物列表")
    void invalidMonstersJsonShouldYieldEmptyMonsters() {
        BattleMonsterWaveRepository.WaveConfig empty =
                BattleTestFixtures.waveConfig(3, 100, 3, "", 1);
        BattleMonsterWaveRepository.WaveConfig invalid =
                BattleTestFixtures.waveConfig(4, 100, 4, "not-json", 1);
        BattleMonsterWaveRepository.WaveConfig notArray =
                BattleTestFixtures.waveConfig(5, 100, 5, "{\"id\":1}", 1);

        int emptyCount = BattleMonsterWaveSimpleFactory.createWaveFromConfig(empty).getMonsters().size();
        int invalidCount = BattleMonsterWaveSimpleFactory.createWaveFromConfig(invalid).getMonsters().size();
        int notArrayCount = BattleMonsterWaveSimpleFactory.createWaveFromConfig(notArray).getMonsters().size();

        log.info("非法 JSON 兜底校验: emptyJsonCount={}, invalidJsonCount={}, notArrayJsonCount={}",
                emptyCount, invalidCount, notArrayCount);
        assertTrue(emptyCount == 0 && invalidCount == 0 && notArrayCount == 0);
    }
}
