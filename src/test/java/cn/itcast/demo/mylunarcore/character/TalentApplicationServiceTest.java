package cn.itcast.demo.mylunarcore.character;

import cn.itcast.demo.mylunarcore.model.AvatarTalentEntity;
import cn.itcast.demo.mylunarcore.repo.AvatarRepository;
import cn.itcast.demo.mylunarcore.repo.AvatarTalentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * TalentApplicationService 天赋服务测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code TalentApplicationServiceTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("TalentApplicationService 天赋服务测试")
class TalentApplicationServiceTest {

    private static final Logger log = LoggerFactory.getLogger(TalentApplicationServiceTest.class);

    private static final int PLAYER_ID = CharacterTestFixtures.PLAYER_ID;
    private static final int AVATAR_ID = CharacterTestFixtures.AVATAR_ID;
    private static final int TALENT_ID = 201;

    private AvatarTalentRepository talentRepository;
    private AvatarRepository avatarRepository;
    private TalentApplicationService service;

    @BeforeEach
    void setUp() {
        talentRepository = mock(AvatarTalentRepository.class);
        avatarRepository = mock(AvatarRepository.class);
        service = new TalentApplicationService(talentRepository, avatarRepository);
        log.info("天赋服务初始化: playerId={}, avatarId={}, talentId={}",
                PLAYER_ID, AVATAR_ID, TALENT_ID);
    }

    /**
     * 验证点：listTalents 应返回角色全部天赋。
     * <p>测试方法 {@code listTalentsShouldReturnAll}：
     * <ul>
     *   <li>{@code when(talentRepository.listByAvatar(PLAYER_ID, AVATAR_ID)).thenReturn(talents);}</li>
     *   <li>{@code assertEquals(2, result.size());}</li>
     *   <li>{@code assertEquals(201, result.get(0).getTalentId());}</li>
     *   <li>{@code assertEquals(3, result.get(1).getLevel());}</li>
     * </ul>
     */
    @Test
    @DisplayName("listTalents 应返回角色全部天赋")
    void listTalentsShouldReturnAll() {
        List<AvatarTalentEntity> talents = List.of(
                CharacterTestFixtures.talent(201, 1, true),
                CharacterTestFixtures.talent(202, 3, true));
        when(talentRepository.listByAvatar(PLAYER_ID, AVATAR_ID)).thenReturn(talents);

        List<AvatarTalentEntity> result = service.listTalents(PLAYER_ID, AVATAR_ID);

        log.info("天赋列表校验: playerId={}, avatarId={}, talentCount={}, firstTalentId={}, firstLevel={}",
                PLAYER_ID, AVATAR_ID, result.size(),
                result.get(0).getTalentId(), result.get(0).getLevel());
        assertEquals(2, result.size());
        assertEquals(201, result.get(0).getTalentId());
        assertEquals(3, result.get(1).getLevel());
    }

    /**
     * 验证点：角色不存在升级应返回 retcode=2。
     * <p>测试方法 {@code upgradeShouldFailWhenAvatarMissing}：
     * <ul>
     *   <li>{@code when(avatarRepository.findAvatar(PLAYER_ID, AVATAR_ID)).thenReturn(null);}</li>
     *   <li>{@code assertFalse(result.success());}</li>
     *   <li>{@code assertEquals(2, result.retcode());}</li>
     *   <li>{@code assertNull(result.talent());}</li>
     *   <li>{@code verify(talentRepository, never()).upgrade(anyInt(), anyInt(), anyInt(), anyInt());}</li>
     * </ul>
     */
    @Test
    @DisplayName("角色不存在升级应返回 retcode=2")
    void upgradeShouldFailWhenAvatarMissing() {
        when(avatarRepository.findAvatar(PLAYER_ID, AVATAR_ID)).thenReturn(null);

        TalentApplicationService.UpgradeResult result =
                service.upgrade(PLAYER_ID, AVATAR_ID, TALENT_ID, 2);

        log.info("角色缺失升级校验: playerId={}, avatarId={}, talentId={}, targetLevel=2, success={}, retcode={}",
                PLAYER_ID, AVATAR_ID, TALENT_ID, result.success(), result.retcode());
        assertFalse(result.success());
        assertEquals(2, result.retcode());
        assertNull(result.talent());
        verify(talentRepository, never()).upgrade(anyInt(), anyInt(), anyInt(), anyInt());
    }

