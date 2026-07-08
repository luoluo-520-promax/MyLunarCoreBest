// 玩家主表实体所在包
package cn.itcast.demo.mylunarcore.model;

// Lombok 数据类
import lombok.Data;

// 高精度小数：场景坐标
import java.math.BigDecimal;
// JDBC 时间戳类型
import java.sql.Timestamp;

/**
 * 玩家角色主表 {@code player}：等级、体力、货币 JSON、当前场景与坐标等，
 * 是 {@link PlayerData} 的核心引用，也是 uid 与账号关联的枢纽。
 */
@Data // Lombok：生成 getter/setter 等样板方法
public class PlayerEntity {
    private long uid;           // 玩家唯一 id（游戏内主键）
    private long accountId;     // 关联账号表 id（数值型，与 account 字符串 id 对应）
    private String nickname;    // 昵称

    private int level;          // 开拓等级
    private long exp;           // 当前经验
    private int worldLevel;     // 世界等级
    private int stamina;        // 当前体力值

    /**
     * player.currency JSON 原始字符串，后续在业务层再反序列化为 map
     */
    private String currencyJson; // 各货币数量 JSON，如星琼、信用点等

    private int sceneId;        // 当前场景/地图 id
    private BigDecimal posX;    // 场景内 X 坐标（高精度小数）
    private BigDecimal posY;
    private BigDecimal posZ;

    private Timestamp lastLogin;  // 上次登录时间
    private Timestamp lastLogout; // 上次登出时间
}
