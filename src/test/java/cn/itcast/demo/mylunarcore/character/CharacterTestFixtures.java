package cn.itcast.demo.mylunarcore.character;

import cn.itcast.demo.mylunarcore.model.AvatarEntity;
import cn.itcast.demo.mylunarcore.model.AvatarTalentEntity;

final /**
 * CharacterTestFixtures。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code CharacterTestFixtures}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
class CharacterTestFixtures {

    static final int PLAYER_ID = 1001;
    static final int AVATAR_ID = 1001;
    static final long PLAYER_UID = 1001L;

    private CharacterTestFixtures() {
    }

    static AvatarEntity avatar(int avatarId, int level, long exp, int promotion, int rank) {
        AvatarEntity entity = new AvatarEntity();
        entity.setPlayerId(PLAYER_ID);
        entity.setAvatarId(avatarId);
        entity.setLevel(level);
        entity.setExp(exp);
        entity.setPromotion(promotion);
        entity.setRank(rank);
        return entity;
    }

    static AvatarTalentEntity talent(int talentId, int level, boolean activated) {
        AvatarTalentEntity entity = new AvatarTalentEntity();
        entity.setPlayerId(PLAYER_ID);
        entity.setAvatarId(AVATAR_ID);
        entity.setTalentId(talentId);
        entity.setLevel(level);
        entity.setActivated(activated);
        return entity;
    }
}
