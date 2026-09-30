package cn.itcast.demo.mylunarcore.character;

import cn.itcast.demo.mylunarcore.model.AvatarEntity;
import cn.itcast.demo.mylunarcore.model.AvatarTalentEntity;
import cn.itcast.demo.mylunarcore.player.DataChangeScope;
import cn.itcast.demo.mylunarcore.player.PlayerContextResolver;
import cn.itcast.demo.mylunarcore.player.PlayerDataSyncService;
import cn.itcast.demo.mylunarcore.protocol.CharacterSystemProto;
import cn.itcast.demo.mylunarcore.repo.AvatarRepository;
import io.netty.channel.Channel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * CharacterNettyService 角色协议服务测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code CharacterNettyServiceTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("CharacterNettyService 角色协议服务测试")
class CharacterNettyServiceTest {

    private static final Logger log = LoggerFactory.getLogger(CharacterNettyServiceTest.class);

    private static final int PLAYER_ID = CharacterTestFixtures.PLAYER_ID;
    private static final long PLAYER_UID = CharacterTestFixtures.PLAYER_UID;
    private static final int AVATAR_ID = CharacterTestFixtures.AVATAR_ID;

    private CharacterCreationApplicationService creationService;
    private CharacterProgressionApplicationService progressionService;
    private AttributeCalculator attributeCalculator;
    private AvatarRepository avatarRepository;
    private PlayerContextResolver contextResolver;
    private PlayerDataSyncService playerDataSyncService;
    private TalentApplicationService talentApplicationService;
    private CharacterNettyService service;

    @BeforeEach
    void setUp() {
        creationService = mock(CharacterCreationApplicationService.class);
        progressionService = mock(CharacterProgressionApplicationService.class);
        attributeCalculator = new AttributeCalculator();
        avatarRepository = mock(AvatarRepository.class);
        contextResolver = mock(PlayerContextResolver.class);
        playerDataSyncService = mock(PlayerDataSyncService.class);
        talentApplicationService = mock(TalentApplicationService.class);
        service = new CharacterNettyService(
                creationService, progressionService, attributeCalculator,
                avatarRepository, contextResolver, playerDataSyncService, talentApplicationService);
        log.info("角色协议服务初始化: playerId={}, uid={}, avatarId={}", PLAYER_ID, PLAYER_UID, AVATAR_ID);
    }

    /**
     * 验证点：创角成功应返回角色信息并通知 AVATARS。
     * <p>测试方法 {@code handleCreateCharacterShouldSucceed}：
     * <ul>
     *   <li>{@code when(creationService.create(PLAYER_ID, "开拓者", AVATAR_ID))}</li>
     *   <li>{@code assertEquals(0, rsp.getRetcode());}</li>
     *   <li>{@code assertEquals("开拓者", rsp.getNickname());}</li>
     *   <li>{@code assertEquals(AVATAR_ID, rsp.getStarterAvatar().getAvatarId());}</li>
     *   <li>{@code verify(playerDataSyncService).notifyDataChanged(PLAYER_UID, DataChangeScope.AVATARS);}</li>
     * </ul>
     */
    @Test
    @DisplayName("创角成功应返回角色信息并通知 AVATARS")
    void handleCreateCharacterShouldSucceed() {
        AvatarEntity avatar = CharacterTestFixtures.avatar(AVATAR_ID, 1, 0, 0, 0);
        when(creationService.create(PLAYER_ID, "开拓者", AVATAR_ID))
                .thenReturn(new CharacterCreationApplicationService.CreateResult(true, 0, "开拓者", avatar));

        CharacterSystemProto.CreateCharacterScRsp rsp = service.handleCreateCharacter(
                CharacterSystemProto.CreateCharacterCsReq.newBuilder()
                        .setNickname("开拓者")
                        .setStarterAvatarId(AVATAR_ID)
                        .build(),
                loggedInChannel());

        log.info("创角协议校验: retcode={}, nickname={}, starterAvatarId={}, starterLevel={}",
                rsp.getRetcode(), rsp.getNickname(),
                rsp.getStarterAvatar().getAvatarId(), rsp.getStarterAvatar().getLevel());
        assertEquals(0, rsp.getRetcode());
        assertEquals("开拓者", rsp.getNickname());
        assertEquals(AVATAR_ID, rsp.getStarterAvatar().getAvatarId());
        verify(playerDataSyncService).notifyDataChanged(PLAYER_UID, DataChangeScope.AVATARS);
    }

