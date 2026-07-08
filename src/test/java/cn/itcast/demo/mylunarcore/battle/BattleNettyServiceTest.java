package cn.itcast.demo.mylunarcore.battle;

import cn.itcast.demo.mylunarcore.common.BattleEndedEvent;
import cn.itcast.demo.mylunarcore.common.BattleStartedEvent;
import cn.itcast.demo.mylunarcore.common.GameEventPublisher;
import cn.itcast.demo.mylunarcore.net.GamePacket;
import cn.itcast.demo.mylunarcore.protocol.BattleSystemProto;
import cn.itcast.demo.mylunarcore.repo.BattleMonsterWaveRepository;
import cn.itcast.demo.mylunarcore.repo.BattleRepository;
import cn.itcast.demo.mylunarcore.repo.MazeBuffRepository;
import cn.itcast.demo.mylunarcore.repo.MazeSkillActionRepository;
import cn.itcast.demo.mylunarcore.repo.MazeSkillRepository;
import io.netty.channel.Channel;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Timestamp;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("BattleNettyService 战斗协议服务测试")
class BattleNettyServiceTest {

    private static final Logger log = LoggerFactory.getLogger(BattleNettyServiceTest.class);

    private static final AttributeKey<Long> UID_KEY = AttributeKey.valueOf("playerUid");
    private static final long PLAYER_UID = 77L;
    private static final int PLAYER_ID = 77;
    private static final long BATTLE_ID = 88001L;

    private BattleManager battleManager;
    private BattleRepository battleRepository;
    private BattleMonsterWaveRepository waveRepository;
    private MazeSkillRepository skillRepository;
    private MazeSkillActionRepository skillActionRepository;
    private MazeBuffRepository buffRepository;
    private GameEventPublisher gameEventPublisher;
    private BattleNettyService service;

    @BeforeEach
    void setUp() {
        battleManager = new BattleManager();
        battleRepository = mock(BattleRepository.class);
        waveRepository = mock(BattleMonsterWaveRepository.class);
        skillRepository = mock(MazeSkillRepository.class);
        skillActionRepository = mock(MazeSkillActionRepository.class);
        buffRepository = mock(MazeBuffRepository.class);
        gameEventPublisher = mock(GameEventPublisher.class);
        BattleSceneFactory battleSceneFactory = new BattleSceneFactory();

        service = new BattleNettyService(
                battleManager,
                battleRepository,
                waveRepository,
                skillRepository,
                skillActionRepository,
                buffRepository,
                battleSceneFactory,
                gameEventPublisher);
    }

    @Test
    @DisplayName("开战成功应注册战局并返回敌方信息")
    void fightStartSuccessShouldRegisterBattle() throws Exception {
        Channel channel = loggedInChannel(PLAYER_UID);
        when(battleRepository.insertBattle(eq(PLAYER_ID), eq(1), eq(100), any(Timestamp.class)))
                .thenReturn(BATTLE_ID);
        when(waveRepository.loadWavesByStageId(100)).thenReturn(BattleTestFixtures.twoWaveStage());

        BattleSystemProto.FightStartScRsp rsp = service.handleFightStart(
                BattleSystemProto.FightStartCsReq.newBuilder()
                        .setBattleStageId(100)
                        .setLineupId(1)
                        .build(),
                channel);

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(gameEventPublisher).publish(eventCaptor.capture());
        log.info("开战成功校验: stageId=100, lineupId=1, retcode={}, battleId={}, waveCount={}, enemyWaveCount={}, eventType={}, battleRegistered={}",
                rsp.getRetcode(), rsp.getBattleId(), rsp.getStageInfo().getWaveCount(),
                rsp.getEnemyInfoCount(), eventCaptor.getValue().getClass().getSimpleName(),
                battleManager.get(BATTLE_ID) != null);

        assertEquals(0, rsp.getRetcode());
        assertEquals(BATTLE_ID, rsp.getBattleId());
        assertEquals(2, rsp.getStageInfo().getWaveCount());
        assertEquals(2, rsp.getEnemyInfoCount());
        assertNotNull(battleManager.get(BATTLE_ID));
        assertTrue(eventCaptor.getValue() instanceof BattleStartedEvent);
    }

