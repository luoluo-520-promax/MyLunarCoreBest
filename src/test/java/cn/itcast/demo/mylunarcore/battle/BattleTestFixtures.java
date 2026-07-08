package cn.itcast.demo.mylunarcore.battle;

import cn.itcast.demo.mylunarcore.repo.BattleMonsterWaveRepository;
import cn.itcast.demo.mylunarcore.repo.MazeSkillActionRepository;

import java.util.ArrayList;
import java.util.List;

/**
 * 战斗模块测试用公共数据构造工具。
 */
final class BattleTestFixtures {

    private BattleTestFixtures() {
    }

    static BattleMonsterWaveRepository.WaveConfig waveConfig(int id, int stageId, int waveOrder,
                                                              String monstersJson, int customLevel) {
        return new BattleMonsterWaveRepository.WaveConfig(id, stageId, waveOrder, monstersJson, customLevel);
    }

    static List<BattleMonsterWaveRepository.WaveConfig> twoWaveStage() {
        List<BattleMonsterWaveRepository.WaveConfig> waves = new ArrayList<>();
        waves.add(waveConfig(1, 100, 1, "[101,102]", 2));
        waves.add(waveConfig(2, 100, 2, "[201]", 3));
        return waves;
    }

    static List<BattleMonsterWaveRepository.WaveConfig> singleWaveStage() {
        List<BattleMonsterWaveRepository.WaveConfig> waves = new ArrayList<>();
        waves.add(waveConfig(1, 200, 1, "[301]", 5));
        return waves;
    }

    static BattleContext createContext(long battleId, int playerId, List<BattleMonsterWaveRepository.WaveConfig> waves) {
        return BattleContext.createNew(battleId, playerId, 1, 100, 1_700_000_000L, waves);
    }

    static MazeSkillActionRepository.MazeSkillActionRow skillActionRow(int id, int skillId, int actionType,
                                                                        int order, String paramsJson) {
        return new MazeSkillActionRepository.MazeSkillActionRow(id, skillId, actionType, 0, order, paramsJson);
    }
}
