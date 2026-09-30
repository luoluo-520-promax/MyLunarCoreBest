package cn.itcast.demo.mylunarcore.character;

import cn.itcast.demo.mylunarcore.model.AvatarEntity;
import cn.itcast.demo.mylunarcore.repo.AvatarRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CharacterProgressionApplicationService 角色成长测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code CharacterProgressionApplicationServiceTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("CharacterProgressionApplicationService 角色成长测试")
class CharacterProgressionApplicationServiceTest {

    private static final Logger log = LoggerFactory.getLogger(CharacterProgressionApplicationServiceTest.class);

    private static final int PLAYER_ID = CharacterTestFixtures.PLAYER_ID;
    private static final int AVATAR_ID = CharacterTestFixtures.AVATAR_ID;

    private AvatarRepository avatarRepository;
    private CharacterProgressionApplicationService service;

    @BeforeEach
    void setUp() {
        avatarRepository = mock(AvatarRepository.class);
        service = new CharacterProgressionApplicationService(avatarRepository);
        log.info("成长服务初始化: playerId={}, avatarId={}, expPerLevel=1000, maxLevel=80",
                PLAYER_ID, AVATAR_ID);
    }

    /**
     * 验证点：经验足够时应升级并扣除阈值经验。
     * <p>测试方法 {@code addAvatarExpShouldLevelUp}：
     * <ul>
     *   <li>{@code when(avatarRepository.findAvatar(PLAYER_ID, AVATAR_ID)).thenReturn(avatar);}</li>
     *   <li>{@code assertTrue(result.leveledUp());}</li>
     *   <li>{@code assertEquals(11, result.newLevel());}</li>
     *   <li>{@code assertEquals(300L, result.newExp());}</li>
     *   <li>{@code verify(avatarRepository).updateAvatarProgress(PLAYER_ID, AVATAR_ID, 11, 300L, 0);}</li>
     * </ul>
     */
    @Test
    @DisplayName("经验足够时应升级并扣除阈值经验")
    void addAvatarExpShouldLevelUp() {
        AvatarEntity avatar = CharacterTestFixtures.avatar(AVATAR_ID, 10, 800, 0, 0);
        when(avatarRepository.findAvatar(PLAYER_ID, AVATAR_ID)).thenReturn(avatar);

        CharacterProgressionApplicationService.ExpResult result =
                service.addAvatarExp(PLAYER_ID, AVATAR_ID, 500);

        // 800+500=1300 → 升 1 级剩余 300，level=11
        log.info("升级经验校验: playerId={}, avatarId={}, expGain=500, leveledUp={}, newLevel={}, newExp={}",
                PLAYER_ID, AVATAR_ID, result.leveledUp(), result.newLevel(), result.newExp());
        assertTrue(result.leveledUp());
        assertEquals(11, result.newLevel());
        assertEquals(300L, result.newExp());
        verify(avatarRepository).updateAvatarProgress(PLAYER_ID, AVATAR_ID, 11, 300L, 0);
    }

    /**
     * 验证点：大额经验应可连升多级。
     * <p>测试方法 {@code addAvatarExpShouldMultiLevel}：
     * <ul>
     *   <li>{@code when(avatarRepository.findAvatar(PLAYER_ID, AVATAR_ID)).thenReturn(avatar);}</li>
     *   <li>{@code assertTrue(result.leveledUp());}</li>
     *   <li>{@code assertEquals(8, result.newLevel());}</li>
     *   <li>{@code assertEquals(500L, result.newExp());}</li>
     *   <li>{@code verify(avatarRepository).updateAvatarProgress(PLAYER_ID, AVATAR_ID, 8, 500L, 1);}</li>
     * </ul>
     */
    @Test
    @DisplayName("大额经验应可连升多级")
    void addAvatarExpShouldMultiLevel() {
        AvatarEntity avatar = CharacterTestFixtures.avatar(AVATAR_ID, 5, 0, 1, 0);
        when(avatarRepository.findAvatar(PLAYER_ID, AVATAR_ID)).thenReturn(avatar);

        CharacterProgressionApplicationService.ExpResult result =
                service.addAvatarExp(PLAYER_ID, AVATAR_ID, 3500);

        // 3500 经验 → 升 3 级剩余 500，level=8
        log.info("连升校验: expGain=3500, leveledUp={}, newLevel={}, newExp={}, promotionKept={}",
                result.leveledUp(), result.newLevel(), result.newExp(), 1);
        assertTrue(result.leveledUp());
        assertEquals(8, result.newLevel());
        assertEquals(500L, result.newExp());
        verify(avatarRepository).updateAvatarProgress(PLAYER_ID, AVATAR_ID, 8, 500L, 1);
    }

