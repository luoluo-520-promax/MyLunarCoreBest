// 玩家内存聚合数据所在包
package cn.itcast.demo.mylunarcore.model;

// Lombok：生成 getter/setter 与 equals/hashCode 等
import lombok.Data;

// Java 列表类型（各子表装配结果）
import java.util.List;

/**
 * 玩家聚合根在内存中的完整快照：登录后由 {@link cn.itcast.demo.mylunarcore.repo.PlayerDataRepository#loadAllData(long)}
 * 一次性装载，挂在 {@link cn.itcast.demo.mylunarcore.player.GameSession} 上供各 Netty 业务与同步模块读取。
 * <p>
 * 各子列表与数据库表一一对应，修改后需通过对应 Repository 持久化并触发 {@link cn.itcast.demo.mylunarcore.player} 推送。
 */
@Data // Lombok：生成 getter/setter 等样板方法
public class PlayerData {
    private PlayerEntity player;              // 玩家主表一行
    private List<AvatarEntity> avatars;         // 全部角色实例
    private List<LineupEntity> lineups;         // 全部编队预设
    private List<FriendEntity> friends;         // 好友关系列表
    private List<ChallengeEntity> challenges;   // 挑战进度列表
    private List<RogueEntity> rogues;           // 模拟宇宙进度列表
    private List<GameItemEntity> items;         // 背包物品列表
}
