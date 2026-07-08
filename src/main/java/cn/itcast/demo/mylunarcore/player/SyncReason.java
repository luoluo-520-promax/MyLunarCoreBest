// 同步触发原因枚举：与协议字段 sync_reason 数值一一对应
package cn.itcast.demo.mylunarcore.player;

/**
 * 玩家统一同步的触发原因。
 * <p>
 * 各枚举值的 {@link #code} 与
 * {@link cn.itcast.demo.mylunarcore.protocol.PlayerSessionProto.PlayerUnifiedSyncScNotify#getSyncReason}
 * 协议字段保持一致，便于客户端区分登录首包、数据变更推送与定时全量同步。
 * </p>
 */
public enum SyncReason {

    /** 登录成功后主动推送：异步全量加载完成时触发 */
    LOGIN(0),

    /** 服务端数据变更后主动推送：业务模块持久化后调用 {@link PlayerDataSyncService#notifyDataChanged} */
    DATA_CHANGE(1),

    /** 定时被动全量同步：OnlinePlayer Tick 周期触发 */
    TIMER(2);

    /** 写入 Protobuf sync_reason 字段时使用的整型编码 */
    private final int code;

    /**
     * 构造枚举常量并绑定协议数值。
     *
     * @param code 与客户端协议约定一致的同步原因码
     */
    SyncReason(int code) {
        this.code = code; // 保存不可变的协议编码
    }

    /**
     * @return 供 {@link PlayerSyncCoordinator#build} 写入 Protobuf 的原因码
     */
    public int getCode() {
        return code; // 返回构造时绑定的整型值
    }
}
