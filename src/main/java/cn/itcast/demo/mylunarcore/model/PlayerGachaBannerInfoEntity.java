// 抽卡玩家按卡池维度的保底数据实体所在包
package cn.itcast.demo.mylunarcore.model;

// Lombok 数据类注解
import lombok.Data;

// 数据库时间字段类型
import java.sql.Timestamp;

/**
 * 玩家在某类卡池上的保底计数：5 星/4 星保底与 UP 失败次数等，对应业务表持久化。
 */
@Data // 生成访问器与 equals/hashCode 等
public class PlayerGachaBannerInfoEntity {
    private long id;              // 表主键
    private int playerId;         // 玩家 id
    private int bannerType;       // 卡池类型（见 GachaBannerType）
    private int pity5;            // 距离上次 5 星的抽数（软保底计数）
    private int pity4;            // 距离上次 4 星的抽数
    private int failedUpCount;    // UP 池未命中 UP 的连续次数
    private Timestamp createdAt;  // 行创建时间
    private Timestamp updatedAt;  // 行最后更新时间
}
