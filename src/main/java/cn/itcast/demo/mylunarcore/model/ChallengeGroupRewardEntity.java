// 挑战玩法领域实体所在包
package cn.itcast.demo.mylunarcore.model;

import lombok.Getter;
import lombok.Setter;

import java.sql.Timestamp;

/**
 * 挑战组星级领奖进度：按玩家与组 ID 记录已领取星数掩码，对应表 {@code challenge_group_reward}。
 */
@Setter
@Getter
public class ChallengeGroupRewardEntity {
    private long id; // 表主键
    private int playerId; // 玩家 ID
    private int groupId; // 挑战组 ID
    private int takenStars; // 已领取星级位掩码
    private Timestamp createdAt; // 创建时间
    private Timestamp updatedAt; // 最近更新时间

}
