// 角色天赋实体所在包
package cn.itcast.demo.mylunarcore.model;

// Lombok：自动生成 getter/setter 等样板代码
import lombok.Data;

// JDBC 时间戳类型，对应数据库 TIMESTAMP 列
import java.sql.Timestamp;

/**
 * 玩家角色已解锁/已激活的天赋行：对应表 {@code avatar_talent}，
 * 记录角色（avatarId）上每个天赋（talentId）的等级与激活状态，
 * 由 {@link cn.itcast.demo.mylunarcore.character.TalentApplicationService} 维护。
 */
@Data // Lombok：生成 getter/setter 等样板方法
public class AvatarTalentEntity {
    private int playerId;      // 所属玩家 id
    private int avatarId;      // 角色实例 id（对应角色模板在配置表中的 id）
    private int talentId;      // 天赋配置 id（行迹/命途技能等）
    private int level;         // 当前天赋等级
    private boolean activated; // 是否已激活（部分天赋需消耗材料手动激活）
    private Timestamp createdAt; // 解锁时间
    private Timestamp updatedAt; // 最后变更时间
}
