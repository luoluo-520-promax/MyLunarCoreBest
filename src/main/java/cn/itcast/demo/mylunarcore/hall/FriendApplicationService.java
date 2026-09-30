// 好友申请与关系流转的业务服务：发申请、回应、列表查询
package cn.itcast.demo.mylunarcore.hall;

import cn.itcast.demo.mylunarcore.model.FriendEntity; // 好友关系实体：playerId1/2、status、remark
import cn.itcast.demo.mylunarcore.repo.FriendRepository; // 好友关系表读写：insertRequest、updateStatus、listFriends
import cn.itcast.demo.mylunarcore.repo.PlayerDataRepository; // 玩家资料仓储：校验目标 uid 是否真实存在
import org.springframework.stereotype.Service; // Spring 服务 Bean，供 HallNettyService 注入
import org.springframework.transaction.annotation.Transactional; // 申请/回应写库包裹在事务中

import java.util.List; // 好友列表返回类型

/**
 * 好友申请与关系流转的业务服务。
 * 协议入参、回包组装、在线同步通知都由 {@link HallNettyService} 负责。
 * 状态约定：0=待处理申请，1=已是好友，3=已拒绝或已解除（可重新申请）。
 */
@Service
public class FriendApplicationService {

    /**
     * 好友操作结果 record。
     * success 表示业务是否成功；retcode 供协议层映射错误码。
     */
    public record FriendRequestResult(boolean success, int retcode) {}

    /** 好友关系仓储，负责查询、插入和状态更新。 */
    private final FriendRepository friendRepository;
    /** 玩家资料仓储，用于校验目标玩家是否真实存在。 */
    private final PlayerDataRepository playerDataRepository;

    /** 构造器注入好友仓储与玩家资料仓储。 */
    public FriendApplicationService(FriendRepository friendRepository,
                                    PlayerDataRepository playerDataRepository) {
        this.friendRepository = friendRepository; // 保存好友仓储引用
        this.playerDataRepository = playerDataRepository; // 保存玩家资料仓储引用
    }

    /**
     * 查询玩家的好友关系快照。
     * 返回结果包含待处理申请、已建立好友以及已拒绝记录，具体如何展示由上层协议决定。
     */
    public List<FriendEntity> listFriends(int playerId) {
        return friendRepository.listFriends(playerId); // 按 playerId 查 friend 表全量关系
    }

    /**
     * 发起好友申请。
     * retcode：0 成功，2 参数非法（自己/无效uid），3 目标不存在，4 已有未拒绝关系。
     */
    @Transactional // 插入申请与后续检查在同一事务内，避免脏状态
    public FriendRequestResult sendRequest(int requesterId, int targetId, String remark) {
        if (requesterId <= 0 || targetId <= 0 || requesterId == targetId) {
            return new FriendRequestResult(false, 2); // 不能向自己或非法 uid 发申请
        }
        if (playerDataRepository.loadPlayerByUid(targetId) == null) {
            return new FriendRequestResult(false, 3); // 目标玩家不存在
        }
        FriendEntity existing = friendRepository.findRelation(requesterId, targetId); // 查是否已有关系记录
        if (existing != null && existing.getStatus() != 3) {
            return new FriendRequestResult(false, 4); // status≠3 表示待处理或已是好友，不可重复申请
        }
        friendRepository.insertRequest(requesterId, targetId, remark); // 写入 friend 表，status=0 待处理
        return new FriendRequestResult(true, 0); // 申请成功
    }

    /**
     * 处理好友申请（同意/拒绝）。
     * 以「被申请方」视角读取关系，避免同一条申请被重复处理。
     * retcode：0 成功，2 找不到待处理申请。
     */
    @Transactional
    public FriendRequestResult respond(int playerId, int requesterId, boolean accept) {
        FriendEntity relation = friendRepository.findRelation(playerId, requesterId); // 被申请方查关系
        if (relation == null || relation.getStatus() != 0) {
            return new FriendRequestResult(false, 2); // 无待处理申请或已被处理
        }
        friendRepository.updateStatus(playerId, requesterId, accept ? 1 : 3); // 1=已是好友，3=已拒绝
        return new FriendRequestResult(true, 0); // 处理成功
    }
}
