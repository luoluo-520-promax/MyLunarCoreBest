// 角色成长应用层：经验累加、等级折算与突破（promotion）
package cn.itcast.demo.mylunarcore.character;

import cn.itcast.demo.mylunarcore.model.AvatarEntity; // 含 level、exp、promotion 字段
import cn.itcast.demo.mylunarcore.repo.AvatarRepository; // findAvatar / updateAvatarProgress
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 角色成长编排服务。
 * 集中维护 EXP_PER_LEVEL、MAX_LEVEL、突破门槛，避免魔法数字散落各层。
 */
@Service
public class CharacterProgressionApplicationService {

    /** 加经验后的结果：是否升级、最终等级、当前等级剩余经验。 */
    public record ExpResult(boolean leveledUp, int newLevel, long newExp) {}

    /** 突破结果：成功与否及突破后的 avatar 快照。 */
    public record PromoteResult(boolean success, AvatarEntity avatar) {}

    /** 每升一级需消耗的经验阈值（满此值则 level+1）。 */
    private static final long EXP_PER_LEVEL = 1000L;

    /** 角色可达最高等级 80，达到后经验累加但不继续升级。 */
    private static final int MAX_LEVEL = 80;

    /** 最大突破次数 6（0~6 共 7 档）。 */
    private static final int MAX_PROMOTION = 6;

    /** 头像仓储。 */
    private final AvatarRepository avatarRepository;

    /** 构造器注入 AvatarRepository。 */
    public CharacterProgressionApplicationService(AvatarRepository avatarRepository) {
        this.avatarRepository = avatarRepository;
    }

    /**
     * 为角色增加经验并按 EXP_PER_LEVEL 自动折算等级。
     * 可一次大额经验连升多级；avatar 不存在或 expGain<=0 时不写库。
     */
    @Transactional
    public ExpResult addAvatarExp(int playerId, int avatarId, long expGain) {
        AvatarEntity avatar = avatarRepository.findAvatar(playerId, avatarId); // 读当前 level/exp
        if (avatar == null || expGain <= 0) { // 无角色或非法加经验量
            return new ExpResult(false,
                    avatar == null ? 0 : avatar.getLevel(), // 无角色时等级回 0
                    avatar == null ? 0 : avatar.getExp()); // 无角色时经验回 0
        }
        long exp = avatar.getExp() + expGain; // 累加本次获得经验
        int level = avatar.getLevel(); // 当前等级
        boolean leveledUp = false; // 标记本次是否至少升过一级
        while (level < MAX_LEVEL && exp >= EXP_PER_LEVEL) { // 未达上限且经验够升一级
            exp -= EXP_PER_LEVEL; // 扣除升级所需经验
            level++; // 等级 +1
            leveledUp = true; // 记录发生过升级
        }
        avatarRepository.updateAvatarProgress(playerId, avatarId, level, exp, avatar.getPromotion()); // 只改 level/exp
        return new ExpResult(leveledUp, level, exp); // 返回升级结果快照
    }

    /**
     * 执行突破 promotion+1。
     * 门槛：requiredLevel = 20 + promotion×10；已达 MAX_PROMOTION 或等级不足则失败。
     */
    @Transactional
    public PromoteResult promoteAvatar(int playerId, int avatarId) {
        AvatarEntity avatar = avatarRepository.findAvatar(playerId, avatarId); // 读当前成长状态
        if (avatar == null) { // 角色不存在
            return new PromoteResult(false, null);
        }
        if (avatar.getPromotion() >= MAX_PROMOTION) { // 已达最大突破次数
            return new PromoteResult(false, avatar); // 失败但回传当前快照
        }
        int requiredLevel = 20 + avatar.getPromotion() * 10; // 第0次突破需20级，之后每档+10
        if (avatar.getLevel() < requiredLevel) { // 等级未达突破门槛
            return new PromoteResult(false, avatar);
        }
        int nextPromotion = avatar.getPromotion() + 1; // 突破次数 +1
        avatarRepository.updateAvatarProgress(playerId, avatarId, avatar.getLevel(), avatar.getExp(), nextPromotion); // 仅改 promotion
        AvatarEntity updated = avatarRepository.findAvatar(playerId, avatarId); // 读回突破后完整实体
        return new PromoteResult(true, updated); // 突破成功
    }
}