    /**
     * 验证点：未登录创角应返回 retcode=1。
     * <p>测试方法 {@code handleCreateCharacterWithoutLoginShouldFail}：
     * <ul>
     *   <li>{@code assertEquals(1, rsp.getRetcode());}</li>
     *   <li>{@code verify(creationService, never()).create(anyInt(), anyString(), anyInt());}</li>
     * </ul>
     */
    @Test
    @DisplayName("未登录创角应返回 retcode=1")
    void handleCreateCharacterWithoutLoginShouldFail() {
        CharacterSystemProto.CreateCharacterScRsp rsp = service.handleCreateCharacter(
                CharacterSystemProto.CreateCharacterCsReq.newBuilder().setNickname("开拓者").build(),
                loggedOutChannel());

        log.info("未登录创角校验: retcode={}, nicknameEmpty={}",
                rsp.getRetcode(), rsp.getNickname().isEmpty());
        assertEquals(1, rsp.getRetcode());
        verify(creationService, never()).create(anyInt(), anyString(), anyInt());
    }

    /**
     * 验证点：突破成功应回传 avatar 并通知 AVATARS。
     * <p>测试方法 {@code handlePromoteAvatarShouldSucceed}：
     * <ul>
     *   <li>{@code when(progressionService.promoteAvatar(PLAYER_ID, AVATAR_ID))}</li>
     *   <li>{@code assertEquals(0, rsp.getRetcode());}</li>
     *   <li>{@code assertEquals(1, rsp.getAvatar().getPromotion());}</li>
     *   <li>{@code verify(playerDataSyncService).notifyDataChanged(PLAYER_UID, DataChangeScope.AVATARS);}</li>
     * </ul>
     */
    @Test
    @DisplayName("突破成功应回传 avatar 并通知 AVATARS")
    void handlePromoteAvatarShouldSucceed() {
        AvatarEntity promoted = CharacterTestFixtures.avatar(AVATAR_ID, 20, 0, 1, 0);
        when(progressionService.promoteAvatar(PLAYER_ID, AVATAR_ID))
                .thenReturn(new CharacterProgressionApplicationService.PromoteResult(true, promoted));

        CharacterSystemProto.PromoteAvatarScRsp rsp = service.handlePromoteAvatar(
                CharacterSystemProto.PromoteAvatarCsReq.newBuilder().setAvatarId(AVATAR_ID).build(),
                loggedInChannel());

        log.info("突破协议校验: retcode={}, avatarId={}, level={}, promotion={}, rank={}",
                rsp.getRetcode(), rsp.getAvatar().getAvatarId(),
                rsp.getAvatar().getLevel(), rsp.getAvatar().getPromotion(), rsp.getAvatar().getRank());
        assertEquals(0, rsp.getRetcode());
        assertEquals(1, rsp.getAvatar().getPromotion());
        verify(playerDataSyncService).notifyDataChanged(PLAYER_UID, DataChangeScope.AVATARS);
    }

    /**
     * 验证点：突破失败应返回 retcode=2。
     * <p>测试方法 {@code handlePromoteAvatarShouldFail}：
     * <ul>
     *   <li>{@code when(progressionService.promoteAvatar(PLAYER_ID, AVATAR_ID))}</li>
     *   <li>{@code assertEquals(2, rsp.getRetcode());}</li>
     *   <li>{@code verify(playerDataSyncService, never()).notifyDataChanged(anyLong(), any());}</li>
     * </ul>
     */
    @Test
    @DisplayName("突破失败应返回 retcode=2")
    void handlePromoteAvatarShouldFail() {
        when(progressionService.promoteAvatar(PLAYER_ID, AVATAR_ID))
                .thenReturn(new CharacterProgressionApplicationService.PromoteResult(false, null));

        CharacterSystemProto.PromoteAvatarScRsp rsp = service.handlePromoteAvatar(
                CharacterSystemProto.PromoteAvatarCsReq.newBuilder().setAvatarId(AVATAR_ID).build(),
                loggedInChannel());

        log.info("突破失败协议校验: avatarId={}, retcode={}", AVATAR_ID, rsp.getRetcode());
        assertEquals(2, rsp.getRetcode());
        verify(playerDataSyncService, never()).notifyDataChanged(anyLong(), any());
    }

