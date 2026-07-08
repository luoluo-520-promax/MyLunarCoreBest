// 将角色（Avatar）列表写入统一同步协议的 Syncable 实现
package cn.itcast.demo.mylunarcore.player;

// 角色实体：对应数据库 avatar 表一行记录
import cn.itcast.demo.mylunarcore.model.AvatarEntity;

// 玩家领域聚合根，内含角色列表
import cn.itcast.demo.mylunarcore.model.PlayerData;

// 下行同步 Protobuf 消息定义
import cn.itcast.demo.mylunarcore.protocol.PlayerSessionProto;

// Spring Bean 装配顺序注解
import org.springframework.core.annotation.Order;

// 声明为 Spring 组件
import org.springframework.stereotype.Component;

// 遍历内存中的角色集合
import java.util.List;

/**
 * 角色（Avatar）列表同步切片。
 * <p>
 * 将 {@link PlayerData#getAvatars()} 中每条 {@link AvatarEntity} 转为
 * {@link PlayerSessionProto.AvatarSyncEntry} 下发客户端。
 * </p>
 */
@Component // 注册为 Spring Bean
@Order(10) // 在核心数据(0)之后、阵容(20)之前同步，保证角色基础信息优先到达客户端
public class AvatarSyncable implements Syncable {

    @Override
    public void onSync(PlayerSessionProto.PlayerUnifiedSyncScNotify.Builder builder, PlayerData data) {
        List<AvatarEntity> avatars = data.getAvatars(); // 从聚合根获取角色列表
        if (avatars == null || avatars.isEmpty()) { // 无角色数据则跳过，不写入 repeated 字段
            return;
        }
        for (AvatarEntity a : avatars) { // 遍历每个已拥有的角色实例
            builder.addAvatars(PlayerSessionProto.AvatarSyncEntry.newBuilder()
                    .setId(a.getId()) // 角色实例主键（玩家维度唯一）
                    .setAvatarId(Math.max(0, a.getAvatarId())) // 角色配置表 id（模板 id）
                    .setLevel(Math.max(0, a.getLevel())) // 角色等级，负值钳制为 0
                    .setExp(Math.max(0, a.getExp())) // 当前经验值
                    .setPromotion(Math.max(0, a.getPromotion())) // 星魂/突破等级
                    .setRank(Math.max(0, a.getRank())) // 光锥/命座等等阶
                    .setLocked(a.isLocked()) // 是否被锁定（防止误分解等）
                    .build()); // 构建单条 AvatarSyncEntry 并追加
        }
    }
}
