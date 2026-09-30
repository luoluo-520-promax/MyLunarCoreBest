// 配置热更后的在线玩家通知广播器
package cn.itcast.demo.mylunarcore.common;

// AI 辅助服务：攻略包更新通知
import cn.itcast.demo.mylunarcore.assist.AssistNettyService;
// 在线游戏会话：持有与客户端的 Netty 连接
import cn.itcast.demo.mylunarcore.player.GameSession;
// 会话快照提供者：遍历当前全部在线会话
import cn.itcast.demo.mylunarcore.player.GameSessionManager;
// Netty 通道：判断连接活性并写数据
import io.netty.channel.Channel;
// SLF4J 日志接口
import org.slf4j.Logger;
// 注册为 Spring 组件
import org.springframework.stereotype.Component;

/**
 * 配置热更后向在线玩家广播版本、活动与攻略包更新通知。
 * <p>广播目标取自 {@link GameSessionManager#snapshotSessions()} 快照，
 * 避免在遍历过程中新增/移除会话造成并发修改异常；仅对仍活跃的连接下发。</p>
 */
@Component
public class UpdateNotifyBroadcaster {

    private static final Logger log = AppLogger.logger(LogCategory.SYSTEM, UpdateNotifyBroadcaster.class);

    // 在线会话快照来源
    private final GameSessionManager sessionManager;
    // 版本更新通知推送器
    private final VersionNettyService versionNettyService;
    // 活动配置更新通知推送器
    private final ActivityNettyService activityNettyService;
    // 攻略包更新通知推送器
    private final AssistNettyService assistNettyService;

    /**
     * 构造器注入各通知推送器。
     */
    public UpdateNotifyBroadcaster(GameSessionManager sessionManager,
                                   VersionNettyService versionNettyService,
                                   ActivityNettyService activityNettyService,
                                   AssistNettyService assistNettyService) {
        this.sessionManager = sessionManager;
        this.versionNettyService = versionNettyService;
        this.activityNettyService = activityNettyService;
        this.assistNettyService = assistNettyService;
    }

    /**
     * 向全部在线活跃连接广播版本更新通知。
     *
     * @return 实际下发到的会话数
     */
    public int broadcastVersionUpdate() {
        return broadcast(session -> versionNettyService.pushVersionUpdateNotify(session.getChannel()));
    }

    /**
     * 向全部在线活跃连接广播活动配置更新通知。
     *
     * @return 实际下发到的会话数
     */
    public int broadcastActivityUpdate() {
        return broadcast(session -> activityNettyService.pushActivityConfigUpdateNotify(session.getChannel()));
    }

    /**
     * 向全部在线活跃连接广播攻略包更新通知。
     *
     * @return 实际下发到的会话数
     */
    public int broadcastGuidePackUpdate() {
        return broadcast(session -> assistNettyService.pushGuidePackUpdate(session.getChannel()));
    }

    /**
     * 一次性广播三类更新通知（版本/活动/攻略包），供 /reload 全量热更后调用。
     *
     * @return 三类中最大的下发会话数（便于日志统计覆盖度）
     */
    public int broadcastAll() {
        int versionPushed = broadcastVersionUpdate();
        int activityPushed = broadcastActivityUpdate();
        int guidePushed = broadcastGuidePackUpdate();
        log.info("Broadcast update notifies: versionSessions={}, activitySessions={}, guideSessions={}",
                versionPushed, activityPushed, guidePushed);
        return Math.max(versionPushed, Math.max(activityPushed, guidePushed));
    }

    /**
     * 新连接登录引导：向单个连接补齐当前版本/活动/攻略包快照通知。
     *
     * @param channel 目标连接；非活跃连接直接跳过
     */
    public void pushBootstrapNotifies(Channel channel) {
        if (channel == null || !channel.isActive()) {
            return;
        }
        versionNettyService.pushVersionUpdateNotify(channel);
        activityNettyService.pushActivityConfigUpdateNotify(channel);
        assistNettyService.pushGuidePackUpdate(channel);
    }

    /**
     * 泛型广播骨架：遍历会话快照，仅对活跃连接执行 {@code action} 并计数。
     */
    private int broadcast(PushAction action) {
        int pushed = 0;
        for (GameSession session : sessionManager.snapshotSessions()) {
            Channel channel = session == null ? null : session.getChannel();
            if (channel != null && channel.isActive()) {
                action.push(session);
                pushed++;
            }
        }
        return pushed;
    }

    /**
     * 单会话推送动作的函数式接口：由具体广播方法提供实现。
     */
    @FunctionalInterface
    private interface PushAction {
        void push(GameSession session);
    }
}