    /**
     * 验证点：角色不存在或非法经验应不写库。
     * <p>测试方法 {@code addAvatarExpShouldSkipInvalidCases}：
     * <ul>
     *   <li>{@code when(avatarRepository.findAvatar(PLAYER_ID, AVATAR_ID)).thenReturn(null);}</li>
     *   <li>{@code when(avatarRepository.findAvatar(PLAYER_ID, AVATAR_ID)).thenReturn(avatar);}</li>
     *   <li>{@code assertFalse(missing.leveledUp());}</li>
     *   <li>{@code assertEquals(0, missing.newLevel());}</li>
     *   <li>{@code assertEquals(0L, missing.newExp());}</li>
     *   <li>{@code assertFalse(zeroGain.leveledUp());}</li>
     * </ul>
     */
    @Test
    @DisplayName("角色不存在或非法经验应不写库")
    void addAvatarExpShouldSkipInvalidCases() {
        when(avatarRepository.findAvatar(PLAYER_ID, AVATAR_ID)).thenReturn(null);
        CharacterProgressionApplicationService.ExpResult missing =
                service.addAvatarExp(PLAYER_ID, AVATAR_ID, 100);

        AvatarEntity avatar = CharacterTestFixtures.avatar(AVATAR_ID, 10, 100, 0, 0);
        when(avatarRepository.findAvatar(PLAYER_ID, AVATAR_ID)).thenReturn(avatar);
        CharacterProgressionApplicationService.ExpResult zeroGain =
                service.addAvatarExp(PLAYER_ID, AVATAR_ID, 0);

        log.info("非法经验校验: missingLevel={}, missingExp={}, zeroGainLeveledUp={}, zeroGainLevel={}, zeroGainExp={}",
                missing.newLevel(), missing.newExp(),
                zeroGain.leveledUp(), zeroGain.newLevel(), zeroGain.newExp());
        assertFalse(missing.leveledUp());
        assertEquals(0, missing.newLevel());
        assertEquals(0L, missing.newExp());
        assertFalse(zeroGain.leveledUp());
        assertEquals(10, zeroGain.newLevel());
        assertEquals(100L, zeroGain.newExp());
        verify(avatarRepository, never()).updateAvatarProgress(anyInt(), anyInt(), anyInt(), anyLong(), anyInt());
    }

    /**
     * 验证点：满足等级门槛时突破应成功。
     * <p>测试方法 {@code promoteAvatarShouldSucceedWhenLevelEnough}：
     * <ul>
     *   <li>{@code when(avatarRepository.findAvatar(PLAYER_ID, AVATAR_ID))}</li>
     *   <li>{@code assertTrue(result.success());}</li>
     *   <li>{@code assertEquals(1, result.avatar().getPromotion());}</li>
     *   <li>{@code verify(avatarRepository).updateAvatarProgress(PLAYER_ID, AVATAR_ID, 20, 100L, 1);}</li>
     * </ul>
     */
    @Test
    @DisplayName("满足等级门槛时突破应成功")
    void promoteAvatarShouldSucceedWhenLevelEnough() {
        AvatarEntity before = CharacterTestFixtures.avatar(AVATAR_ID, 20, 100, 0, 0);
        AvatarEntity after = CharacterTestFixtures.avatar(AVATAR_ID, 20, 100, 1, 0);
        when(avatarRepository.findAvatar(PLAYER_ID, AVATAR_ID))
                .thenReturn(before)
                .thenReturn(after);

        CharacterProgressionApplicationService.PromoteResult result =
                service.promoteAvatar(PLAYER_ID, AVATAR_ID);

        log.info("突破成功校验: playerId={}, avatarId={}, success={}, level={}, promotionBefore=0, promotionAfter={}",
                PLAYER_ID, AVATAR_ID, result.success(),
                result.avatar() != null ? result.avatar().getLevel() : null,
                result.avatar() != null ? result.avatar().getPromotion() : null);
        assertTrue(result.success());
        assertEquals(1, result.avatar().getPromotion());
        verify(avatarRepository).updateAvatarProgress(PLAYER_ID, AVATAR_ID, 20, 100L, 1);
    }

    /**
     * 验证点：等级不足或已满突破应失败。
     * <p>测试方法 {@code promoteAvatarShouldFailWhenConditionNotMet}：
     * <ul>
     *   <li>{@code when(avatarRepository.findAvatar(PLAYER_ID, AVATAR_ID)).thenReturn(lowLevel);}</li>
     *   <li>{@code when(avatarRepository.findAvatar(PLAYER_ID, AVATAR_ID)).thenReturn(maxPromo);}</li>
     *   <li>{@code when(avatarRepository.findAvatar(PLAYER_ID, AVATAR_ID)).thenReturn(null);}</li>
     *   <li>{@code assertFalse(underLevel.success());}</li>
     *   <li>{@code assertEquals(0, underLevel.avatar().getPromotion());}</li>
     *   <li>{@code assertFalse(maxed.success());}</li>
     * </ul>
     */
    @Test
    @DisplayName("等级不足或已满突破应失败")
    void promoteAvatarShouldFailWhenConditionNotMet() {
        AvatarEntity lowLevel = CharacterTestFixtures.avatar(AVATAR_ID, 19, 0, 0, 0);
        when(avatarRepository.findAvatar(PLAYER_ID, AVATAR_ID)).thenReturn(lowLevel);
        CharacterProgressionApplicationService.PromoteResult underLevel =
                service.promoteAvatar(PLAYER_ID, AVATAR_ID);

        AvatarEntity maxPromo = CharacterTestFixtures.avatar(AVATAR_ID, 80, 0, 6, 0);
        when(avatarRepository.findAvatar(PLAYER_ID, AVATAR_ID)).thenReturn(maxPromo);
        CharacterProgressionApplicationService.PromoteResult maxed =
                service.promoteAvatar(PLAYER_ID, AVATAR_ID);

        when(avatarRepository.findAvatar(PLAYER_ID, AVATAR_ID)).thenReturn(null);
        CharacterProgressionApplicationService.PromoteResult missing =
                service.promoteAvatar(PLAYER_ID, AVATAR_ID);

        log.info("突破失败校验: underLevelSuccess={}, underLevelRequired=20, maxedSuccess={}, maxPromotion=6, missingSuccess={}, missingAvatarNull={}",
                underLevel.success(), maxed.success(), missing.success(), missing.avatar() == null);
        assertFalse(underLevel.success());
        assertEquals(0, underLevel.avatar().getPromotion());
        assertFalse(maxed.success());
        assertEquals(6, maxed.avatar().getPromotion());
        assertFalse(missing.success());
        assertNull(missing.avatar());
        verify(avatarRepository, never()).updateAvatarProgress(anyInt(), anyInt(), anyInt(), anyLong(), anyInt());
    }
}