    @Test
    @DisplayName("未登录开战应返回 retcode=1")
    void fightStartWithoutLoginShouldFail() {
        Channel channel = mock(Channel.class);
        Attribute<Long> uidAttr = mock(Attribute.class);
        when(channel.attr(UID_KEY)).thenReturn(uidAttr);
        when(uidAttr.get()).thenReturn(null);

        BattleSystemProto.FightStartScRsp rsp = service.handleFightStart(
                BattleSystemProto.FightStartCsReq.newBuilder()
                        .setBattleStageId(100)
                        .setLineupId(1)
                        .build(),
                channel);
        log.info("未登录开战校验: uid=null, stageId=100, retcode={}", rsp.getRetcode());
        assertEquals(1, rsp.getRetcode());
    }

    @Test
    @DisplayName("非法关卡或阵容 ID 开战应返回 retcode=2")
    void fightStartWithInvalidParamsShouldFail() {
        Channel channel = loggedInChannel(PLAYER_UID);

        BattleSystemProto.FightStartScRsp rsp = service.handleFightStart(
                BattleSystemProto.FightStartCsReq.newBuilder()
                        .setBattleStageId(0)
                        .setLineupId(1)
                        .build(),
                channel);
        log.info("非法参数开战校验: stageId=0, lineupId=1, retcode={}", rsp.getRetcode());
        assertEquals(2, rsp.getRetcode());
    }

    @Test
    @DisplayName("写库失败开战应返回 retcode=3")
    void fightStartInsertFailedShouldReturnRetcode3() throws Exception {
        Channel channel = loggedInChannel(PLAYER_UID);
        when(battleRepository.insertBattle(anyInt(), anyInt(), anyInt(), any(Timestamp.class)))
                .thenThrow(new RuntimeException("db down"));

        BattleSystemProto.FightStartScRsp rsp = service.handleFightStart(
                BattleSystemProto.FightStartCsReq.newBuilder()
                        .setBattleStageId(100)
                        .setLineupId(1)
                        .build(),
                channel);
        log.info("insertBattle 失败校验: stageId=100, retcode={}, error=db down", rsp.getRetcode());
        assertEquals(3, rsp.getRetcode());
    }

    @Test
    @DisplayName("波次配置加载失败应返回 retcode=4")
    void fightStartLoadWavesFailedShouldReturnRetcode4() throws Exception {
        Channel channel = loggedInChannel(PLAYER_UID);
        when(battleRepository.insertBattle(anyInt(), anyInt(), anyInt(), any(Timestamp.class)))
                .thenReturn(BATTLE_ID);
        when(waveRepository.loadWavesByStageId(100)).thenThrow(new RuntimeException("wave error"));

        BattleSystemProto.FightStartScRsp rsp = service.handleFightStart(
                BattleSystemProto.FightStartCsReq.newBuilder()
                        .setBattleStageId(100)
                        .setLineupId(1)
                        .build(),
                channel);
        log.info("波次加载失败校验: stageId=100, battleId={}, retcode={}, error=wave error",
                BATTLE_ID, rsp.getRetcode());
        assertEquals(4, rsp.getRetcode());
    }

    @Test
    @DisplayName("技能释放应扣血并推进回合")
    void fightActionSkillShouldDealDamageAndAdvanceTurn() {
        BattleContext context = registerBattle(BattleTestFixtures.twoWaveStage());
        Channel channel = loggedInChannel(PLAYER_UID);

        when(skillRepository.findById(1001)).thenReturn(new MazeSkillRepository.MazeSkill(
                1001, "slash", "desc", 1, 1, 0));
        when(skillActionRepository.findBySkillId(1001)).thenReturn(List.of(
                BattleTestFixtures.skillActionRow(1, 1001, 1, 1, "{\"hp_change\":-200}")
        ));

        BattleSystemProto.FightActionScRsp rsp = service.handleFightAction(
                BattleSystemProto.FightActionCsReq.newBuilder()
                        .setBattleId(BATTLE_ID)
                        .setActionType(1)
                        .setSkillId(1001)
                        .addTargetIds(101)
                        .build(),
                channel);

        EntityState target = context.getEntity(101);
        log.info("技能扣血校验: skillId=1001, targetId=101, retcode={}, turn={}, hpChange={}, targetHp={}, targetDead={}",
                rsp.getRetcode(), rsp.getCurrentState().getTurn(),
                rsp.getActionResults(0).getHpChange(), target.getHp(), target.isDead());

        assertEquals(0, rsp.getRetcode());
        assertEquals(2, rsp.getCurrentState().getTurn());
        assertEquals(-200, rsp.getActionResults(0).getHpChange());
        assertEquals(0, target.getHp());
        assertTrue(target.isDead());
    }