    /**
     * 验证点：非法目标等级应返回 retcode=3。
     * <p>测试方法 {@code upgradeShouldFailWhenTargetLevelInvalid}：
     * <ul>
     *   <li>{@code when(avatarRepository.findAvatar(PLAYER_ID, AVATAR_ID))}</li>
     *   <li>{@code assertEquals(3, zero.retcode());}</li>
     *   <li>{@code assertEquals(3, over.retcode());}</li>
     *   <li>{@code verify(talentRepository, never()).upgrade(anyInt(), anyInt(), anyInt(), anyInt());}</li>
     * </ul>
     */
    @Test
    @DisplayName("非法目标等级应返回 retcode=3")
    void upgradeShouldFailWhenTargetLevelInvalid() {
        when(avatarRepository.findAvatar(PLAYER_ID, AVATAR_ID))
                .thenReturn(CharacterTestFixtures.avatar(AVATAR_ID, 10, 0, 0, 0));

        TalentApplicationService.UpgradeResult zero =
                service.upgrade(PLAYER_ID, AVATAR_ID, TALENT_ID, 0);
        TalentApplicationService.UpgradeResult over =
                service.upgrade(PLAYER_ID, AVATAR_ID, TALENT_ID, 11);

        log.info("非法目标等级校验: targetLevel=0 retcode={}, targetLevel=11 retcode={}",
                zero.retcode(), over.retcode());
        assertEquals(3, zero.retcode());
        assertEquals(3, over.retcode());
        verify(talentRepository, never()).upgrade(anyInt(), anyInt(), anyInt(), anyInt());
    }

    /**
     * 验证点：已不低于目标等级应返回 retcode=4（幂等）。
     * <p>测试方法 {@code upgradeShouldFailWhenAlreadyReached}：
     * <ul>
     *   <li>{@code when(avatarRepository.findAvatar(PLAYER_ID, AVATAR_ID))}</li>
     *   <li>{@code when(talentRepository.find(PLAYER_ID, AVATAR_ID, TALENT_ID)).thenReturn(current);}</li>
     *   <li>{@code assertFalse(result.success());}</li>
     *   <li>{@code assertEquals(4, result.retcode());}</li>
     *   <li>{@code assertEquals(5, result.talent().getLevel());}</li>
     *   <li>{@code verify(talentRepository, never()).upgrade(anyInt(), anyInt(), anyInt(), anyInt());}</li>
     * </ul>
     */
    @Test
    @DisplayName("已不低于目标等级应返回 retcode=4（幂等）")
    void upgradeShouldFailWhenAlreadyReached() {
        when(avatarRepository.findAvatar(PLAYER_ID, AVATAR_ID))
                .thenReturn(CharacterTestFixtures.avatar(AVATAR_ID, 10, 0, 0, 0));
        AvatarTalentEntity current = CharacterTestFixtures.talent(TALENT_ID, 5, true);
        when(talentRepository.find(PLAYER_ID, AVATAR_ID, TALENT_ID)).thenReturn(current);

        TalentApplicationService.UpgradeResult result =
                service.upgrade(PLAYER_ID, AVATAR_ID, TALENT_ID, 4);

        log.info("幂等升级拒绝校验: currentLevel={}, targetLevel=4, success={}, retcode={}, returnedLevel={}",
                current.getLevel(), result.success(), result.retcode(),
                result.talent() != null ? result.talent().getLevel() : null);
        assertFalse(result.success());
        assertEquals(4, result.retcode());
        assertEquals(5, result.talent().getLevel());
        verify(talentRepository, never()).upgrade(anyInt(), anyInt(), anyInt(), anyInt());
    }

    /**
     * 验证点：合法升级应写库并返回成功。
     * <p>测试方法 {@code upgradeShouldSucceedAndPersist}：
     * <ul>
     *   <li>{@code when(avatarRepository.findAvatar(PLAYER_ID, AVATAR_ID))}</li>
     *   <li>{@code when(talentRepository.find(PLAYER_ID, AVATAR_ID, TALENT_ID))}</li>
     *   <li>{@code when(talentRepository.upgrade(PLAYER_ID, AVATAR_ID, TALENT_ID, 5)).thenReturn(updated);}</li>
     *   <li>{@code assertTrue(result.success());}</li>
     *   <li>{@code assertEquals(0, result.retcode());}</li>
     *   <li>{@code assertEquals(5, result.talent().getLevel());}</li>
     * </ul>
     */
    @Test
    @DisplayName("合法升级应写库并返回成功")
    void upgradeShouldSucceedAndPersist() {
        when(avatarRepository.findAvatar(PLAYER_ID, AVATAR_ID))
                .thenReturn(CharacterTestFixtures.avatar(AVATAR_ID, 10, 0, 0, 0));
        when(talentRepository.find(PLAYER_ID, AVATAR_ID, TALENT_ID))
                .thenReturn(CharacterTestFixtures.talent(TALENT_ID, 2, true));
        AvatarTalentEntity updated = CharacterTestFixtures.talent(TALENT_ID, 5, true);
        when(talentRepository.upgrade(PLAYER_ID, AVATAR_ID, TALENT_ID, 5)).thenReturn(updated);

        TalentApplicationService.UpgradeResult result =
                service.upgrade(PLAYER_ID, AVATAR_ID, TALENT_ID, 5);

        log.info("天赋升级成功校验: playerId={}, avatarId={}, talentId={}, targetLevel=5, success={}, retcode={}, newLevel={}",
                PLAYER_ID, AVATAR_ID, TALENT_ID, result.success(), result.retcode(),
                result.talent().getLevel());
        assertTrue(result.success());
        assertEquals(0, result.retcode());
        assertEquals(5, result.talent().getLevel());
        verify(talentRepository).upgrade(PLAYER_ID, AVATAR_ID, TALENT_ID, 5);
    }
}
