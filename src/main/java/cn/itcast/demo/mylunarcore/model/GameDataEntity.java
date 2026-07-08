// 游戏侧通用数据实体所在包（非玩家个人数据）
package cn.itcast.demo.mylunarcore.model;

// Lombok 数据类注解
import lombok.Data;

// JDBC 时间戳类型，对应数据库 TIMESTAMP 列
import java.sql.Timestamp;

/**
 * L2：游戏侧通用资源/配置快照（JSON），按 {@code data_key} 唯一索引持久化在 {@code game_data} 表，
 * 与 {@link cn.itcast.demo.mylunarcore.model.PlayerEntity} 等玩家数据并列，供全服或管理端读取。
 */
@Data // Lombok：自动生成 getter/setter，供 MyBatis/JDBC 映射
public class GameDataEntity {
    private String dataKey;       // 业务键，全服唯一，如某活动快照 id
    private String payloadJson;   // JSON 正文，具体结构由业务解析
    private Timestamp updatedAt;  // 最后更新时间
}
