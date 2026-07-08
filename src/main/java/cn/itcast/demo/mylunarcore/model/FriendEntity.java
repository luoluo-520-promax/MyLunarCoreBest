// 好友关系实体所在包
package cn.itcast.demo.mylunarcore.model;

// Lombok 数据类
import lombok.Data;

// JDBC 时间戳
import java.sql.Timestamp;

/**
 * 好友关系双边记录：对应表 {@code friend}，按玩家 id 查询时需考虑 {@code player_id_1} 与 {@code player_id_2} 两端。
 */
@Data // Lombok：生成 getter/setter 等样板方法
public class FriendEntity {
    private int id;              // 关系记录主键
    private int playerId1;       // 关系一端玩家 id（通常较小 id 或发起方）
    private int playerId2;       // 关系另一端玩家 id
    private int status;          // 状态：申请中/已同意/拉黑等
    private Timestamp createTime;   // 建立或申请时间
    private Timestamp confirmTime;  // 确认成为好友时间
    private Integer source;         // 来源渠道（可为 null）
    private String remark;          // 备注名
    private Timestamp updatedAt;
}
