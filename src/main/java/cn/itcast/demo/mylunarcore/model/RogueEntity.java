// 模拟宇宙（Rogue）玩家进度实体所在包
package cn.itcast.demo.mylunarcore.model;

// Lombok 数据类
import lombok.Data;

// JDBC 时间戳
import java.sql.Timestamp;

/**
 * 模拟宇宙（Rogue）玩家在 SQL 中的进度行：对应表 {@code rogue}。
 * 与 {@link cn.itcast.demo.mylunarcore.model.RoguePlayerDataEntity} 等扩展表并存，按业务选用。
 */
@Data // Lombok：生成 getter/setter 等样板方法
public class RogueEntity {
    private int id;              // 行主键
    private int playerId;        // 玩家 id
    private int rogueId;         // 玩法/赛季配置 id

    private int currentFloor;    // 当前层数
    private int currentWave;     // 当前波次
    private int difficulty;      // 难度档位
    private int status;          // 进行中/已结束等
    private long score;          // 积分或评分

    private String rewardsClaimedJson;  // 已领奖励 id 列表 JSON
    private String progressDataJson;    // 局内进度快照 JSON

    private Timestamp startTime; // 本轮开始时间
    private Timestamp endTime;     // 本轮结束时间

    private Timestamp createdAt;
    private Timestamp updatedAt;
}
