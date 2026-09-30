package cn.itcast.demo.mylunarcore.character;

import cn.itcast.demo.mylunarcore.model.AvatarEntity;
import cn.itcast.demo.mylunarcore.repo.AvatarRepository;
import cn.itcast.demo.mylunarcore.skin.SkinOwnershipService;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CharacterCreationApplicationService 创角服务测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code CharacterCreationApplicationServiceTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("CharacterCreationApplicationService 创角服务测试")
class CharacterCreationApplicationServiceTest {

    private static final Logger log = LoggerFactory.getLogger(CharacterCreationApplicationServiceTest.class);

    private static final int PLAYER_ID = CharacterTestFixtures.PLAYER_ID;
    private static final int AVATAR_ID = CharacterTestFixtures.AVATAR_ID;

    private AvatarRepository avatarRepository;
    private SkinOwnershipService skinOwnershipService;
    private CharacterCreationApplicationService service;

    @BeforeEach
    void setUp() {
        avatarRepository = mock(AvatarRepository.class);
        skinOwnershipService = mock(SkinOwnershipService.class);
        when(skinOwnershipService.grantDefaultSkins(anyInt(), anyInt())).thenReturn(8001001);
        service = new CharacterCreationApplicationService(avatarRepository, skinOwnershipService);
        log.info("创角服务初始化: playerId={}, defaultStarterAvatarId=1001", PLAYER_ID);
    }

    /**
     * 验证点：合法昵称应创角成功并写入默认 avatar。
     * <p>测试方法 {@code createShouldSucceedWithValidNickname}：
     * <ul>
     *   <li>{@code when(avatarRepository.hasNickname(PLAYER_ID)).thenReturn(false);}</li>
     *   <li>{@code when(avatarRepository.updateNickname(PLAYER_ID, "开拓者")).thenReturn(1);}</li>
     *   <li>{@code when(avatarRepository.findAvatar(PLAYER_ID, AVATAR_ID)).thenReturn(created);}</li>
     *   <li>{@code assertTrue(result.success());}</li>
     *   <li>{@code assertEquals(0, result.retcode());}</li>
     *   <li>{@code assertEquals("开拓者", result.nickname());}</li>
     * </ul>
     */
    @Test
    @DisplayName("合法昵称应创角成功并写入默认 avatar")
    void createShouldSucceedWithValidNickname() {
        AvatarEntity created = CharacterTestFixtures.avatar(AVATAR_ID, 1, 0, 0, 0);
        when(avatarRepository.hasNickname(PLAYER_ID)).thenReturn(false);
        when(avatarRepository.updateNickname(PLAYER_ID, "开拓者")).thenReturn(1);
        when(avatarRepository.findAvatar(PLAYER_ID, AVATAR_ID)).thenReturn(created);

        CharacterCreationApplicationService.CreateResult result =
                service.create(PLAYER_ID, " 开拓者 ", 0);

        log.info("创角成功校验: playerId={}, success={}, retcode={}, nickname={}, avatarId={}, level={}",
                PLAYER_ID, result.success(), result.retcode(), result.nickname(),
                result.avatar() != null ? result.avatar().getAvatarId() : null,
                result.avatar() != null ? result.avatar().getLevel() : null);
        assertTrue(result.success());
        assertEquals(0, result.retcode());
        assertEquals("开拓者", result.nickname());
        assertEquals(AVATAR_ID, result.avatar().getAvatarId());
        verify(skinOwnershipService).grantDefaultSkins(PLAYER_ID, AVATAR_ID);
        verify(avatarRepository).insertAvatar(PLAYER_ID, AVATAR_ID, 8001001);
    }

    /**
     * 验证点：已创角应返回 retcode=2。
     * <p>测试方法 {@code createShouldFailWhenAlreadyCreated}：
     * <ul>
     *   <li>{@code when(avatarRepository.hasNickname(PLAYER_ID)).thenReturn(true);}</li>
     *   <li>{@code assertFalse(result.success());}</li>
     *   <li>{@code assertEquals(2, result.retcode());}</li>
     *   <li>{@code assertNull(result.avatar());}</li>
     *   <li>{@code verify(avatarRepository, never()).updateNickname(anyInt(), anyString());}</li>
     *   <li>{@code verify(avatarRepository, never()).insertAvatar(anyInt(), anyInt(), anyInt());}</li>
     * </ul>
     */
    @Test
    @DisplayName("已创角应返回 retcode=2")
    void createShouldFailWhenAlreadyCreated() {
        when(avatarRepository.hasNickname(PLAYER_ID)).thenReturn(true);

        CharacterCreationApplicationService.CreateResult result =
                service.create(PLAYER_ID, "开拓者", AVATAR_ID);

        log.info("重复创角校验: playerId={}, success={}, retcode={}, nickname={}",
                PLAYER_ID, result.success(), result.retcode(), result.nickname());
        assertFalse(result.success());
        assertEquals(2, result.retcode());
        assertNull(result.avatar());
        verify(avatarRepository, never()).updateNickname(anyInt(), anyString());
        verify(avatarRepository, never()).insertAvatar(anyInt(), anyInt(), anyInt());
    }