    /**
     * 验证点：查询属性成功应返回四维面板。
     * <p>测试方法 {@code handleGetAvatarAttributesShouldReturnPanel}：
     * <ul>
     *   <li>{@code when(avatarRepository.findAvatar(PLAYER_ID, AVATAR_ID)).thenReturn(avatar);}</li>
     *   <li>{@code assertEquals(0, rsp.getRetcode());}</li>
     *   <li>{@code assertEquals(AVATAR_ID, rsp.getAttributes().getAvatarId());}</li>
     *   <li>{@code assertEquals(3450, rsp.getAttributes().getHp());}</li>
     *   <li>{@code assertEquals(345, rsp.getAttributes().getAtk());}</li>
     *   <li>{@code assertEquals(173, rsp.getAttributes().getDef());}</li>
     * </ul>
     */
    @Test
    @DisplayName("查询属性成功应返回四维面板")
    void handleGetAvatarAttributesShouldReturnPanel() {
        AvatarEntity avatar = CharacterTestFixtures.avatar(AVATAR_ID, 20, 0, 2, 1);
        when(avatarRepository.findAvatar(PLAYER_ID, AVATAR_ID)).thenReturn(avatar);

        CharacterSystemProto.GetAvatarAttributesScRsp rsp = service.handleGetAvatarAttributes(
                CharacterSystemProto.GetAvatarAttributesCsReq.newBuilder().setAvatarId(AVATAR_ID).build(),
                loggedInChannel());

        log.info("属性协议校验: retcode={}, avatarId={}, hp={}, atk={}, def={}, spd={}",
                rsp.getRetcode(), rsp.getAttributes().getAvatarId(),
                rsp.getAttributes().getHp(), rsp.getAttributes().getAtk(),
                rsp.getAttributes().getDef(), rsp.getAttributes().getSpd());
        assertEquals(0, rsp.getRetcode());
        assertEquals(AVATAR_ID, rsp.getAttributes().getAvatarId());
        assertEquals(3450, rsp.getAttributes().getHp());
        assertEquals(345, rsp.getAttributes().getAtk());
        assertEquals(173, rsp.getAttributes().getDef());
        assertEquals(124, rsp.getAttributes().getSpd());
    }

    /**
     * 验证点：查询不存在角色属性应返回 retcode=2。
     * <p>测试方法 {@code handleGetAvatarAttributesMissingShouldFail}：
     * <ul>
     *   <li>{@code when(avatarRepository.findAvatar(PLAYER_ID, AVATAR_ID)).thenReturn(null);}</li>
     *   <li>{@code assertEquals(2, rsp.getRetcode());}</li>
     * </ul>
     */
    @Test
    @DisplayName("查询不存在角色属性应返回 retcode=2")
    void handleGetAvatarAttributesMissingShouldFail() {
        when(avatarRepository.findAvatar(PLAYER_ID, AVATAR_ID)).thenReturn(null);

        CharacterSystemProto.GetAvatarAttributesScRsp rsp = service.handleGetAvatarAttributes(
                CharacterSystemProto.GetAvatarAttributesCsReq.newBuilder().setAvatarId(AVATAR_ID).build(),
                loggedInChannel());

        log.info("缺失角色属性校验: avatarId={}, retcode={}", AVATAR_ID, rsp.getRetcode());
        assertEquals(2, rsp.getRetcode());
    }

    /**
     * 验证点：天赋升级成功应回传 TalentInfo。
     * <p>测试方法 {@code handleUpgradeTalentShouldSucceed}：
     * <ul>
     *   <li>{@code when(talentApplicationService.upgrade(PLAYER_ID, AVATAR_ID, 201, 3))}</li>
     *   <li>{@code assertEquals(0, rsp.getRetcode());}</li>
     *   <li>{@code assertEquals(201, rsp.getTalent().getTalentId());}</li>
     *   <li>{@code assertEquals(3, rsp.getTalent().getLevel());}</li>
     *   <li>{@code assertTrue(rsp.getTalent().getActivated());}</li>
     * </ul>
     */
    @Test
    @DisplayName("天赋升级成功应回传 TalentInfo")
    void handleUpgradeTalentShouldSucceed() {
        AvatarTalentEntity talent = CharacterTestFixtures.talent(201, 3, true);
        when(talentApplicationService.upgrade(PLAYER_ID, AVATAR_ID, 201, 3))
                .thenReturn(new TalentApplicationService.UpgradeResult(true, 0, talent));

        CharacterSystemProto.UpgradeTalentScRsp rsp = service.handleUpgradeTalent(
                CharacterSystemProto.UpgradeTalentCsReq.newBuilder()
                        .setAvatarId(AVATAR_ID)
                        .setTalentId(201)
                        .setTargetLevel(3)
                        .build(),
                loggedInChannel());

        log.info("天赋升级协议校验: retcode={}, talentId={}, level={}, activated={}",
                rsp.getRetcode(), rsp.getTalent().getTalentId(),
                rsp.getTalent().getLevel(), rsp.getTalent().getActivated());
        assertEquals(0, rsp.getRetcode());
        assertEquals(201, rsp.getTalent().getTalentId());
        assertEquals(3, rsp.getTalent().getLevel());
        assertTrue(rsp.getTalent().getActivated());
    }

