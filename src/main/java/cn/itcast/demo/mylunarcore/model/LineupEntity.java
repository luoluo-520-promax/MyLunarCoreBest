// 编队预设实体所在包
package cn.itcast.demo.mylunarcore.model;

// Lombok 数据类
import lombok.Data;

// JDBC 时间戳
import java.sql.Timestamp;

/**
 * 编队预设：对应表 {@code lineup}，{@code avatarsJson} 存阵容 JSON 原文，由客户端协议或业务解析。
 */
@Data // Lombok：生成 getter/setter 等样板方法
public class LineupEntity {
    private int id;              // 编队预设 id（玩家内）
    private int playerId;        // 所属玩家
    private String name;         // 编队名称
    private boolean active;      // 是否为当前出战编队
    /**
     * lineup.avatars JSON 原始字符串
     */
    private String avatarsJson;  // 阵容成员列表 JSON，不在这里反序列化

    private Timestamp createdAt;
    private Timestamp updatedAt;
}