    @Test
    @DisplayName("清场后应自动切换下一波并推送状态通知")
    void fightActionShouldAdvanceWaveWhenAllMonstersDead() {
        BattleContext context = registerBattle(BattleTestFixtures.twoWaveStage());
        Channel channel = loggedInChannel(PLAYER_UID);

        when(skillRepository.findById(9001)).thenReturn(new MazeSkillRepository.MazeSkill(
                9001, "kill", "desc", 1, 1, 0));
        when(skillActionRepository.findBySkillId(9001)).thenReturn(List.of(
                BattleTestFixtures.skillActionRow(1, 9001, 5, 1, "{\"kill\":true}")
        ));

        BattleSystemProto.FightActionScRsp rsp = service.handleFightAction(
                BattleSystemProto.FightActionCsReq.newBuilder()
                        .setBattleId(BATTLE_ID)
                        .setActionType(1)
                        .setSkillId(9001)
                        .addTargetIds(101)
                        .addTargetIds(102)
                        .build(),
                channel);

        log.info("波次切换校验: skillId=9001, targets=[101,102], retcode={}, currentWave={}, turn={}, nextWaveMonster201Exists={}",
                rsp.getRetcode(), rsp.getCurrentState().getCurrentWave(), rsp.getCurrentState().getTurn(),
                context.getEntity(201) != null);

        assertEquals(0, rsp.getRetcode());
        assertEquals(2, rsp.getCurrentState().getCurrentWave());
        assertNotNull(context.getEntity(201));
        verify(channel).writeAndFlush(any(GamePacket.class));
    }

    @Test
    @DisplayName("Buff 技能应叠加层数")
    void fightActionShouldApplyBuffStacks() {
        registerBattle(BattleTestFixtures.singleWaveStage());
        Channel channel = loggedInChannel(PLAYER_UID);

        when(skillRepository.findById(2001)).thenReturn(new MazeSkillRepository.MazeSkill(
                2001, "buff", "desc", 1, 1, 0));
        when(skillActionRepository.findBySkillId(2001)).thenReturn(List.of(
                BattleTestFixtures.skillActionRow(1, 2001, 2, 1, "{\"buff_id\":5}")
        ));
        when(buffRepository.findMaxStack(5)).thenReturn(3);

        BattleSystemProto.FightActionScRsp rsp = service.handleFightAction(
                BattleSystemProto.FightActionCsReq.newBuilder()
                        .setBattleId(BATTLE_ID)
                        .setActionType(1)
                        .setSkillId(2001)
                        .addTargetIds(301)
                        .build(),
                channel);

        log.info("Buff 叠加校验: skillId=2001, targetId=301, buffId=5, maxStack=3, retcode={}, addedBuffs={}",
                rsp.getRetcode(), rsp.getActionResults(0).getAddedBuffsList());
        assertEquals(0, rsp.getRetcode());
        assertEquals(List.of(5), rsp.getActionResults(0).getAddedBuffsList());
    }

    @Test
    @DisplayName("战局不存在时行动应返回 retcode=1")
    void fightActionMissingBattleShouldFail() {
        Channel channel = loggedInChannel(PLAYER_UID);
        BattleSystemProto.FightActionScRsp rsp = service.handleFightAction(
                BattleSystemProto.FightActionCsReq.newBuilder()
                        .setBattleId(404L)
                        .setActionType(1)
                        .setSkillId(1)
                        .addTargetIds(1)
                        .build(),
                channel);
        log.info("战局不存在校验: battleId=404, retcode={}", rsp.getRetcode());
        assertEquals(1, rsp.getRetcode());
    }

    @Test
    @DisplayName("非本人战局行动应返回 retcode=7")
    void fightActionWrongOwnerShouldFail() {
        registerBattle(BattleTestFixtures.singleWaveStage());
        Channel channel = loggedInChannel(999L);

        BattleSystemProto.FightActionScRsp rsp = service.handleFightAction(
                BattleSystemProto.FightActionCsReq.newBuilder()
                        .setBattleId(BATTLE_ID)
                        .setActionType(1)
                        .setSkillId(1)
                        .addTargetIds(301)
                        .build(),
                channel);
        log.info("非本人战局校验: battleId={}, requestUid=999, ownerPlayerId={}, retcode={}",
                BATTLE_ID, PLAYER_ID, rsp.getRetcode());
        assertEquals(7, rsp.getRetcode());
    }

