// 模拟宇宙（Rogue）玩家扩展数据实体所在包
package cn.itcast.demo.mylunarcore.model;

// Lombok：自动生成 getter/setter 等
import lombok.Data;

// JDBC 时间戳类型
import java.sql.Timestamp;

/**
 * 扩展 Rogue 玩家数据行：天赋、奇物、命途选择与统计等 JSON 字段，表 {@code rogue_player_data}。
 */
@Data // Lombok 数据类
public class RoguePlayerDataEntity {
    private int playerId;                  // 所属玩家 id（主键或外键）
    private String talentsJson;            // 已解锁天赋树 JSON
    private String unlockedMiraclesJson;   // 已解锁奇物列表 JSON
    private Integer selectedPath;          // 当前选择的命途/路径 id，可为 null
    private int completedRuns;             // 累计通关次数
    private int highestFloor;              // 历史最高层数
    private long totalScore;               // 累计积分
    private Timestamp createdAt;           // 记录创建时间
    private Timestamp updatedAt;           // 最后更新时间
}