    /**
     * 验证点：未登录天赋升级应返回 retcode=1。
     * <p>测试方法 {@code handleUpgradeTalentWithoutLoginShouldFail}：
     * <ul>
     *   <li>{@code assertEquals(1, rsp.getRetcode());}</li>
     *   <li>{@code verify(talentApplicationService, never()).upgrade(anyInt(), anyInt(), anyInt(), anyInt());}</li>
     * </ul>
     */
    @Test
    @DisplayName("未登录天赋升级应返回 retcode=1")
    void handleUpgradeTalentWithoutLoginShouldFail() {
        CharacterSystemProto.UpgradeTalentScRsp rsp = service.handleUpgradeTalent(
                CharacterSystemProto.UpgradeTalentCsReq.newBuilder()
                        .setAvatarId(AVATAR_ID)
                        .setTalentId(201)
                        .setTargetLevel(2)
                        .build(),
                loggedOutChannel());

        log.info("未登录天赋升级校验: retcode={}", rsp.getRetcode());
        assertEquals(1, rsp.getRetcode());
        verify(talentApplicationService, never()).upgrade(anyInt(), anyInt(), anyInt(), anyInt());
    }

    /**
     * 验证点：获取天赋列表应填充全部 TalentInfo。
     * <p>测试方法 {@code handleGetTalentListShouldReturnTalents}：
     * <ul>
     *   <li>{@code when(talentApplicationService.listTalents(PLAYER_ID, AVATAR_ID)).thenReturn(List.of(}</li>
     *   <li>{@code assertEquals(0, rsp.getRetcode());}</li>
     *   <li>{@code assertEquals(2, rsp.getTalentsCount());}</li>
     *   <li>{@code assertEquals(201, rsp.getTalents(0).getTalentId());}</li>
     *   <li>{@code assertEquals(4, rsp.getTalents(1).getLevel());}</li>
     * </ul>
     */
    @Test
    @DisplayName("获取天赋列表应填充全部 TalentInfo")
    void handleGetTalentListShouldReturnTalents() {
        when(talentApplicationService.listTalents(PLAYER_ID, AVATAR_ID)).thenReturn(List.of(
                CharacterTestFixtures.talent(201, 1, true),
                CharacterTestFixtures.talent(202, 4, false)));

        CharacterSystemProto.GetTalentListScRsp rsp = service.handleGetTalentList(
                CharacterSystemProto.GetTalentListCsReq.newBuilder().setAvatarId(AVATAR_ID).build(),
                loggedInChannel());

        log.info("天赋列表协议校验: retcode={}, talentCount={}, firstTalentId={}, secondLevel={}, secondActivated={}",
                rsp.getRetcode(), rsp.getTalentsCount(),
                rsp.getTalents(0).getTalentId(),
                rsp.getTalents(1).getLevel(),
                rsp.getTalents(1).getActivated());
        assertEquals(0, rsp.getRetcode());
        assertEquals(2, rsp.getTalentsCount());
        assertEquals(201, rsp.getTalents(0).getTalentId());
        assertEquals(4, rsp.getTalents(1).getLevel());
    }

    private Channel loggedInChannel() {
        Channel channel = mock(Channel.class);
        when(contextResolver.resolvePlayerId(channel)).thenReturn(PLAYER_ID);
        when(contextResolver.resolveUid(channel)).thenReturn(OptionalLong.of(PLAYER_UID));
        log.info("模拟登录 Channel: uid={}, playerId={}", PLAYER_UID, PLAYER_ID);
        return channel;
    }

    private Channel loggedOutChannel() {
        Channel channel = mock(Channel.class);
        when(contextResolver.resolvePlayerId(channel)).thenReturn(0);
        when(contextResolver.resolveUid(channel)).thenReturn(OptionalLong.empty());
        log.info("模拟未登录 Channel: playerId=0");
        return channel;
    }
}
