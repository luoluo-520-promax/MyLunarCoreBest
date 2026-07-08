// 聚合各 Syncable 实现，构建并推送玩家统一同步下行包
package cn.itcast.demo.mylunarcore.player;

// 玩家领域聚合根，各 Syncable 从中读取业务数据
import cn.itcast.demo.mylunarcore.model.PlayerData;

// 下行协议：玩家统一同步通知消息类型
import cn.itcast.demo.mylunarcore.protocol.PlayerSessionProto;

// 单个在线会话，提供 sendPlayerUnifiedSync 发包入口
import cn.itcast.demo.mylunarcore.player.GameSession;

// 声明为 Spring 组件
import org.springframework.stereotype.Component;

// Spring 自动注入所有 Syncable 实现类的列表
import java.util.List;

/**
 * 玩家统一同步协调器。
 * <p>
 * 聚合所有 {@link Syncable} 实现，组装完整的
 * {@link PlayerSessionProto.PlayerUnifiedSyncScNotify} 并通过 {@link GameSession} 下发。
 * Bean 执行顺序由各实现类上的 {@link org.springframework.core.annotation.Order} 与 Spring 列表注入顺序共同保证。
 * </p>
 */
@Component // 注册为 Spring Bean，供异步加载服务与 Tick 调用
public class PlayerSyncCoordinator {

    /** Spring 注入的全部同步切片实现，顺序由 @Order 决定 */
    private final List<Syncable> syncables;

    /**
     * 构造注入所有 Syncable 实现。
     *
     * @param syncables Spring 容器中全部 {@link Syncable} Bean 的有序列表
     */
    public PlayerSyncCoordinator(List<Syncable> syncables) {
        this.syncables = syncables; // 保存引用，build 时遍历调用 onSync
    }

    /**
     * 根据内存中的玩家数据与同步原因，构建完整的 Protobuf 统一同步消息。
     *
     * @param data   内存中的玩家聚合数据；允许为 null（此时仅填充元信息如原因码与时间戳）
     * @param reason 同步触发原因（登录、数据变更、定时等）
     * @return 已 build 完成、可直接序列化下发的 Protobuf 消息
     */
    public PlayerSessionProto.PlayerUnifiedSyncScNotify build(PlayerData data, SyncReason reason) {
        PlayerSessionProto.PlayerUnifiedSyncScNotify.Builder b =
                PlayerSessionProto.PlayerUnifiedSyncScNotify.newBuilder(); // 创建根消息构建器
        b.setSyncReason(reason.getCode()); // 写入同步原因码，客户端据此决定 UI 刷新策略
        b.setServerTime(System.currentTimeMillis() / 1000L); // 写入秒级服务器时间，供客户端对时
        if (data != null) { // 有玩家数据时才调用各切片写入业务字段
            for (Syncable syncable : syncables) { // 按 @Order 顺序遍历所有同步实现
                syncable.onSync(b, data); // 每个切片向同一 builder 追加各自负责的字段
            }
        }
        return b.build(); // 不可变 Protobuf 消息，供序列化或发送
    }

    /**
     * 组包并通过指定会话的 Netty Channel 下行推送统一同步包。
     *
     * @param session 目标在线会话；为 null 时静默忽略
     * @param data    玩家聚合数据
     * @param reason  同步原因
     */
    public void pushToSession(GameSession session, PlayerData data, SyncReason reason) {
        if (session == null) { // 会话已移除或从未建立，无需发包
            return;
        }
        session.sendPlayerUnifiedSync(build(data, reason)); // 先组包再委托会话写出
    }
}
