package cn.itcast.demo.mylunarcore.character;

import cn.itcast.demo.mylunarcore.model.AvatarEntity;
import cn.itcast.demo.mylunarcore.repo.AvatarRepository;
import cn.itcast.demo.mylunarcore.skin.SkinOwnershipService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 角色创建应用服务。
 * retcode：0 成功；2 已创角；3 昵称非法；4 昵称更新失败。
 * 创角成功后授予该角色默认皮肤。
 */
@Service
public class CharacterCreationApplicationService {

    public record CreateResult(boolean success, int retcode, String nickname, AvatarEntity avatar) {}

    private static final int DEFAULT_STARTER_AVATAR = 1001;

    private final AvatarRepository avatarRepository;
    private final SkinOwnershipService skinOwnershipService;

    public CharacterCreationApplicationService(AvatarRepository avatarRepository,
                                               SkinOwnershipService skinOwnershipService) {
        this.avatarRepository = avatarRepository;
        this.skinOwnershipService = skinOwnershipService;
    }

    @Transactional
    public CreateResult create(int playerId, String nickname, int starterAvatarId) {
        if (avatarRepository.hasNickname(playerId)) {
            return new CreateResult(false, 2, nickname, null);
        }
        if (nickname == null || nickname.isBlank() || nickname.length() > 20) {
            return new CreateResult(false, 3, nickname, null);
        }
        int avatarId = starterAvatarId > 0 ? starterAvatarId : DEFAULT_STARTER_AVATAR;
        if (avatarRepository.updateNickname(playerId, nickname.trim()) <= 0) {
            return new CreateResult(false, 4, nickname, null);
        }
        int defaultSkinId = skinOwnershipService.grantDefaultSkins(playerId, avatarId);
        avatarRepository.insertAvatar(playerId, avatarId, defaultSkinId);
        AvatarEntity avatar = avatarRepository.findAvatar(playerId, avatarId);
        return new CreateResult(true, 0, nickname.trim(), avatar);
    }
}
