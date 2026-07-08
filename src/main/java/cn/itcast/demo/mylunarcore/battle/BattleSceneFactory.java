// 战斗场景工厂所在包
package cn.itcast.demo.mylunarcore.battle;

// 关卡波次配置仓储
import cn.itcast.demo.mylunarcore.repo.BattleMonsterWaveRepository;
// Spring 组件
import org.springframework.stereotype.Component;

// 列表接口
import java.util.List;

/**
 * 战斗场景工厂：将波次配置装配为可直接使用的 {@link BattleContext}。
 */
@Component // Spring Bean：战局创建工厂
public class BattleSceneFactory {

    /**
     * 根据战斗基本信息与波次配置创建新战局上下文。
     */
    public BattleContext createBattleScene(long battleId,
                                           int playerId,
                                           int lineupId,
                                           int battleStageId,
                                           long startTimeSeconds,
                                           List<BattleMonsterWaveRepository.WaveConfig> waveConfigs) { // 装配完整战局
        return BattleContext.createNew(battleId, playerId, lineupId, battleStageId, startTimeSeconds, waveConfigs); // 委托 BattleContext 静态工厂
    }
}
