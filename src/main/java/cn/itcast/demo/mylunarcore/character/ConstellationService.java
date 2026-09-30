package cn.itcast.demo.mylunarcore.character;

import cn.itcast.demo.mylunarcore.model.AvatarEntity;
import cn.itcast.demo.mylunarcore.repo.AvatarRepository;
import org.springframework.stereotype.Service;

/**
 * 命座/星魂：重复抽取自动升层，上限 6。
 */
@Service
public class ConstellationService {

    public static final int MAX_LAYER = 6;

    public record ConstellationResult(boolean applied, boolean isNewConstellation, int previousLayer,
                                      int currentLayer, int characterId) {
        public static ConstellationResult none(int characterId) {
            return new ConstellationResult(false, false, 0, 0, characterId);
        }
    }

    private final AvatarRepository avatarRepository;
    private final CharacterConstellationConfigRepository configRepository;

    public ConstellationService(AvatarRepository avatarRepository,
                                CharacterConstellationConfigRepository configRepository) {
        this.avatarRepository = avatarRepository;
        this.configRepository = configRepository;
    }

    public int currentLayer(int playerId, int characterId) {
        AvatarEntity avatar = avatarRepository.findAvatar(playerId, characterId);
        if (avatar == null) {
            return 0;
        }
        return Math.max(0, Math.min(maxLayer(characterId), avatar.getRank()));
    }

    public int maxLayer(int characterId) {
        return Math.min(MAX_LAYER, configRepository.maxLayer(characterId));
    }

    /**
     * 抽到角色时调用：全新角色 rank=0；重复则 rank+1（不超过上限）。
     */
    public ConstellationResult onAvatarObtained(int playerId, int characterId, boolean isNewItem) {
        if (playerId <= 0 || characterId <= 0) {
            return ConstellationResult.none(characterId);
        }
        AvatarEntity existing = avatarRepository.findAvatar(playerId, characterId);
        int max = maxLayer(characterId);
        if (existing == null) {
            avatarRepository.insertAvatar(playerId, characterId);
            return new ConstellationResult(true, false, 0, 0, characterId);
        }
        if (isNewItem) {
            // 背包标记为新但已有 avatar 实例：视为首次绑定，不升命
            return new ConstellationResult(true, false, existing.getRank(), existing.getRank(), characterId);
        }
        int prev = Math.max(0, existing.getRank());
        if (prev >= max) {
            return new ConstellationResult(false, false, prev, prev, characterId);
        }
        int next = prev + 1;
        avatarRepository.updateRank(playerId, characterId, next);
        return new ConstellationResult(true, true, prev, next, characterId);
    }
}
