// Channel AttributeKey 常量：在 Netty Channel 上绑定玩家 uid 等会话属性
package cn.itcast.demo.mylunarcore.player;

// Netty 提供的 Channel 自定义属性键类型，用于在连接对象上挂载业务数据
import io.netty.util.AttributeKey;

/**
 * Netty Channel 上与玩家会话相关的属性键定义。
 * <p>
 * 键名 {@code "playerUid"} 与项目中各处 {@code AttributeKey.valueOf("playerUid")} 保持同名，
 * 确保登录 Handler、心跳 Handler 等模块读写同一属性槽位。
 * </p>
 */
public final class PlayerChannelAttributes {

    /** Channel 上绑定的玩家 uid；登录成功后写入，登出或断连时清空 */
    public static final AttributeKey<Long> PLAYER_UID = AttributeKey.valueOf("playerUid");

    /**
     * 工具类禁止实例化，避免误创建无意义对象。
     */
    private PlayerChannelAttributes() {
    }
}
