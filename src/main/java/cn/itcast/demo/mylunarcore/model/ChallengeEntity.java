// 玩家挑战/任务进度实体所在包
package cn.itcast.demo.mylunarcore.model;

// Lombok 数据类
import lombok.Data;

// JDBC 时间戳
import java.sql.Timestamp;

/**
 * 玩家挑战/任务类进度：对应表 {@code challenge}，与玩法模块 {@code challenge} 包中的配置实体区分（本类为玩家实例数据）。
 */
@Data // Lombok：生成 getter/setter 等样板方法
public class ChallengeEntity {
    private long id;              // 行主键
    private int playerId;         // 玩家 id
    private int challengeType;    // 挑战类型（日常/成就等）
    private int challengeId;      // 配置表中的挑战 id

    private long progress;        // 当前进度值
    private long maxProgress;     // 完成所需进度
    private int status;           // 状态：进行中/已完成等

    private Timestamp startTime;     // 开始时间
    private Timestamp completeTime;  // 完成时间（未完成可为 null）
    private boolean rewardClaimed;   // 奖励是否已领取
    private String extraDataJson;    // 扩展数据 JSON

    private Timestamp createdAt;
    private Timestamp updatedAt;
}
