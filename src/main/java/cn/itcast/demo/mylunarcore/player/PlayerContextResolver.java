// 从 Netty Channel 统一解析玩家 uid、playerId 与在线会话的组件
package cn.itcast.demo.mylunarcore.player;

import io.netty.channel.Channel;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.OptionalLong;

/**
 * 玩家上下文解析器。
 * <p>
 * 将各 *NettyService 中重复的「从 Channel 读 uid / 转 playerId / 查 GameSession」逻辑
 * 收敛到单一组件，保证登录后 {@link PlayerChannelAttributes#PLAYER_UID} 的读写路径一致。
 */
@Component
public class PlayerContextResolver {

    /** 全局在线会话管理器，resolveSession 时按 uid 查找 GameSession。 */
    private final GameSessionManager sessionManager;

    /**
     * 构造器注入 GameSessionManager。
     *
     * @param sessionManager 在线会话注册表
     */
    public PlayerContextResolver(GameSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    /**
     * 从 Channel 属性槽读取登录成功后绑定的玩家 uid。
     * Channel 为 null、未绑定 uid 或 uid ≤ 0 时视为未登录。
     *
     * @param channel 当前 Netty 连接
     * @return 有效 uid 的 OptionalLong；未登录为 empty
     */
    public OptionalLong resolveUid(Channel channel) {
        if (channel == null) {
            return OptionalLong.empty();
        }
        Long uid = channel.attr(PlayerChannelAttributes.PLAYER_UID).get();
        if (uid == null || uid <= 0) {
            return OptionalLong.empty();
        }
        return OptionalLong.of(uid);
    }

    /**
     * 将 64 位 uid 的低 32 位映射为 int 型 playerId。
     * <p>
     * 历史原因：部分仓储层与数据库主键使用 int 宽度；uid 在业务上需保证低 32 位唯一。
     * 未登录时返回 0，调用方通常以 {@code playerId <= 0} 判定拒绝业务请求。
     *
     * @param channel 当前 Netty 连接
     * @return playerId；未登录为 0
     */
    public int resolvePlayerId(Channel channel) {
        return resolveUid(channel)
                .stream()
                .mapToInt(uid -> (int) (uid & 0xffffffffL))
                .findFirst()
                .orElse(0);
    }

    /**
     * 解析当前连接对应的在线 GameSession。
     * Channel 已绑定 uid 但会话已被 remove（断连竞态）时返回 empty。
     *
     * @param channel 当前 Netty 连接
     * @return 在线会话 Optional；不存在为 empty
     */
    public Optional<GameSession> resolveSession(Channel channel) {
        return resolveUid(channel)
                .stream()
                .mapToObj(sessionManager::getOrNull)
                .filter(session -> session != null)
                .findFirst();
    }
}
