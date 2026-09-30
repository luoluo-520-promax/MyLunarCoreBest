// 社交增量同步服务：好友列表、邮件列表变更时主动推送 SC_NOTIFY
package cn.itcast.demo.mylunarcore.hall;

import cn.itcast.demo.mylunarcore.net.CmdIds; // FRIEND_LIST_UPDATE_SC_NOTIFY、MAIL_UPDATE_SC_NOTIFY 命令字
import cn.itcast.demo.mylunarcore.net.GamePacket; // 协议包封装
import cn.itcast.demo.mylunarcore.player.GameSession; // 在线会话，含 Channel
import cn.itcast.demo.mylunarcore.player.GameSessionManager; // 按 uid 定位在线会话
import cn.itcast.demo.mylunarcore.protocol.HallSystemProto; // 好友/邮件增量通知 protobuf 定义
import org.springframework.stereotype.Service; // Spring 服务 Bean

import java.util.List; // 好友列表、邮件列表参数

/**
 * 社交增量同步服务。
 * 好友/邮件变更时直接推送增量通知，减少客户端轮询全量接口。
 * 离线玩家静默跳过，邮件等持久化数据上线后可拉取。
 */
@Service
public class SocialSyncService {

    /** 通过 uid 定位在线会话，精确单播推送。 */
    private final GameSessionManager sessionManager;

    /** 构造器注入会话管理器。 */
    public SocialSyncService(GameSessionManager sessionManager) {
        this.sessionManager = sessionManager; // 保存会话管理器引用
    }

    /**
     * 推送好友列表增量更新（FRIEND_LIST_UPDATE_SC_NOTIFY）。
     * 客户端收到后可刷新列表或直接使用附带快照更新 UI。
     */
    public void pushFriendUpdate(long uid, List<HallSystemProto.FriendInfo> friends) {
        GameSession session = sessionManager.getOrNull(uid); // 按 uid 查在线会话
        if (session == null || session.getChannel() == null) {
            return; // 离线则静默返回
        }
        HallSystemProto.FriendListUpdateScNotify notify = HallSystemProto.FriendListUpdateScNotify.newBuilder()
                .addAllFriends(friends) // 附带最新好友列表快照
                .build();
        session.getChannel().writeAndFlush(new GamePacket(CmdIds.FRIEND_LIST_UPDATE_SC_NOTIFY, notify.toByteArray())); // 单播推送
    }

    /**
     * 推送邮件列表增量更新（MAIL_UPDATE_SC_NOTIFY）。
     * 触发场景：系统补偿、GM 发件、领取后状态变化等。
     */
    public void pushMailUpdate(long uid, List<HallSystemProto.MailInfo> mails) {
        GameSession session = sessionManager.getOrNull(uid); // 按 uid 查在线会话
        if (session == null || session.getChannel() == null) {
            return; // 离线跳过
        }
        HallSystemProto.MailUpdateScNotify notify = HallSystemProto.MailUpdateScNotify.newBuilder()
                .addAllNewMails(mails) // 新增或变更的邮件列表
                .build();
        session.getChannel().writeAndFlush(new GamePacket(CmdIds.MAIL_UPDATE_SC_NOTIFY, notify.toByteArray())); // 单播推送
    }

    /**
     * 单封邮件便捷入口：包装为单元素列表调用 pushMailUpdate。
     * 避免调用方手工构造 List.of(mail)。
     */
    public void pushMailToPlayer(int playerId, HallSystemProto.MailInfo mail) {
        pushMailUpdate(playerId, List.of(mail)); // playerId 与 uid 在本项目中数值一致
    }
}
