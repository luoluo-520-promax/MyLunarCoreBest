// 模拟宇宙天赋实体所在包
package cn.itcast.demo.mylunarcore.model;

// Lombok 数据类
import lombok.Data;

// 时间戳字段
import java.sql.Timestamp;

/**
 * 玩家已解锁/已激活的 Rogue 天赋行，表 {@code rogue_talent}。
 */
@Data // 自动生成 getter/setter
public class RogueTalentEntity {
    private int playerId;        // 玩家 id
    private int talentId;        // 天赋配置 id
    private int level;           // 天赋等级
    private boolean activated;   // 是否已激活（部分天赋需手动激活）
    private Timestamp createdAt; // 解锁时间
    private Timestamp updatedAt; // 最后变更时间
}
