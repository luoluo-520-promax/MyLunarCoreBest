// 角色天赋应用层：天赋树升级、归属校验与幂等保护
package cn.itcast.demo.mylunarcore.character;

import cn.itcast.demo.mylunarcore.model.AvatarTalentEntity; // 天赋实体：talentId、level、activated
import cn.itcast.demo.mylunarcore.repo.AvatarRepository; // 头像仓储：升级前校验 avatar 归属 playerId
import cn.itcast.demo.mylunarcore.repo.AvatarTalentRepository; // 天赋仓储：list/find/upgrade 写库
import org.springframework.stereotype.Service; // Spring 服务 Bean
import org.springframework.transaction.annotation.Transactional; // 升级写库包裹在事务中

import java.util.List; // 天赋列表返回类型

/**
 * 角色天赋应用服务。
 * 天赋挂在「playerId + avatarId」维度；升级前校验 avatar 归属，防止伪造 avatarId。
 * retcode：0 成功；2 角色不存在；3 targetLevel 不在 1~10；4 已不低于目标等级。
 */
@Service
public class TalentApplicationService {

    /** 天赋升级结果：success、retcode、升级后的 talent 实体（失败时可能为 null 或当前快照）。 */
    public record UpgradeResult(boolean success, int retcode, AvatarTalentEntity talent) {}

    /** 天赋表读写入口。 */
    private final AvatarTalentRepository avatarTalentRepository;
    /** 头像表查询入口，用于归属校验。 */
    private final AvatarRepository avatarRepository;

    /** 构造器注入天赋仓储与头像仓储。 */
    public TalentApplicationService(AvatarTalentRepository avatarTalentRepository,
                                    AvatarRepository avatarRepository) {
        this.avatarTalentRepository = avatarTalentRepository; // 保存天赋仓储
        this.avatarRepository = avatarRepository; // 保存头像仓储
    }

    /** 读取某玩家某角色下的全部天赋当前等级与激活状态。 */
    public List<AvatarTalentEntity> listTalents(int playerId, int avatarId) {
        return avatarTalentRepository.listByAvatar(playerId, avatarId); // 按 playerId+avatarId 查 talent 表
    }

    /**
     * 将指定天赋推进到 targetLevel。
     * 若当前等级已 ≥ targetLevel，返回 retcode=4 且不写库，避免重复点击产生无效事务。
     */
    @Transactional // 升级写库失败时整体回滚
    public UpgradeResult upgrade(int playerId, int avatarId, int talentId, int targetLevel) {
        if (avatarRepository.findAvatar(playerId, avatarId) == null) { // avatar 不属于该玩家或不存在
            return new UpgradeResult(false, 2, null); // retcode=2：角色不存在
        }
        if (targetLevel <= 0 || targetLevel > 10) { // 天赋等级合法范围为 1~10
            return new UpgradeResult(false, 3, null); // retcode=3：目标等级非法
        }
        AvatarTalentEntity current = avatarTalentRepository.find(playerId, avatarId, talentId); // 读当前天赋等级
        if (current != null && current.getLevel() >= targetLevel) { // 已不低于目标等级，幂等拒绝
            return new UpgradeResult(false, 4, current); // retcode=4：无需重复升级
        }
        AvatarTalentEntity updated =
                avatarTalentRepository.upgrade(playerId, avatarId, talentId, targetLevel); // 写库更新 level
        return new UpgradeResult(true, 0, updated); // retcode=0：升级成功
    }
}