    /**
     * 验证点：非法昵称应返回 retcode=3。
     * <p>测试方法 {@code createShouldFailWhenNicknameInvalid}：
     * <ul>
     *   <li>{@code when(avatarRepository.hasNickname(PLAYER_ID)).thenReturn(false);}</li>
     *   <li>{@code assertEquals(3, blank.retcode());}</li>
     *   <li>{@code assertEquals(3, tooLong.retcode());}</li>
     *   <li>{@code assertFalse(blank.success());}</li>
     *   <li>{@code assertFalse(tooLong.success());}</li>
     *   <li>{@code verify(avatarRepository, never()).insertAvatar(anyInt(), anyInt(), anyInt());}</li>
     * </ul>
     */
    @Test
    @DisplayName("非法昵称应返回 retcode=3")
    void createShouldFailWhenNicknameInvalid() {
        when(avatarRepository.hasNickname(PLAYER_ID)).thenReturn(false);

        CharacterCreationApplicationService.CreateResult blank =
                service.create(PLAYER_ID, "   ", AVATAR_ID);
        CharacterCreationApplicationService.CreateResult tooLong =
                service.create(PLAYER_ID, "abcdefghijabcdefghijk", AVATAR_ID);

        log.info("非法昵称校验: blankRetcode={}, blankSuccess={}, tooLongLen={}, tooLongRetcode={}",
                blank.retcode(), blank.success(), "abcdefghijabcdefghijk".length(), tooLong.retcode());
        assertEquals(3, blank.retcode());
        assertEquals(3, tooLong.retcode());
        assertFalse(blank.success());
        assertFalse(tooLong.success());
        verify(avatarRepository, never()).insertAvatar(anyInt(), anyInt(), anyInt());
    }

    /**
     * 验证点：昵称更新失败应返回 retcode=4。
     * <p>测试方法 {@code createShouldFailWhenNicknameUpdateMisses}：
     * <ul>
     *   <li>{@code when(avatarRepository.hasNickname(PLAYER_ID)).thenReturn(false);}</li>
     *   <li>{@code when(avatarRepository.updateNickname(eq(PLAYER_ID), eq("开拓者"))).thenReturn(0);}</li>
     *   <li>{@code assertFalse(result.success());}</li>
     *   <li>{@code assertEquals(4, result.retcode());}</li>
     *   <li>{@code verify(avatarRepository, never()).insertAvatar(anyInt(), anyInt(), anyInt());}</li>
     * </ul>
     */
    @Test
    @DisplayName("昵称更新失败应返回 retcode=4")
    void createShouldFailWhenNicknameUpdateMisses() {
        when(avatarRepository.hasNickname(PLAYER_ID)).thenReturn(false);
        when(avatarRepository.updateNickname(eq(PLAYER_ID), eq("开拓者"))).thenReturn(0);

        CharacterCreationApplicationService.CreateResult result =
                service.create(PLAYER_ID, "开拓者", AVATAR_ID);

        log.info("昵称写库失败校验: playerId={}, success={}, retcode={}, updateRows=0",
                PLAYER_ID, result.success(), result.retcode());
        assertFalse(result.success());
        assertEquals(4, result.retcode());
        verify(avatarRepository, never()).insertAvatar(anyInt(), anyInt(), anyInt());
    }

    /**
     * 验证点：指定 starterAvatarId 应发放对应 avatar。
     * <p>测试方法 {@code createShouldUseCustomStarterAvatarId}：
     * <ul>
     *   <li>{@code when(avatarRepository.hasNickname(PLAYER_ID)).thenReturn(false);}</li>
     *   <li>{@code when(avatarRepository.updateNickname(PLAYER_ID, "星穹")).thenReturn(1);}</li>
     *   <li>{@code when(avatarRepository.findAvatar(PLAYER_ID, customAvatarId)).thenReturn(created);}</li>
     *   <li>{@code when(skinOwnershipService.grantDefaultSkins(PLAYER_ID, customAvatarId)).thenReturn(0);}</li>
     *   <li>{@code assertTrue(result.success());}</li>
     *   <li>{@code assertEquals(0, result.retcode());}</li>
     * </ul>
     */
    @Test
    @DisplayName("指定 starterAvatarId 应发放对应 avatar")
    void createShouldUseCustomStarterAvatarId() {
        int customAvatarId = 1102;
        AvatarEntity created = CharacterTestFixtures.avatar(customAvatarId, 1, 0, 0, 0);
        when(avatarRepository.hasNickname(PLAYER_ID)).thenReturn(false);
        when(avatarRepository.updateNickname(PLAYER_ID, "星穹")).thenReturn(1);
        when(avatarRepository.findAvatar(PLAYER_ID, customAvatarId)).thenReturn(created);
        when(skinOwnershipService.grantDefaultSkins(PLAYER_ID, customAvatarId)).thenReturn(0);

        CharacterCreationApplicationService.CreateResult result =
                service.create(PLAYER_ID, "星穹", customAvatarId);

        log.info("自定义初始角色校验: playerId={}, starterAvatarId={}, success={}, retcode={}, avatarId={}",
                PLAYER_ID, customAvatarId, result.success(), result.retcode(),
                result.avatar().getAvatarId());
        assertTrue(result.success());
        assertEquals(0, result.retcode());
        assertEquals(customAvatarId, result.avatar().getAvatarId());
        verify(avatarRepository).insertAvatar(PLAYER_ID, customAvatarId, 0);
    }
}
