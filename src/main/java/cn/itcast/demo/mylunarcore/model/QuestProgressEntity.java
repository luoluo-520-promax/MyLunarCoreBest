// 任务进度实体所在包
package cn.itcast.demo.mylunarcore.model;

// Lombok：自动生成 getter/setter 等样板代码
import lombok.Data;

// JDBC 时间戳类型，对应数据库 TIMESTAMP 列
import java.sql.Timestamp;

/**
 * 玩家任务（主线/支线/日常）进度实体：对应表 {@code quest_progress}，
 * 由 {@link cn.itcast.demo.mylunarcore.quest.QuestProgressApplicationService} 读写，
 * 与 {@link cn.itcast.demo.mylunarcore.quest.QuestConfigRepository} 中的静态任务配置区分（本类为玩家实例数据）。
 */
@Data // Lombok：生成 getter/setter 等样板方法
public class QuestProgressEntity {
    private long id;                 // 行主键
    private int playerId;            // 所属玩家 id
    private int questId;             // 任务配置表 id
    private int status;              // 任务状态：进行中/已完成/已领奖/已失败等，具体枚举由业务定义
    private String objectivesJson;   // 各目标当前进度 JSON（如 [{objectiveId, progress}]），用于断点恢复
    private Timestamp createdAt;     // 任务接受/创建时间
    private Timestamp updatedAt;     // 任务进度最后变更时间
}
