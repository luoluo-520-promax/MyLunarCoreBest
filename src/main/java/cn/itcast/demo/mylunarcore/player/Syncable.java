// 定义「领域切片写入统一同步协议」的契约接口
package cn.itcast.demo.mylunarcore.player;

// 玩家领域聚合根（内存 L2），包含角色、阵容、挑战等子集合
import cn.itcast.demo.mylunarcore.model.PlayerData;

// 下行协议：玩家统一同步通知的 Protobuf 构建器类型
import cn.itcast.demo.mylunarcore.protocol.PlayerSessionProto;

/**
 * 可同步模块契约：将 {@link PlayerData} 中某一业务切片序列化到统一下行包。
 * <p>
 * 各实现类（如 {@link PlayerCoreSyncable}、{@link AvatarSyncable}）通过 {@link org.springframework.core.annotation.Order}
 * 控制写入顺序，由 {@link PlayerSyncCoordinator} 统一聚合后下发客户端。
 * </p>
 */
public interface Syncable {

    /**
     * 将 {@code data} 中本模块负责的字段写入 {@code builder}。
     *
     * @param builder 统一同步消息的 Protobuf 构建器，各切片向同一 builder 追加字段
     * @param data    当前会话内存中的玩家聚合数据
     */
    void onSync(PlayerSessionProto.PlayerUnifiedSyncScNotify.Builder builder, PlayerData data);
}
