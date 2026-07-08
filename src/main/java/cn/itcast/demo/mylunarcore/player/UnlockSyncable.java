// 将挑战解锁进度写入统一同步协议的 Syncable 实现
package cn.itcast.demo.mylunarcore.player;

// 挑战进度实体，记录玩家对各挑战的完成状态
import cn.itcast.demo.mylunarcore.model.ChallengeEntity;

// 玩家领域聚合根，内含挑战列表
import cn.itcast.demo.mylunarcore.model.PlayerData;

// 下行同步 Protobuf 消息定义
import cn.itcast.demo.mylunarcore.protocol.PlayerSessionProto;

// Spring Bean 装配顺序注解
import org.springframework.core.annotation.Order;

// 声明为 Spring 组件
import org.springframework.stereotype.Component;

// 遍历挑战实体列表
import java.util.List;

/**
 * 解锁向数据同步切片。
 * <p>
 * 当前以「已完成挑战 id 集合」作为可展示解锁集合下发客户端；
 * 后续可扩展更多解锁维度（功能开关、区域解锁等）到 {@link PlayerSessionProto.UnlockSyncData}。
 * </p>
 */
@Component // 注册为 Spring Bean
@Order(30) // 在核心/角色/阵容之后同步，解锁信息通常依赖前述数据展示
public class UnlockSyncable implements Syncable {

    /** 约定：挑战 status 字段 >= 2 表示该挑战已完成（与业务层状态机一致） */
    private static final int CHALLENGE_COMPLETED_STATUS = 2;

    @Override
    public void onSync(PlayerSessionProto.PlayerUnifiedSyncScNotify.Builder builder, PlayerData data) {
        PlayerSessionProto.UnlockSyncData.Builder ub = PlayerSessionProto.UnlockSyncData.newBuilder(); // 解锁子消息构建器
        List<ChallengeEntity> challenges = data.getChallenges(); // 玩家全部挑战进度记录
        if (challenges != null) { // 挑战列表可能尚未加载
            for (ChallengeEntity c : challenges) { // 逐条判定是否已完成
                boolean doneByStatus = c.getStatus() >= CHALLENGE_COMPLETED_STATUS; // 通过状态字段判定完成
                // 通过进度条判定：maxProgress > 0 且当前进度已达上限
                boolean doneByProgress = c.getMaxProgress() > 0 && c.getProgress() >= c.getMaxProgress();
                if (doneByStatus || doneByProgress) { // 任一条件满足即视为已完成
                    ub.addCompletedChallengeIds(Math.max(0, c.getChallengeId())); // 追加已完成挑战的配置 id
                }
            }
        }
        builder.setUnlock(ub.build()); // 将解锁子消息设入统一同步根消息
    }
}
