// 抽卡玩家全局信息实体所在包
package cn.itcast.demo.mylunarcore.model;

// Lombok：减少样板代码
import lombok.Data;

// SQL 时间戳
import java.sql.Timestamp;

/**
 * 玩家全局抽卡信息：如新手池里程碑次数与是否已领等，与单 Banner 行区分。
 */
@Data // Lombok 自动生成 getter/setter
public class PlayerGachaInfoEntity {
    private int id;                   // 表主键
    private int playerId;             // 玩家 id
    private int ceilingNum;           // 常驻池天井/积分累计值
    private boolean ceilingClaimed;   // 是否已兑换过当期天井奖励
    private Timestamp createdAt;      // 创建时间
    private Timestamp updatedAt;      // 最后更新时间
}
