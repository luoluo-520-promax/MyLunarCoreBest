// 背包物品实例实体所在包
package cn.itcast.demo.mylunarcore.model;

// Lombok 数据类
import lombok.Data;

// JDBC 时间戳
import java.sql.Timestamp;

/**
 * 背包物品实例：对应表 {@code game_item}，含强化、遗器词条、是否丢弃等字段，
 * 与 {@link cn.itcast.demo.mylunarcore.repo.ItemRepository} 的查询与协议转换配合使用。
 */
@Data // Lombok：生成 getter/setter 等样板方法
public class GameItemEntity {
    private long id;           // 物品实例主键
    private int playerId;      // 所属玩家
    private int itemId;        // 物品配置 id
    private int type;          // 物品大类（材料/光锥/遗器等）

    private long count;        // 堆叠数量
    private int level;         // 强化等级
    private long exp;          // 当前经验（若适用）
    private int promotion;     // 突破阶
    private int rank;          // 叠影/精炼等

    private boolean locked;    // 是否锁定
    private boolean discarded; // 是否已丢弃（软删）

    private Integer mainAffixId;      // 主词条 id（遗器，可为 null）
    private String subAffixesJson;    // 副词条 JSON 数组
    private Integer equipAvatarId;    // 装备在哪个角色实例上（可为 null）

    private Timestamp createdAt;
    private Timestamp updatedAt;
}
