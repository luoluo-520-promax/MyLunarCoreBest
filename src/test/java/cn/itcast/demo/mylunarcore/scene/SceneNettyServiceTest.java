package cn.itcast.demo.mylunarcore.scene;

import cn.itcast.demo.mylunarcore.protocol.SceneSystemProto;
import cn.itcast.demo.mylunarcore.repo.MonsterConfigRepository;
import cn.itcast.demo.mylunarcore.repo.NpcConfigRepository;
import cn.itcast.demo.mylunarcore.repo.SceneConfigRepository;
import cn.itcast.demo.mylunarcore.repo.SummonUnitConfigRepository;
import io.netty.channel.Channel;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("SceneNettyService 场景协议服务测试")
class SceneNettyServiceTest {

    private static final Logger log = LoggerFactory.getLogger(SceneNettyServiceTest.class);

    private static final AttributeKey<Long> UID_KEY = AttributeKey.valueOf("playerUid");
    private static final long PLAYER_UID = SceneTestFixtures.PLAYER_UID;

    private SceneManager sceneManager;
    private SceneConfigRepository sceneConfigRepository;
    private MonsterConfigRepository monsterConfigRepository;
    private NpcConfigRepository npcConfigRepository;
    private SummonUnitConfigRepository summonUnitConfigRepository;
    private SceneNettyService service;

    @BeforeEach
    void setUp() {
        sceneManager = new SceneManager();
        sceneConfigRepository = mock(SceneConfigRepository.class);
        monsterConfigRepository = mock(MonsterConfigRepository.class);
        npcConfigRepository = mock(NpcConfigRepository.class);
        summonUnitConfigRepository = mock(SummonUnitConfigRepository.class);
        service = new SceneNettyService(
                sceneManager,
                sceneConfigRepository,
                monsterConfigRepository,
                npcConfigRepository,
                summonUnitConfigRepository);
        log.info("场景协议服务初始化: playerUid={}, planeId={}, floorId={}, entryId={}",
                PLAYER_UID, SceneTestFixtures.PLANE_ID, SceneTestFixtures.FLOOR_ID, SceneTestFixtures.ENTRY_ID);
    }

    @Test
    @DisplayName("进入场景成功应注册上下文并返回实体列表")
    void enterSceneSuccessShouldRegisterContext() {
        stubSceneConfigAndEntities();

        SceneSystemProto.EnterSceneScRsp rsp = service.handleEnterScene(
                SceneSystemProto.EnterSceneCsReq.newBuilder()
                        .setPlaneId(SceneTestFixtures.PLANE_ID)
                        .setFloorId(SceneTestFixtures.FLOOR_ID)
                        .setEntryId(SceneTestFixtures.ENTRY_ID)
                        .setPosX(SceneTestFixtures.POS_X)
                        .setPosY(SceneTestFixtures.POS_Y)
                        .setPosZ(SceneTestFixtures.POS_Z)
                        .build(),
                loggedInChannel(PLAYER_UID));

        SceneContext ctx = sceneManager.getByPlayerUid(PLAYER_UID);
        assertNotNull(ctx);
        log.info("进入场景成功校验: retcode={}, planeId={}, floorId={}, monsterCount={}, npcCount={}, propCount={}, initialized={}",
                rsp.getRetcode(),
                rsp.getSceneInfo().getPlaneId(),
                rsp.getSceneInfo().getFloorId(),
                rsp.getEntityList().getMonstersCount(),
                rsp.getEntityList().getNpcsCount(),
                rsp.getEntityList().getPropsCount(),
                ctx.isInitialized());
        assertEquals(0, rsp.getRetcode());
        assertEquals(SceneTestFixtures.PLANE_ID, rsp.getSceneInfo().getPlaneId());
        assertEquals(1, rsp.getEntityList().getMonstersCount());
        assertEquals(1, rsp.getEntityList().getNpcsCount());
        assertEquals(1, rsp.getEntityList().getPropsCount());
        assertTrue(ctx.isInitialized());
    }

