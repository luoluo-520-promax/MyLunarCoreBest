// 玩家角色（Avatar）实例实体所在包
package cn.itcast.demo.mylunarcore.model;

// Lombok 数据类
import lombok.Data;

// JDBC 时间戳
import java.sql.Timestamp;

/**
 * 玩家已解锁角色（光锥/角色卡）实例：对应表 {@code avatar}，用于编队与战斗属性来源。
 */
@Data // Lombok：生成 getter/setter 等样板方法
public class AvatarEntity {
    private long id;           // 表主键，玩家内角色实例唯一 id
    private int playerId;      // 所属玩家 id
    private int avatarId;      // 配置表中的角色模板 id

    private int level;         // 当前等级
    private long exp;          // 当前经验值
    private int promotion;     // 突破/晋阶阶数
    private int rank;          // 星魂/命座等等级
    private boolean locked;    // 是否锁定（防误分解）

    private Timestamp createdAt;  // 获得时间
    private Timestamp updatedAt;  // 最后变更时间
}
