package cn.itcast.demo.mylunarcore.challenge;

import cn.itcast.demo.mylunarcore.model.ChallengeGroupRewardEntity;
import cn.itcast.demo.mylunarcore.model.ChallengeHistoryEntity;
import cn.itcast.demo.mylunarcore.protocol.BattleSystemProto;
import cn.itcast.demo.mylunarcore.repo.BattleMonsterWaveRepository;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * 挑战模块测试用公共数据构造工具。
 */
final class ChallengeTestFixtures {

    private ChallengeTestFixtures() {
    }

    static ChallengeRuntime createRuntime(long challengeUid, int playerId, int challengeId) {
        int groupId = (challengeId / 1000) * 100;
        int stageId = 1000 + challengeId;
        List<BattleSystemProto.EnemyInfo> enemyInfo = new ArrayList<>();
        enemyInfo.add(BattleSystemProto.EnemyInfo.newBuilder().setWaveIndex(1).build());
        enemyInfo.add(BattleSystemProto.EnemyInfo.newBuilder().setWaveIndex(2).build());
        return new ChallengeRuntime(
                challengeUid,
                playerId,
                1,
                challengeId,
                groupId,
                stageId,
                1_700_000_000L,
                2,
                enemyInfo);
    }

    static List<BattleMonsterWaveRepository.WaveConfig> wavesForChallenge(int challengeId) {
        int stageId = 1000 + challengeId;
        List<BattleMonsterWaveRepository.WaveConfig> waves = new ArrayList<>();
        waves.add(new BattleMonsterWaveRepository.WaveConfig(1, stageId, 1, "[101,102]", 2));
        waves.add(new BattleMonsterWaveRepository.WaveConfig(2, stageId, 2, "[201]", 3));
        return waves;
    }

    static ChallengeHistoryEntity historyEntity(int challengeId, int groupId, int stars, int score) {
        ChallengeHistoryEntity entity = new ChallengeHistoryEntity();
        entity.setId(1L);
        entity.setPlayerId(77);
        entity.setChallengeId(challengeId);
        entity.setGroupId(groupId);
        entity.setStars(stars);
        entity.setScore(score);
        entity.setTakenReward(0);
        entity.setUpdatedAt(new Timestamp(1_700_000_000_000L));
        return entity;
    }

    static ChallengeGroupRewardEntity groupRewardEntity(int groupId, int takenStars) {
        ChallengeGroupRewardEntity entity = new ChallengeGroupRewardEntity();
        entity.setId(1L);
        entity.setPlayerId(77);
        entity.setGroupId(groupId);
        entity.setTakenStars(takenStars);
        return entity;
    }
}