    @Test
    @DisplayName("未登录进入场景应返回 retcode=1")
    void enterSceneWithoutLoginShouldFail() {
        SceneSystemProto.EnterSceneScRsp rsp = service.handleEnterScene(
                SceneSystemProto.EnterSceneCsReq.newBuilder()
                        .setPlaneId(SceneTestFixtures.PLANE_ID)
                        .setFloorId(SceneTestFixtures.FLOOR_ID)
                        .build(),
                loggedOutChannel());
        log.info("未登录进场景校验: planeId={}, floorId={}, retcode={}",
                SceneTestFixtures.PLANE_ID, SceneTestFixtures.FLOOR_ID, rsp.getRetcode());
        assertEquals(1, rsp.getRetcode());
    }

    @Test
    @DisplayName("场景配置不存在应返回 retcode=2")
    void enterSceneMissingConfigShouldFail() {
        when(sceneConfigRepository.findGroups(SceneTestFixtures.PLANE_ID, SceneTestFixtures.FLOOR_ID))
                .thenReturn(null);

        SceneSystemProto.EnterSceneScRsp rsp = service.handleEnterScene(
                SceneSystemProto.EnterSceneCsReq.newBuilder()
                        .setPlaneId(SceneTestFixtures.PLANE_ID)
                        .setFloorId(SceneTestFixtures.FLOOR_ID)
                        .build(),
                loggedInChannel(PLAYER_UID));
        log.info("缺失配置进场景校验: planeId={}, floorId={}, retcode={}",
                SceneTestFixtures.PLANE_ID, SceneTestFixtures.FLOOR_ID, rsp.getRetcode());
        assertEquals(2, rsp.getRetcode());
    }

    @Test
    @DisplayName("查询当前场景成功应返回完整快照")
    void getCurSceneInfoSuccessShouldReturnSnapshot() {
        sceneManager.put(PLAYER_UID, SceneTestFixtures.createInitializedContext(PLAYER_UID));

        SceneSystemProto.GetCurSceneInfoScRsp rsp = service.handleGetCurSceneInfo(
                SceneSystemProto.GetCurSceneInfoCsReq.newBuilder().build(),
                loggedInChannel(PLAYER_UID));

        log.info("当前场景查询校验: retcode={}, planeId={}, floorId={}, pos=({}, {}, {}), monsterCount={}, npcEntityId={}",
                rsp.getRetcode(),
                rsp.getSceneInfo().getPlaneId(),
                rsp.getSceneInfo().getFloorId(),
                rsp.getSceneInfo().getPosX(),
                rsp.getSceneInfo().getPosY(),
                rsp.getSceneInfo().getPosZ(),
                rsp.getEntityList().getMonstersCount(),
                rsp.getEntityList().getNpcsCount() > 0 ? rsp.getEntityList().getNpcs(0).getEntityId() : 0);
        assertEquals(0, rsp.getRetcode());
        assertEquals(SceneTestFixtures.PLANE_ID, rsp.getSceneInfo().getPlaneId());
        assertEquals(1, rsp.getEntityList().getMonstersCount());
        assertEquals(1000002, rsp.getEntityList().getNpcs(0).getEntityId());
    }

    @Test
    @DisplayName("未登录查询当前场景应返回 retcode=1")
    void getCurSceneInfoWithoutLoginShouldFail() {
        SceneSystemProto.GetCurSceneInfoScRsp rsp = service.handleGetCurSceneInfo(
                SceneSystemProto.GetCurSceneInfoCsReq.newBuilder().build(),
                loggedOutChannel());
        log.info("未登录查询场景校验: retcode={}", rsp.getRetcode());
        assertEquals(1, rsp.getRetcode());
    }

