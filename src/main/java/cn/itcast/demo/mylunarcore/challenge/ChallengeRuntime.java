// 挑战玩法运行时状态所在包
package cn.itcast.demo.mylunarcore.challenge;

// 列表接口
import java.util.List;
// 战斗系统 Protobuf（EnemyInfo）
import cn.itcast.demo.mylunarcore.protocol.BattleSystemProto;

/**
 * 单次挑战关卡内存状态：波次、敌人信息、当前得分与星级掩码，由 {@link ChallengeNettyService} 推进直至结束。
 */
public class ChallengeRuntime {

    private final long challengeUid; // 本次挑战运行时实例 ID（内存唯一）
    private final int playerId; // 所属玩家 ID
    private final int challengeType; // 挑战类型（协议枚举）
    private final int challengeId; // 关卡/挑战配置 ID
    private final int groupId; // 推导的挑战组 ID
    private final int stageId; // 映射的战斗关卡 ID（用于加载波次）
    private final long startTimeSeconds; // 开战时间（Unix 秒）
    private final int waveCount; // 波次数（来自配置）
    private final List<BattleSystemProto.EnemyInfo> enemyInfo; // 敌方波次快照（协议结构）

    private volatile int status; // 1=进行中 / 2=胜利结算 / 3=失败
    private volatile int currentStage; // 当前阶段序号（演示字段）
    private volatile int roundsUsed; // 已用回合数
    private volatile int currentScore; // 当前得分
    private volatile int currentStarsMask; // 当前星级位掩码

    /**
     * 创建进行中的挑战运行时快照。
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
        this.challengeUid = challengeUid; // 保存挑战实例 UID
        this.playerId = playerId; // 保存玩家 ID
        this.challengeType = challengeType; // 保存挑战类型
        this.challengeId = challengeId; // 保存挑战关卡 ID
        this.groupId = groupId; // 保存挑战组 ID
        this.stageId = stageId; // 保存映射 stageId
        this.startTimeSeconds = startTimeSeconds; // 保存开战时间戳
        this.waveCount = waveCount; // 保存总波次数
        this.enemyInfo = enemyInfo; // 保存敌方波次快照
        this.status = 1; // 初始状态：进行中
        this.currentStage = 1; // 初始阶段为第 1 阶段
        this.roundsUsed = 0; // 初始未消耗回合
        this.currentScore = 0; // 初始得分为 0
        this.currentStarsMask = 0; // 初始未达成任何星
    }

    /** 返回挑战实例 UID。 */
    public long getChallengeUid() {
        return challengeUid; // 返回内存挑战 UID
    }

    /** 返回所属玩家 ID。 */
    public int getPlayerId() {
        return playerId; // 返回玩家 ID
    }

    /** 返回挑战类型。 */
    public int getChallengeType() {
        return challengeType; // 返回挑战类型枚举值
    }

    /** 返回挑战关卡 ID。 */
    public int getChallengeId() {
        return challengeId; // 返回挑战配置 ID
    }

    /** 返回挑战组 ID。 */
    public int getGroupId() {
        return groupId; // 返回推导的组 ID
    }

    /** 返回映射的 stageId。 */
    public int getStageId() {
        return stageId; // 返回波次 stageId
    }

    /** 返回开战时间（Unix 秒）。 */
    public long getStartTimeSeconds() {
        return startTimeSeconds; // 返回开战时间戳
    }

    /** 返回总波次数。 */
    public int getWaveCount() {
        return waveCount; // 返回波次总数
    }

    /** 返回敌方波次快照列表。 */
    public List<BattleSystemProto.EnemyInfo> getEnemyInfo() {
        return enemyInfo; // 返回不可变敌方信息引用
    }

    /** 返回当前状态（1 进行中 / 2 胜利 / 3 失败）。 */
    public int getStatus() {
        return status; // 返回 volatile 状态字段
    }

    /** 返回当前阶段序号。 */
    public int getCurrentStage() {
        return currentStage; // 返回当前阶段
    }

    /** 返回已消耗回合数。 */
    public int getRoundsUsed() {
        return roundsUsed; // 返回已用回合
    }

    /** 返回当前得分。 */
    public int getCurrentScore() {
        return currentScore; // 返回当前分数
    }

    /** 返回当前星级位掩码。 */
    public int getCurrentStarsMask() {
        return currentStarsMask; // 返回星级掩码
    }

    /**
     * 结算挑战：写入最终分数、星级与回合占用，并更新状态为胜/负。
     */
    public void markSettled(boolean win, int finalScore, int finalStarsMask, int roundsUsed) {
        this.status = win ? 2 : 3; // 胜利 status=2，失败 status=3
        this.currentScore = Math.max(0, finalScore); // 得分下限为 0
        this.currentStarsMask = finalStarsMask; // 写入最终星级掩码
        this.roundsUsed = Math.max(0, roundsUsed); // 回合数下限为 0
    }
}
