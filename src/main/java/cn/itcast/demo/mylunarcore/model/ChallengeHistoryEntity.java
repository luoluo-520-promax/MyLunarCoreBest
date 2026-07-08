// 挑战玩法领域实体所在包
package cn.itcast.demo.mylunarcore.model;

import lombok.Getter;
import lombok.Setter;

import java.sql.Timestamp;

/**
 * 单关卡挑战历史：星级、分数、已领奖励掩码等，对应表 {@code challenge_history}。
 */
@Setter
@Getter
public class ChallengeHistoryEntity {
    private long id; // 表主键
    private int playerId; // 玩家 ID
    private int challengeId; // 关卡/挑战实例配置 ID
    private int groupId; // 所属挑战组
    private int stars; // 达成的星级位掩码
    private int score; // 最佳分数
    private int takenReward; // 领奖进度位掩码
    private Timestamp createdAt; // 创建时间
    private Timestamp updatedAt; // 最近更新时间

}