    @Test
    @DisplayName("尚未进入场景查询应返回 retcode=2")
    void getCurSceneInfoWithoutSceneShouldFail() {
        SceneSystemProto.GetCurSceneInfoScRsp rsp = service.handleGetCurSceneInfo(
                SceneSystemProto.GetCurSceneInfoCsReq.newBuilder().build(),
                loggedInChannel(PLAYER_UID));
        log.info("未进场景查询校验: playerUid={}, retcode={}", PLAYER_UID, rsp.getRetcode());
        assertEquals(2, rsp.getRetcode());
    }

    @Test
    @DisplayName("NPC 交互成功应返回对话 ID")
    void interactNpcSuccessShouldReturnDialogueId() {
        sceneManager.put(PLAYER_UID, SceneTestFixtures.createInitializedContext(PLAYER_UID));
        when(npcConfigRepository.findById(SceneTestFixtures.NPC_CONFIG_ID))
                .thenReturn(SceneTestFixtures.npcRow(
                        SceneTestFixtures.NPC_CONFIG_ID,
                        SceneTestFixtures.NPC_DIALOGUE_ID,
                        SceneTestFixtures.NPC_ROGUE_EVENT_ID));

        SceneSystemProto.InteractNpcScRsp rsp = service.handleInteractNpc(
                SceneSystemProto.InteractNpcCsReq.newBuilder()
                        .setEntityId(1000002)
                        .build(),
                loggedInChannel(PLAYER_UID));

        log.info("NPC 交互成功校验: entityId=1000002, npcId={}, retcode={}, dialogueId={}",
                SceneTestFixtures.NPC_CONFIG_ID, rsp.getRetcode(), rsp.getDialogueId());
        assertEquals(0, rsp.getRetcode());
        assertEquals(1000002, rsp.getEntityId());
        assertEquals(SceneTestFixtures.NPC_DIALOGUE_ID, rsp.getDialogueId());
    }

    @Test
    @DisplayName("交互不存在的 NPC 实体应返回 retcode=3")
    void interactMissingNpcShouldFail() {
        sceneManager.put(PLAYER_UID, SceneTestFixtures.createInitializedContext(PLAYER_UID));

        SceneSystemProto.InteractNpcScRsp rsp = service.handleInteractNpc(
                SceneSystemProto.InteractNpcCsReq.newBuilder()
                        .setEntityId(9999999)
                        .build(),
                loggedInChannel(PLAYER_UID));

        log.info("缺失 NPC 交互校验: entityId=9999999, retcode={}", rsp.getRetcode());
        assertEquals(3, rsp.getRetcode());
        assertEquals(9999999, rsp.getEntityId());
    }

    @Test
    @DisplayName("拾取道具成功应更新道具状态为已开启")
    void pickupPropSuccessShouldOpenProp() {
        sceneManager.put(PLAYER_UID, SceneTestFixtures.createInitializedContext(PLAYER_UID));

        SceneSystemProto.PickupPropScRsp rsp = service.handlePickupProp(
                SceneSystemProto.PickupPropCsReq.newBuilder()
                        .setEntityId(1000003)
                        .build(),
                loggedInChannel(PLAYER_UID));

        SceneContext ctx = sceneManager.getByPlayerUid(PLAYER_UID);
        int propStateAfter = ctx.getProp(1000003).getState();

        log.info("道具拾取成功校验: entityId=1000003, propId={}, retcode={}, propStateBefore=0, propStateAfter={}",
                SceneTestFixtures.PROP_CONFIG_ID, rsp.getRetcode(), propStateAfter);
        assertEquals(0, rsp.getRetcode());
        assertEquals(1000003, rsp.getEntityId());
        assertEquals(1, rsp.getPropState());
        assertEquals(1, propStateAfter);
    }

