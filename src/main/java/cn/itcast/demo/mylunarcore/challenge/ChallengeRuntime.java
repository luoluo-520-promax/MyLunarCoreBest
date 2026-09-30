// 挑战玩法运行时状态所在包
package cn.itcast.demo.mylunarcore.challenge;

// 列表接口
import java.util.List;
// 战斗系统 Protobuf（EnemyInfo）
import cn.itcast.demo.mylunarcore.protocol.BattleSystemProto;

/**
 * 单次挑战关卡内存状态：波次、敌人信息、当前得分与星级掩码，由 {@link ChallengeNettyService} 推进直至结束。
 * 匹配成局时携带成员列表与共享 {@code sharedBattleId}。
 */
public class ChallengeRuntime {

    private final long challengeUid; // 本次挑战运行时实例 ID（内存唯一）
    private final int playerId; // 发起者玩家 ID
    private final int challengeType; // 挑战类型（协议枚举）
    private final int challengeId; // 关卡/挑战配置 ID
    private final int groupId; // 推导的挑战组 ID
    private final int stageId; // 映射的战斗关卡 ID（用于加载波次）
    private final long startTimeSeconds; // 开战时间（Unix 秒）
    private final int waveCount; // 波次数（来自配置）
    private final List<BattleSystemProto.EnemyInfo> enemyInfo; // 敌方波次快照（协议结构）
    private final List<Integer> memberPlayerIds; // 联机成员（含发起者）
    private final long sharedBattleId; // 共享战局 ID；0 表示无共享 BattleContext

    private volatile int status; // 1=进行中 / 2=胜利结算 / 3=失败
    private volatile int currentStage; // 当前阶段序号（演示字段）
    private volatile int roundsUsed; // 已用回合数
    private volatile int currentScore; // 当前得分
    private volatile int currentStarsMask; // 当前星级位掩码

    /**
     * 创建进行中的挑战运行时快照（单机，无共享战局）。
     */
    public ChallengeRuntime(long challengeUid,
                            int playerId,
                            int challengeType,
                            int challengeId,
                            int groupId,
                            int stageId,
                            long startTimeSeconds,
                            int waveCount,
                            List<BattleSystemProto.EnemyInfo> enemyInfo) {
        this(challengeUid, playerId, challengeType, challengeId, groupId, stageId,
                startTimeSeconds, waveCount, enemyInfo, List.of(playerId), 0L);
    }

    /**
     * 创建进行中的挑战运行时快照（可带联机成员与共享战局）。
     */
    public ChallengeRuntime(long challengeUid,
                            int playerId,
                            int challengeType,
                            int challengeId,
                            int groupId,
                            int stageId,
                            long startTimeSeconds,
                            int waveCount,
                            List<BattleSystemProto.EnemyInfo> enemyInfo,
                            List<Integer> memberPlayerIds,
                            long sharedBattleId) {
        this.challengeUid = challengeUid;
        this.playerId = playerId;
        this.challengeType = challengeType;
        this.challengeId = challengeId;
        this.groupId = groupId;
        this.stageId = stageId;
        this.startTimeSeconds = startTimeSeconds;
        this.waveCount = waveCount;
        this.enemyInfo = enemyInfo;
        if (memberPlayerIds == null || memberPlayerIds.isEmpty()) {
            this.memberPlayerIds = List.of(playerId);
        } else {
            this.memberPlayerIds = List.copyOf(memberPlayerIds);
        }
        this.sharedBattleId = Math.max(0L, sharedBattleId);
        this.status = 1;
        this.currentStage = 1;
        this.roundsUsed = 0;
        this.currentScore = 0;
        this.currentStarsMask = 0;
    }

    public long getChallengeUid() {
        return challengeUid;
    }

    public int getPlayerId() {
        return playerId;
    }

    public int getChallengeType() {
        return challengeType;
    }

    public int getChallengeId() {
        return challengeId;
    }

    public int getGroupId() {
        return groupId;
    }

    public int getStageId() {
        return stageId;
    }

    public long getStartTimeSeconds() {
        return startTimeSeconds;
    }

    public int getWaveCount() {
        return waveCount;
    }

    public List<BattleSystemProto.EnemyInfo> getEnemyInfo() {
        return enemyInfo;
    }

    public List<Integer> getMemberPlayerIds() {
        return memberPlayerIds;
    }

    public long getSharedBattleId() {
        return sharedBattleId;
    }

    public boolean isParticipant(int pid) {
        return memberPlayerIds.contains(pid);
    }

    public int getStatus() {
        return status;
    }

    public int getCurrentStage() {
        return currentStage;
    }

    public int getRoundsUsed() {
        return roundsUsed;
    }

    public int getCurrentScore() {
        return currentScore;
    }

    public int getCurrentStarsMask() {
        return currentStarsMask;
    }

    /**
     * 结算挑战：写入最终分数、星级与回合占用，并更新状态为胜/负。
     */
    public void markSettled(boolean win, int finalScore, int finalStarsMask, int roundsUsed) {
        this.status = win ? 2 : 3;
        this.currentScore = Math.max(0, finalScore);
        this.currentStarsMask = finalStarsMask;
        this.roundsUsed = Math.max(0, roundsUsed);
    }
}