    @Test
    @DisplayName("已结束战局行动应返回 retcode=2")
    void fightActionOnEndedBattleShouldFail() {
        BattleContext context = registerBattle(BattleTestFixtures.singleWaveStage());
        context.setEnded(true);
        Channel channel = loggedInChannel(PLAYER_UID);

        BattleSystemProto.FightActionScRsp rsp = service.handleFightAction(
                BattleSystemProto.FightActionCsReq.newBuilder()
                        .setBattleId(BATTLE_ID)
                        .setActionType(1)
                        .setSkillId(1)
                        .addTargetIds(301)
                        .build(),
                channel);
        log.info("已结束战局校验: battleId={}, ended={}, retcode={}",
                BATTLE_ID, context.isEnded(), rsp.getRetcode());
        assertEquals(2, rsp.getRetcode());
    }

    @Test
    @DisplayName("技能不存在应返回 retcode=4")
    void fightActionUnknownSkillShouldFail() {
        registerBattle(BattleTestFixtures.singleWaveStage());
        Channel channel = loggedInChannel(PLAYER_UID);
        when(skillRepository.findById(404)).thenReturn(null);

        BattleSystemProto.FightActionScRsp rsp = service.handleFightAction(
                BattleSystemProto.FightActionCsReq.newBuilder()
                        .setBattleId(BATTLE_ID)
                        .setActionType(1)
                        .setSkillId(404)
                        .addTargetIds(301)
                        .build(),
                channel);
        log.info("技能不存在校验: skillId=404, retcode={}", rsp.getRetcode());
        assertEquals(4, rsp.getRetcode());
    }

    @Test
    @DisplayName("战斗结果上报成功应清理战局并发布结束事件")
    void fightResultSuccessShouldCleanupBattle() throws Exception {
        registerBattle(BattleTestFixtures.singleWaveStage());
        Channel channel = loggedInChannel(PLAYER_UID);

        BattleSystemProto.FightResultScRsp rsp = service.handleFightResult(
                BattleSystemProto.FightResultCsReq.newBuilder()
                        .setBattleId(BATTLE_ID)
                        .setEndStatus(1)
                        .setStatistics(BattleSystemProto.BattleStatistics.newBuilder()
                                .setDamageDealt(500)
                                .setTurnCount(3)
                                .build())
                        .build(),
                channel);

        ArgumentCaptor<Object> eventCaptor = ArgumentCaptor.forClass(Object.class);
        verify(gameEventPublisher).publish(eventCaptor.capture());
        log.info("战斗结果上报校验: battleId={}, endStatus=1, damageDealt=500, turnCount=3, retcode={}, battleRemoved={}, eventType={}",
                BATTLE_ID, rsp.getRetcode(), battleManager.get(BATTLE_ID) == null,
                eventCaptor.getValue().getClass().getSimpleName());

        assertEquals(0, rsp.getRetcode());
        assertNull(battleManager.get(BATTLE_ID));
        assertTrue(eventCaptor.getValue() instanceof BattleEndedEvent);
        verify(battleRepository).updateBattleResult(eq(BATTLE_ID), eq(1), anyString(), any(Timestamp.class));
    }

    @Test
    @DisplayName("战斗结果写库失败应保留战局")
    void fightResultDbFailureShouldKeepBattle() throws Exception {
        registerBattle(BattleTestFixtures.singleWaveStage());
        Channel channel = loggedInChannel(PLAYER_UID);
        doThrow(new RuntimeException("update failed"))
                .when(battleRepository).updateBattleResult(anyLong(), anyInt(), anyString(), any(Timestamp.class));

        BattleSystemProto.FightResultScRsp rsp = service.handleFightResult(
                BattleSystemProto.FightResultCsReq.newBuilder()
                        .setBattleId(BATTLE_ID)
                        .setEndStatus(1)
                        .build(),
                channel);

        log.info("写库失败保留战局校验: battleId={}, endStatus=1, retcode={}, battleStillExists={}, eventPublished=false",
                BATTLE_ID, rsp.getRetcode(), battleManager.get(BATTLE_ID) != null);
        assertEquals(2, rsp.getRetcode());
        assertNotNull(battleManager.get(BATTLE_ID));
        verify(gameEventPublisher, never()).publish(any());
    }