    @Test
    @DisplayName("拾取不存在的道具应返回 retcode=3")
    void pickupMissingPropShouldFail() {
        sceneManager.put(PLAYER_UID, SceneTestFixtures.createInitializedContext(PLAYER_UID));

        SceneSystemProto.PickupPropScRsp rsp = service.handlePickupProp(
                SceneSystemProto.PickupPropCsReq.newBuilder()
                        .setEntityId(9999999)
                        .build(),
                loggedInChannel(PLAYER_UID));

        log.info("缺失道具拾取校验: entityId=9999999, retcode={}, propState={}",
                rsp.getRetcode(), rsp.getPropState());
        assertEquals(3, rsp.getRetcode());
        assertEquals(0, rsp.getPropState());
    }

    @Test
    @DisplayName("触发场景事件应返回 retcode=0")
    void triggerSceneEventShouldSucceed() {
        SceneSystemProto.TriggerSceneEventScRsp rsp = service.handleTriggerSceneEvent(
                SceneSystemProto.TriggerSceneEventCsReq.newBuilder()
                        .setEntityId(1000004)
                        .build(),
                loggedInChannel(PLAYER_UID));

        log.info("场景事件触发校验: entityId={}, retcode={}", rsp.getEntityId(), rsp.getRetcode());
        assertEquals(0, rsp.getRetcode());
        assertEquals(1000004, rsp.getEntityId());
    }

    @Test
    @Disabled("SceneSystemProto.UseHealingSpringScRsp 生成代码存在 NoSuchMethodError，待 proto 重新生成后启用")
    @DisplayName("使用治疗泉应返回演示效果")
    void useHealingSpringShouldReturnDemoEffect() {
        SceneSystemProto.UseHealingSpringScRsp rsp = service.handleUseHealingSpring(
                SceneSystemProto.UseHealingSpringCsReq.newBuilder()
                        .setEntityId(1000005)
                        .build(),
                loggedInChannel(PLAYER_UID));

        log.info("治疗泉使用校验: entityId=1000005, retcode={}, hpRecovered={}, respawnPointSet={}",
                rsp.getRetcode(), rsp.getHealEffect().getHpRecovered(), rsp.getRespawnPointSet());
        assertEquals(0, rsp.getRetcode());
        assertEquals(1000005, rsp.getEntityId());
        assertEquals(0, rsp.getHealEffect().getHpRecovered());
        assertTrue(rsp.getRespawnPointSet());
    }

    private void stubSceneConfigAndEntities() {
        when(sceneConfigRepository.findGroups(SceneTestFixtures.PLANE_ID, SceneTestFixtures.FLOOR_ID))
                .thenReturn(SceneTestFixtures.sceneRow(
                        SceneTestFixtures.PLANE_ID,
                        SceneTestFixtures.FLOOR_ID,
                        SceneTestFixtures.sampleGroupsJson()));
        when(monsterConfigRepository.findById(SceneTestFixtures.MONSTER_CONFIG_ID))
                .thenReturn(SceneTestFixtures.monsterRow(
                        SceneTestFixtures.MONSTER_CONFIG_ID, 10, 1000, "[1,2]"));
        when(npcConfigRepository.findById(SceneTestFixtures.NPC_CONFIG_ID))
                .thenReturn(SceneTestFixtures.npcRow(
                        SceneTestFixtures.NPC_CONFIG_ID,
                        SceneTestFixtures.NPC_DIALOGUE_ID,
                        SceneTestFixtures.NPC_ROGUE_EVENT_ID));
    }

    @SuppressWarnings("unchecked")
    private Channel loggedInChannel(long uid) {
        Channel channel = mock(Channel.class);
        Attribute<Long> uidAttr = mock(Attribute.class);
        when(channel.attr(UID_KEY)).thenReturn(uidAttr);
        when(uidAttr.get()).thenReturn(uid);
        log.info("模拟登录 Channel: uid={}", uid);
        return channel;
    }

    @SuppressWarnings("unchecked")
    private Channel loggedOutChannel() {
        Channel channel = mock(Channel.class);
        Attribute<Long> uidAttr = mock(Attribute.class);
        when(channel.attr(UID_KEY)).thenReturn(uidAttr);
        when(uidAttr.get()).thenReturn(null);
        return channel;
    }
}
