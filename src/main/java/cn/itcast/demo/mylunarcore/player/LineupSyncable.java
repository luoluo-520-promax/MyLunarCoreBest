// 将阵容列表写入统一同步协议的 Syncable 实现
package cn.itcast.demo.mylunarcore.player;

// 阵容实体：对应数据库 lineup 表一行记录
import cn.itcast.demo.mylunarcore.model.LineupEntity;

// 玩家领域聚合根，内含阵容列表
import cn.itcast.demo.mylunarcore.model.PlayerData;

// 下行同步 Protobuf 消息定义
import cn.itcast.demo.mylunarcore.protocol.PlayerSessionProto;

// Spring Bean 装配顺序注解，数值越小越先执行 onSync
import org.springframework.core.annotation.Order;

// 声明为 Spring 管理的组件，供 PlayerSyncCoordinator 注入
import org.springframework.stereotype.Component;

// 遍历内存中的阵容集合
import java.util.List;

/**
 * 阵容（Lineup）同步切片。
 * <p>
 * 将 {@link PlayerData#getLineups()} 中每条 {@link LineupEntity} 转为
 * {@link PlayerSessionProto.LineupSyncEntry} 追加到统一同步包。
 * </p>
 */
@Component // 注册为 Spring Bean，自动加入 Syncable 列表
@Order(20) // 在核心(0)与角色(10)之后同步，满足前端先展示基础数据再展示阵容的依赖顺序
public class LineupSyncable implements Syncable {

    @Override
    public void onSync(PlayerSessionProto.PlayerUnifiedSyncScNotify.Builder builder, PlayerData data) {
        List<LineupEntity> lineups = data.getLineups(); // 从聚合根取出阵容列表引用
        if (lineups == null || lineups.isEmpty()) { // 未加载或无阵容数据则无需写入协议字段
            return;
        }
        for (LineupEntity l : lineups) { // 逐条阵容实体转换为协议条目
            builder.addLineups(PlayerSessionProto.LineupSyncEntry.newBuilder()
                    .setId(Math.max(0, l.getId())) // 阵容主键 id，负值钳制为 0
                    .setName(l.getName() == null ? "" : l.getName()) // 阵容名称，null 转空串
                    .setActive(l.isActive()) // 是否为当前激活阵容
                    .setAvatarsJson(l.getAvatarsJson() == null ? "" : l.getAvatarsJson()) // 阵容内角色 JSON 快照
                    .build()); // 构建单条 LineupSyncEntry 并追加到 repeated 字段
        }
    }
}