    @Test
    @DisplayName("主动退出战斗应清理战局")
    void fightQuitSuccessShouldCleanupBattle() throws Exception {
        registerBattle(BattleTestFixtures.singleWaveStage());
        Channel channel = loggedInChannel(PLAYER_UID);

        BattleSystemProto.FightQuitScRsp rsp = service.handleFightQuit(
                BattleSystemProto.FightQuitCsReq.newBuilder()
                        .setBattleId(BATTLE_ID)
                        .build(),
                channel);

        log.info("主动退出校验: battleId={}, endStatus=3, retcode={}, teleportSceneId={}, battleRemoved={}",
                BATTLE_ID, rsp.getRetcode(), rsp.getTeleportSceneId(), battleManager.get(BATTLE_ID) == null);
        assertEquals(0, rsp.getRetcode());
        assertEquals(0, rsp.getTeleportSceneId());
        assertNull(battleManager.get(BATTLE_ID));
        verify(battleRepository).updateBattleResult(eq(BATTLE_ID), eq(3), eq("{}"), any(Timestamp.class));
    }

    @Test
    @DisplayName("查询战局信息应返回当前快照")
    void getBattleInfoShouldReturnSnapshot() {
        BattleContext context = registerBattle(BattleTestFixtures.twoWaveStage());
        context.incrementTurn();
        Channel channel = loggedInChannel(PLAYER_UID);

        BattleSystemProto.GetBattleInfoScRsp rsp = service.handleGetBattleInfo(
                BattleSystemProto.GetBattleInfoCsReq.newBuilder()
                        .setBattleId(BATTLE_ID)
                        .build(),
                channel);

        log.info("战局查询校验: battleId={}, retcode={}, turn={}, waveCount={}, entityCount={}, startTime={}",
                rsp.getBattleId(), rsp.getRetcode(), rsp.getCurrentState().getTurn(),
                rsp.getStageInfo().getWaveCount(), rsp.getCurrentState().getEntitiesCount(),
                rsp.getStartTime());

        assertEquals(0, rsp.getRetcode());
        assertEquals(BATTLE_ID, rsp.getBattleId());
        assertEquals(2, rsp.getStageInfo().getWaveCount());
        assertEquals(2, rsp.getCurrentState().getTurn());
        assertFalse(rsp.getCurrentState().getEntitiesList().isEmpty());
    }

    @Test
    @DisplayName("非本人查询战局应返回 retcode=3")
    void getBattleInfoWrongOwnerShouldFail() {
        registerBattle(BattleTestFixtures.singleWaveStage());
        Channel channel = loggedInChannel(888L);

        BattleSystemProto.GetBattleInfoScRsp rsp = service.handleGetBattleInfo(
                BattleSystemProto.GetBattleInfoCsReq.newBuilder()
                        .setBattleId(BATTLE_ID)
                        .build(),
                channel);
        log.info("非本人查询校验: battleId={}, requestUid=888, ownerPlayerId={}, retcode={}",
                BATTLE_ID, PLAYER_ID, rsp.getRetcode());
        assertEquals(3, rsp.getRetcode());
    }

    private BattleContext registerBattle(List<BattleMonsterWaveRepository.WaveConfig> waves) {
        BattleContext context = BattleTestFixtures.createContext(BATTLE_ID, PLAYER_ID, waves);
        battleManager.put(context);
        log.info("注册测试战局: battleId={}, playerId={}, waveCount={}, currentWave={}",
                BATTLE_ID, PLAYER_ID, context.getWaveCount(), context.getCurrentWave());
        return context;
    }

    @SuppressWarnings("unchecked")
    private Channel loggedInChannel(long uid) {
        Channel channel = mock(Channel.class);
        Attribute<Long> uidAttr = mock(Attribute.class);
        when(channel.attr(UID_KEY)).thenReturn(uidAttr);
        when(uidAttr.get()).thenReturn(uid);
        log.info("模拟登录 Channel: uid={}, playerId={}", uid, (int) (uid & 0xffffffffL));
        return channel;
    }
}
