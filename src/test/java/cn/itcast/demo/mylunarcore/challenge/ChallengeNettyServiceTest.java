package cn.itcast.demo.mylunarcore.challenge;

import cn.itcast.demo.mylunarcore.model.ChallengeHistoryEntity;
import cn.itcast.demo.mylunarcore.protocol.ChallengeSystemProto;
import cn.itcast.demo.mylunarcore.repo.BattleMonsterWaveRepository;
import cn.itcast.demo.mylunarcore.repo.ChallengeGroupRewardRepository;
import cn.itcast.demo.mylunarcore.repo.ChallengeHistoryRepository;
import cn.itcast.demo.mylunarcore.matchmaking.ChallengeMatchCoordinator;
import cn.itcast.demo.mylunarcore.player.PlayerContextResolver;
import cn.itcast.demo.mylunarcore.economy.RewardDistributor;
import io.netty.channel.Channel;
import java.util.OptionalLong;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ChallengeNettyService 挑战协议服务测试。
 * <p>
 * 针对相关生产代码的单元/切片测试类 {@code ChallengeNettyServiceTest}：
 * 通过 fixture、mock 与断言覆盖关键成功路径、失败码与状态边界。
 */
@DisplayName("ChallengeNettyService 挑战协议服务测试")
class ChallengeNettyServiceTest {

    private static final Logger log = LoggerFactory.getLogger(ChallengeNettyServiceTest.class);

    private static final AttributeKey<Long> UID_KEY = AttributeKey.valueOf("playerUid");
    private static final long PLAYER_UID = 77L;
    private static final int PLAYER_ID = 77;
    private static final int CHALLENGE_TYPE = 1;
    private static final int CHALLENGE_ID = 100;
    private static final int STAGE_ID = 1100;
    private static final int GROUP_ID = 0;
    private static final int LINEUP_ID = 1;

    private ChallengeManager challengeManager;
    private BattleMonsterWaveRepository waveRepository;
    private ChallengeHistoryRepository historyRepository;
    private ChallengeGroupRewardRepository groupRewardRepository;
    private PlayerContextResolver contextResolver;
    private ChallengeNettyService service;

    @BeforeEach
    void setUp() {
        challengeManager = new ChallengeManager();
        waveRepository = mock(BattleMonsterWaveRepository.class);
        historyRepository = mock(ChallengeHistoryRepository.class);
        groupRewardRepository = mock(ChallengeGroupRewardRepository.class);
        contextResolver = mock(PlayerContextResolver.class);
        ChallengeMatchCoordinator challengeMatchCoordinator = mock(ChallengeMatchCoordinator.class);
        when(challengeMatchCoordinator.ensureReadyForChallenge(anyInt(), any(Boolean.class), anyLong()))
                .thenReturn(new ChallengeMatchCoordinator.MatchGateResult(true, 0, 0));
        RewardDistributor rewardDistributor = mock(RewardDistributor.class);
        when(rewardDistributor.grantBattleRewards(anyInt(), any(), anyInt(), anyString()))
                .thenReturn(List.of());
        service = new ChallengeNettyService(
                challengeManager, waveRepository, historyRepository, groupRewardRepository,
                contextResolver, challengeMatchCoordinator,
                mock(cn.itcast.demo.mylunarcore.matchmaking.RoomService.class),
                new cn.itcast.demo.mylunarcore.battle.BattleManager(
                        org.mockito.Mockito.mock(cn.itcast.demo.mylunarcore.battle.BattleSnapshotService.class)),
                new cn.itcast.demo.mylunarcore.battle.BattleSceneFactory(),
                mock(cn.itcast.demo.mylunarcore.repo.BattleRepository.class),
                rewardDistributor);
        log.info("挑战服务初始化: playerId={}, challengeId={}, stageId={}, groupId={}",
                PLAYER_ID, CHALLENGE_ID, STAGE_ID, GROUP_ID);
    }

    /**
     * 验证点：开始挑战成功应注册运行时并返回波次信息。
     * <p>测试方法 {@code startChallengeSuccessShouldRegisterRuntime}：
     * <ul>
     *   <li>{@code when(waveRepository.loadWavesByStageId(STAGE_ID)).thenReturn(ChallengeTestFixtures.wavesForChallenge(CHALLENGE_ID));}</li>
     *   <li>{@code assertNotNull(runtime);}</li>
     *   <li>{@code assertEquals(0, rsp.getRetcode());}</li>
     *   <li>{@code assertEquals(CHALLENGE_TYPE, rsp.getChallengeType());}</li>
     *   <li>{@code assertEquals(CHALLENGE_ID, rsp.getChallengeId());}</li>
     *   <li>{@code assertEquals(2, rsp.getStageInfo().getWaveCount());}</li>
     * </ul>
     */
    @Test
    @DisplayName("开始挑战成功应注册运行时并返回波次信息")
    void startChallengeSuccessShouldRegisterRuntime() throws Exception {
        Channel channel = loggedInChannel(PLAYER_UID);
        when(waveRepository.loadWavesByStageId(STAGE_ID)).thenReturn(ChallengeTestFixtures.wavesForChallenge(CHALLENGE_ID));

        ChallengeSystemProto.StartChallengeScRsp rsp = service.handleStartChallenge(
                ChallengeSystemProto.StartChallengeCsReq.newBuilder()
                        .setChallengeType(CHALLENGE_TYPE)
                        .setChallengeId(CHALLENGE_ID)
                        .setLineupId(LINEUP_ID)
                        .build(),
                channel);

        long challengeUid = rsp.getChallengeUid();
        ChallengeRuntime runtime = challengeManager.get(challengeUid);
        assertNotNull(runtime);
        log.info("开始挑战成功校验: retcode={}, challengeUid={}, challengeType={}, challengeId={}, waveCount={}, enemyWaveCount={}, roundsLeft={}, runtimeRegistered={}",
                rsp.getRetcode(), challengeUid, rsp.getChallengeType(), rsp.getChallengeId(),
                rsp.getStageInfo().getWaveCount(), rsp.getEnemyInfoCount(),
                rsp.getStageInfo().getRoundsLeft(), runtime != null);

        assertEquals(0, rsp.getRetcode());
        assertEquals(CHALLENGE_TYPE, rsp.getChallengeType());
        assertEquals(CHALLENGE_ID, rsp.getChallengeId());
        assertEquals(2, rsp.getStageInfo().getWaveCount());
        assertEquals(2, rsp.getEnemyInfoCount());
        assertEquals(5, rsp.getStageInfo().getRoundsLeft());
        assertEquals(PLAYER_ID, runtime.getPlayerId());
    }

    /**
     * 验证点：未登录开始挑战应返回 retcode=1。
     * <p>测试方法 {@code startChallengeWithoutLoginShouldFail}：
     * <ul>
     *   <li>{@code when(contextResolver.resolvePlayerId(channel)).thenReturn(0);}</li>
     *   <li>{@code when(contextResolver.resolveUid(channel)).thenReturn(OptionalLong.empty());}</li>
     *   <li>{@code assertEquals(1, rsp.getRetcode());}</li>
     * </ul>
     */
    @Test
    @DisplayName("未登录开始挑战应返回 retcode=1")
    void startChallengeWithoutLoginShouldFail() {
        Channel channel = mock(Channel.class);
        when(contextResolver.resolvePlayerId(channel)).thenReturn(0);
        when(contextResolver.resolveUid(channel)).thenReturn(OptionalLong.empty());

        ChallengeSystemProto.StartChallengeScRsp rsp = service.handleStartChallenge(
                ChallengeSystemProto.StartChallengeCsReq.newBuilder()
                        .setChallengeType(CHALLENGE_TYPE)
                        .setChallengeId(CHALLENGE_ID)
                        .setLineupId(LINEUP_ID)
                        .build(),
                channel);
        log.info("未登录开始挑战校验: uid=null, challengeId={}, retcode={}", CHALLENGE_ID, rsp.getRetcode());
        assertEquals(1, rsp.getRetcode());
    }

    /**
     * 验证点：非法参数开始挑战应返回 retcode=2。
     * <p>测试方法 {@code startChallengeWithInvalidParamsShouldFail}：
     * <ul>
     *   <li>{@code assertEquals(2, rsp.getRetcode());}</li>
     * </ul>
     */
    @Test
    @DisplayName("非法参数开始挑战应返回 retcode=2")
    void startChallengeWithInvalidParamsShouldFail() {
        Channel channel = loggedInChannel(PLAYER_UID);

        ChallengeSystemProto.StartChallengeScRsp rsp = service.handleStartChallenge(
                ChallengeSystemProto.StartChallengeCsReq.newBuilder()
                        .setChallengeType(CHALLENGE_TYPE)
                        .setChallengeId(0)
                        .setLineupId(LINEUP_ID)
                        .build(),
                channel);
        log.info("非法参数开始挑战校验: challengeId=0, lineupId={}, retcode={}", LINEUP_ID, rsp.getRetcode());
        assertEquals(2, rsp.getRetcode());
    }

    /**
     * 验证点：波次加载失败仍应创建挑战实例。
     * <p>测试方法 {@code startChallengeWaveLoadFailedShouldStillCreateRuntime}：
     * <ul>
     *   <li>{@code when(waveRepository.loadWavesByStageId(STAGE_ID)).thenThrow(new RuntimeException("db down"));}</li>
     *   <li>{@code assertEquals(0, rsp.getRetcode());}</li>
     *   <li>{@code assertEquals(0, rsp.getStageInfo().getWaveCount());}</li>
     *   <li>{@code assertNotNull(runtime);}</li>
     * </ul>
     */
    @Test
    @DisplayName("波次加载失败仍应创建挑战实例")
    void startChallengeWaveLoadFailedShouldStillCreateRuntime() throws Exception {
        Channel channel = loggedInChannel(PLAYER_UID);
        when(waveRepository.loadWavesByStageId(STAGE_ID)).thenThrow(new RuntimeException("db down"));

        ChallengeSystemProto.StartChallengeScRsp rsp = service.handleStartChallenge(
                ChallengeSystemProto.StartChallengeCsReq.newBuilder()
                        .setChallengeType(CHALLENGE_TYPE)
                        .setChallengeId(CHALLENGE_ID)
                        .setLineupId(LINEUP_ID)
                        .build(),
                channel);

        ChallengeRuntime runtime = challengeManager.get(rsp.getChallengeUid());
        log.info("波次加载失败降级校验: stageId={}, retcode={}, challengeUid={}, waveCount={}, enemyWaveCount={}, runtimeExists={}",
                STAGE_ID, rsp.getRetcode(), rsp.getChallengeUid(),
                rsp.getStageInfo().getWaveCount(), rsp.getEnemyInfoCount(), runtime != null);
        assertEquals(0, rsp.getRetcode());
        assertEquals(0, rsp.getStageInfo().getWaveCount());
        assertNotNull(runtime);
    }

    /**
     * 验证点：查询挑战详情成功应返回当前状态。
     * <p>测试方法 {@code getChallengeInfoSuccessShouldReturnRuntime}：
     * <ul>
     *   <li>{@code assertEquals(0, rsp.getRetcode());}</li>
     *   <li>{@code assertEquals(challengeUid, rsp.getChallengeUid());}</li>
     *   <li>{@code assertEquals(1, rsp.getStatus());}</li>
     *   <li>{@code assertEquals(2, rsp.getEnemyInfoCount());}</li>
     * </ul>
     */
    @Test
    @DisplayName("查询挑战详情成功应返回当前状态")
    void getChallengeInfoSuccessShouldReturnRuntime() {
        long challengeUid = registerChallenge();

        ChallengeSystemProto.GetChallengeInfoScRsp rsp = service.handleGetChallengeInfo(
                ChallengeSystemProto.GetChallengeInfoCsReq.newBuilder()
                        .setChallengeUid(challengeUid)
                        .build(),
                loggedInChannel(PLAYER_UID));

        log.info("挑战详情查询校验: challengeUid={}, retcode={}, status={}, currentStage={}, roundsUsed={}, currentScore={}, currentStars={}, enemyWaveCount={}",
                rsp.getChallengeUid(), rsp.getRetcode(), rsp.getStatus(), rsp.getCurrentStage(),
                rsp.getRoundsUsed(), rsp.getCurrentScore(), rsp.getCurrentStars(), rsp.getEnemyInfoCount());
        assertEquals(0, rsp.getRetcode());
        assertEquals(challengeUid, rsp.getChallengeUid());
        assertEquals(1, rsp.getStatus());
        assertEquals(2, rsp.getEnemyInfoCount());
    }

    /**
     * 验证点：非本人查询挑战应返回 retcode=2。
     * <p>测试方法 {@code getChallengeInfoWrongOwnerShouldFail}：
     * <ul>
     *   <li>{@code assertEquals(2, rsp.getRetcode());}</li>
     * </ul>
     */
    @Test
    @DisplayName("非本人查询挑战应返回 retcode=2")
    void getChallengeInfoWrongOwnerShouldFail() {
        long challengeUid = registerChallenge();

        ChallengeSystemProto.GetChallengeInfoScRsp rsp = service.handleGetChallengeInfo(
                ChallengeSystemProto.GetChallengeInfoCsReq.newBuilder()
                        .setChallengeUid(challengeUid)
                        .build(),
                loggedInChannel(888L));
        log.info("非本人查询挑战校验: challengeUid={}, requestUid=888, ownerPlayerId={}, retcode={}",
                challengeUid, PLAYER_ID, rsp.getRetcode());
        assertEquals(2, rsp.getRetcode());
    }

    /**
     * 验证点：胜利上报应刷新纪录并移除运行时。
     * <p>测试方法 {@code reportResultWinShouldUpsertAndRemoveRuntime}：
     * <ul>
     *   <li>{@code when(historyRepository.findByPlayerAndChallenge(PLAYER_ID, CHALLENGE_ID)).thenReturn(Optional.empty());}</li>
     *   <li>{@code assertEquals(0, rsp.getRetcode());}</li>
     *   <li>{@code assertTrue(rsp.getIsNewRecord());}</li>
     *   <li>{@code assertEquals(5000, rsp.getBestScore());}</li>
     *   <li>{@code assertEquals(0b11, rsp.getBestStars());}</li>
     *   <li>{@code assertNull(challengeManager.get(challengeUid));}</li>
     * </ul>
     */
    @Test
    @DisplayName("胜利上报应刷新纪录并移除运行时")
    void reportResultWinShouldUpsertAndRemoveRuntime() throws Exception {
        long challengeUid = registerChallenge();
        when(historyRepository.findByPlayerAndChallenge(PLAYER_ID, CHALLENGE_ID)).thenReturn(Optional.empty());

        ChallengeSystemProto.ReportChallengeResultScRsp rsp = service.handleReportResult(
                ChallengeSystemProto.ReportChallengeResultCsReq.newBuilder()
                        .setChallengeUid(challengeUid)
                        .setIsWin(true)
                        .setFinalScore(5000)
                        .setFinalStars(0b11)
                        .setRoundsUsed(6)
                        .build(),
                loggedInChannel(PLAYER_UID));

        log.info("胜利上报校验: challengeUid={}, retcode={}, isNewRecord={}, bestScore={}, bestStars={}, runtimeRemoved={}",
                challengeUid, rsp.getRetcode(), rsp.getIsNewRecord(), rsp.getBestScore(), rsp.getBestStars(),
                challengeManager.get(challengeUid) == null);
        assertEquals(0, rsp.getRetcode());
        assertTrue(rsp.getIsNewRecord());
        assertEquals(5000, rsp.getBestScore());
        assertEquals(0b11, rsp.getBestStars());
        assertNull(challengeManager.get(challengeUid));
        verify(historyRepository).upsertBestResult(PLAYER_ID, CHALLENGE_ID, GROUP_ID, 0b11, 5000);
    }

    /**
     * 验证点：胜利上报应合并历史星级与分数。
     * <p>测试方法 {@code reportResultWinShouldMergeHistory}：
     * <ul>
     *   <li>{@code when(historyRepository.findByPlayerAndChallenge(PLAYER_ID, CHALLENGE_ID))}</li>
     *   <li>{@code assertEquals(0, rsp.getRetcode());}</li>
     *   <li>{@code assertTrue(rsp.getIsNewRecord());}</li>
     *   <li>{@code assertEquals(0b11, rsp.getBestStars());}</li>
     *   <li>{@code assertEquals(4500, rsp.getBestScore());}</li>
     *   <li>{@code verify(historyRepository).upsertBestResult(PLAYER_ID, CHALLENGE_ID, GROUP_ID, 0b11, 4500);}</li>
     * </ul>
     */
    @Test
    @DisplayName("胜利上报应合并历史星级与分数")
    void reportResultWinShouldMergeHistory() throws Exception {
        long challengeUid = registerChallenge();
        when(historyRepository.findByPlayerAndChallenge(PLAYER_ID, CHALLENGE_ID))
                .thenReturn(Optional.of(ChallengeTestFixtures.historyEntity(CHALLENGE_ID, GROUP_ID, 0b01, 4000)));

        ChallengeSystemProto.ReportChallengeResultScRsp rsp = service.handleReportResult(
                ChallengeSystemProto.ReportChallengeResultCsReq.newBuilder()
                        .setChallengeUid(challengeUid)
                        .setIsWin(true)
                        .setFinalScore(4500)
                        .setFinalStars(0b10)
                        .setRoundsUsed(5)
                        .build(),
                loggedInChannel(PLAYER_UID));

        log.info("历史合并上报校验: challengeUid={}, oldStars=1, oldScore=4000, finalStars=2, finalScore=4500, isNewRecord={}, bestStars={}, bestScore={}",
                challengeUid, rsp.getIsNewRecord(), rsp.getBestStars(), rsp.getBestScore());
        assertEquals(0, rsp.getRetcode());
        assertTrue(rsp.getIsNewRecord());
        assertEquals(0b11, rsp.getBestStars());
        assertEquals(4500, rsp.getBestScore());
        verify(historyRepository).upsertBestResult(PLAYER_ID, CHALLENGE_ID, GROUP_ID, 0b11, 4500);
    }

    /**
     * 验证点：失败上报不应写库但仍移除运行时。
     * <p>测试方法 {@code reportResultLossShouldSkipUpsert}：
     * <ul>
     *   <li>{@code assertEquals(0, rsp.getRetcode());}</li>
     *   <li>{@code assertFalse(rsp.getIsNewRecord());}</li>
     *   <li>{@code assertEquals(800, rsp.getBestScore());}</li>
     *   <li>{@code assertNull(challengeManager.get(challengeUid));}</li>
     *   <li>{@code verify(historyRepository, never()).upsertBestResult(anyInt(), anyInt(), anyInt(), anyInt(), anyInt());}</li>
     * </ul>
     */
    @Test
    @DisplayName("失败上报不应写库但仍移除运行时")
    void reportResultLossShouldSkipUpsert() throws Exception {
        long challengeUid = registerChallenge();

        ChallengeSystemProto.ReportChallengeResultScRsp rsp = service.handleReportResult(
                ChallengeSystemProto.ReportChallengeResultCsReq.newBuilder()
                        .setChallengeUid(challengeUid)
                        .setIsWin(false)
                        .setFinalScore(800)
                        .setFinalStars(0)
                        .setRoundsUsed(4)
                        .build(),
                loggedInChannel(PLAYER_UID));

        log.info("失败上报校验: challengeUid={}, retcode={}, isNewRecord={}, bestScore={}, runtimeRemoved={}",
                challengeUid, rsp.getRetcode(), rsp.getIsNewRecord(), rsp.getBestScore(),
                challengeManager.get(challengeUid) == null);
        assertEquals(0, rsp.getRetcode());
        assertFalse(rsp.getIsNewRecord());
        assertEquals(800, rsp.getBestScore());
        assertNull(challengeManager.get(challengeUid));
        verify(historyRepository, never()).upsertBestResult(anyInt(), anyInt(), anyInt(), anyInt(), anyInt());
    }

    /**
     * 验证点：写库失败上报应返回 retcode=3。
     * <p>测试方法 {@code reportResultUpsertFailedShouldReturnRetcode3}：
     * <ul>
     *   <li>{@code when(historyRepository.findByPlayerAndChallenge(PLAYER_ID, CHALLENGE_ID)).thenReturn(Optional.empty());}</li>
     *   <li>{@code .when(historyRepository).upsertBestResult(anyInt(), anyInt(), anyInt(), anyInt(), anyInt());}</li>
     *   <li>{@code assertEquals(3, rsp.getRetcode());}</li>
     * </ul>
     */
    @Test
    @DisplayName("写库失败上报应返回 retcode=3")
    void reportResultUpsertFailedShouldReturnRetcode3() throws Exception {
        long challengeUid = registerChallenge();
        when(historyRepository.findByPlayerAndChallenge(PLAYER_ID, CHALLENGE_ID)).thenReturn(Optional.empty());
        doThrow(new RuntimeException("db down"))
                .when(historyRepository).upsertBestResult(anyInt(), anyInt(), anyInt(), anyInt(), anyInt());

        ChallengeSystemProto.ReportChallengeResultScRsp rsp = service.handleReportResult(
                ChallengeSystemProto.ReportChallengeResultCsReq.newBuilder()
                        .setChallengeUid(challengeUid)
                        .setIsWin(true)
                        .setFinalScore(3000)
                        .setFinalStars(1)
                        .setRoundsUsed(3)
                        .build(),
                loggedInChannel(PLAYER_UID));

        log.info("写库失败上报校验: challengeUid={}, retcode={}, bestScore={}", challengeUid, rsp.getRetcode(), rsp.getBestScore());
        assertEquals(3, rsp.getRetcode());
    }

    /**
     * 验证点：领取组奖励成功应更新掩码。
     * <p>测试方法 {@code claimGroupRewardSuccessShouldUpdateMask}：
     * <ul>
     *   <li>{@code when(historyRepository.listAllInGroup(PLAYER_ID, groupId))}</li>
     *   <li>{@code when(groupRewardRepository.find(PLAYER_ID, groupId))}</li>
     *   <li>{@code assertEquals(0, rsp.getRetcode());}</li>
     *   <li>{@code assertEquals(0b11, rsp.getUpdatedTakenStars());}</li>
     *   <li>{@code verify(groupRewardRepository).updateTakenStars(PLAYER_ID, groupId, 0b11);}</li>
     * </ul>
     */
    @Test
    @DisplayName("领取组奖励成功应更新掩码")
    void claimGroupRewardSuccessShouldUpdateMask() throws Exception {
        int groupId = 100;
        int starCount = 2;
        when(historyRepository.listAllInGroup(PLAYER_ID, groupId))
                .thenReturn(List.of(ChallengeTestFixtures.historyEntity(1500, groupId, 0b11, 1000)));
        when(groupRewardRepository.find(PLAYER_ID, groupId))
                .thenReturn(Optional.of(ChallengeTestFixtures.groupRewardEntity(groupId, 0)));

        ChallengeSystemProto.ClaimChallengeGroupRewardScRsp rsp = service.handleClaimGroupReward(
                ChallengeSystemProto.ClaimChallengeGroupRewardCsReq.newBuilder()
                        .setGroupId(groupId)
                        .setStarCount(starCount)
                        .build(),
                loggedInChannel(PLAYER_UID));

        log.info("组奖励领取校验: groupId={}, starCount={}, retcode={}, availableStars=2, updatedTakenStars={}",
                groupId, starCount, rsp.getRetcode(), rsp.getUpdatedTakenStars());
        assertEquals(0, rsp.getRetcode());
        assertEquals(0b11, rsp.getUpdatedTakenStars());
        verify(groupRewardRepository).updateTakenStars(PLAYER_ID, groupId, 0b11);
    }

    /**
     * 验证点：可用星不足领取应返回 retcode=3。
     * <p>测试方法 {@code claimGroupRewardInsufficientStarsShouldFail}：
     * <ul>
     *   <li>{@code when(historyRepository.listAllInGroup(PLAYER_ID, groupId))}</li>
     *   <li>{@code assertEquals(3, rsp.getRetcode());}</li>
     *   <li>{@code assertEquals(0, rsp.getUpdatedTakenStars());}</li>
     * </ul>
     */
    @Test
    @DisplayName("可用星不足领取应返回 retcode=3")
    void claimGroupRewardInsufficientStarsShouldFail() throws Exception {
        int groupId = 100;
        when(historyRepository.listAllInGroup(PLAYER_ID, groupId))
                .thenReturn(List.of(ChallengeTestFixtures.historyEntity(1500, groupId, 0b01, 1000)));

        ChallengeSystemProto.ClaimChallengeGroupRewardScRsp rsp = service.handleClaimGroupReward(
                ChallengeSystemProto.ClaimChallengeGroupRewardCsReq.newBuilder()
                        .setGroupId(groupId)
                        .setStarCount(3)
                        .build(),
                loggedInChannel(PLAYER_UID));

        log.info("星数不足领取校验: groupId={}, starCount=3, availableStars=1, retcode={}, updatedTakenStars={}",
                groupId, rsp.getRetcode(), rsp.getUpdatedTakenStars());
        assertEquals(3, rsp.getRetcode());
        assertEquals(0, rsp.getUpdatedTakenStars());
    }

    /**
     * 验证点：重复领取组奖励应返回 retcode=4。
     * <p>测试方法 {@code claimGroupRewardAlreadyTakenShouldFail}：
     * <ul>
     *   <li>{@code when(historyRepository.listAllInGroup(PLAYER_ID, groupId))}</li>
     *   <li>{@code when(groupRewardRepository.find(PLAYER_ID, groupId))}</li>
     *   <li>{@code assertEquals(4, rsp.getRetcode());}</li>
     *   <li>{@code assertEquals(0b11, rsp.getUpdatedTakenStars());}</li>
     * </ul>
     */
    @Test
    @DisplayName("重复领取组奖励应返回 retcode=4")
    void claimGroupRewardAlreadyTakenShouldFail() throws Exception {
        int groupId = 100;
        int starCount = 2;
        when(historyRepository.listAllInGroup(PLAYER_ID, groupId))
                .thenReturn(List.of(ChallengeTestFixtures.historyEntity(1500, groupId, 0b11, 1000)));
        when(groupRewardRepository.find(PLAYER_ID, groupId))
                .thenReturn(Optional.of(ChallengeTestFixtures.groupRewardEntity(groupId, 0b11)));

        ChallengeSystemProto.ClaimChallengeGroupRewardScRsp rsp = service.handleClaimGroupReward(
                ChallengeSystemProto.ClaimChallengeGroupRewardCsReq.newBuilder()
                        .setGroupId(groupId)
                        .setStarCount(starCount)
                        .build(),
                loggedInChannel(PLAYER_UID));

        log.info("重复领取校验: groupId={}, starCount={}, takenMask=3, retcode={}, updatedTakenStars={}",
                groupId, starCount, rsp.getRetcode(), rsp.getUpdatedTakenStars());
        assertEquals(4, rsp.getRetcode());
        assertEquals(0b11, rsp.getUpdatedTakenStars());
    }

    /**
     * 验证点：查询挑战历史应返回分页结果。
     * <p>测试方法 {@code getHistoryShouldReturnPagedList}：
     * <ul>
     *   <li>{@code when(historyRepository.countHistory(PLAYER_ID, 0, 0)).thenReturn(1);}</li>
     *   <li>{@code when(historyRepository.listHistory(PLAYER_ID, 0, 0, 0, 20)).thenReturn(List.of(row));}</li>
     *   <li>{@code assertEquals(0, rsp.getRetcode());}</li>
     *   <li>{@code assertEquals(1, rsp.getTotalCount());}</li>
     *   <li>{@code assertEquals(1, rsp.getHistoriesCount());}</li>
     *   <li>{@code assertEquals(CHALLENGE_ID, rsp.getHistories(0).getChallengeId());}</li>
     * </ul>
     */
    @Test
    @DisplayName("查询挑战历史应返回分页结果")
    void getHistoryShouldReturnPagedList() throws Exception {
        ChallengeHistoryEntity row = ChallengeTestFixtures.historyEntity(CHALLENGE_ID, GROUP_ID, 0b11, 9000);
        when(historyRepository.countHistory(PLAYER_ID, 0, 0)).thenReturn(1);
        when(historyRepository.listHistory(PLAYER_ID, 0, 0, 0, 20)).thenReturn(List.of(row));

        ChallengeSystemProto.GetChallengeHistoryScRsp rsp = service.handleGetHistory(
                ChallengeSystemProto.GetChallengeHistoryCsReq.newBuilder()
                        .setGroupId(0)
                        .setChallengeId(0)
                        .setPage(1)
                        .setPageSize(20)
                        .build(),
                loggedInChannel(PLAYER_UID));

        log.info("挑战历史查询校验: retcode={}, totalCount={}, historyCount={}, challengeId={}, stars={}, score={}",
                rsp.getRetcode(), rsp.getTotalCount(), rsp.getHistoriesCount(),
                rsp.getHistories(0).getChallengeId(), rsp.getHistories(0).getStars(), rsp.getHistories(0).getScore());
        assertEquals(0, rsp.getRetcode());
        assertEquals(1, rsp.getTotalCount());
        assertEquals(1, rsp.getHistoriesCount());
        assertEquals(CHALLENGE_ID, rsp.getHistories(0).getChallengeId());
        assertEquals(9000, rsp.getHistories(0).getScore());
    }

    /**
     * 验证点：查询组奖励状态应返回掩码与可用星。
     * <p>测试方法 {@code getGroupRewardStateShouldReturnMaskAndStars}：
     * <ul>
     *   <li>{@code when(groupRewardRepository.find(PLAYER_ID, groupId))}</li>
     *   <li>{@code when(historyRepository.listAllInGroup(PLAYER_ID, groupId))}</li>
     *   <li>{@code assertEquals(0, rsp.getRetcode());}</li>
     *   <li>{@code assertEquals(0b01, rsp.getTakenStarsMask());}</li>
     *   <li>{@code assertEquals(3, rsp.getAvailableStars());}</li>
     * </ul>
     */
    @Test
    @DisplayName("查询组奖励状态应返回掩码与可用星")
    void getGroupRewardStateShouldReturnMaskAndStars() throws Exception {
        int groupId = 100;
        when(groupRewardRepository.find(PLAYER_ID, groupId))
                .thenReturn(Optional.of(ChallengeTestFixtures.groupRewardEntity(groupId, 0b01)));
        when(historyRepository.listAllInGroup(PLAYER_ID, groupId))
                .thenReturn(List.of(
                        ChallengeTestFixtures.historyEntity(1500, groupId, 0b11, 1000),
                        ChallengeTestFixtures.historyEntity(1600, groupId, 0b01, 800)));

        ChallengeSystemProto.GetChallengeGroupRewardScRsp rsp = service.handleGetGroupRewardState(
                ChallengeSystemProto.GetChallengeGroupRewardCsReq.newBuilder()
                        .setGroupId(groupId)
                        .build(),
                loggedInChannel(PLAYER_UID));

        log.info("组奖励状态查询校验: groupId={}, retcode={}, takenStarsMask={}, availableStars={}",
                groupId, rsp.getRetcode(), rsp.getTakenStarsMask(), rsp.getAvailableStars());
        assertEquals(0, rsp.getRetcode());
        assertEquals(0b01, rsp.getTakenStarsMask());
        assertEquals(3, rsp.getAvailableStars());
    }

    private long registerChallenge() {
        long challengeUid = challengeManager.nextUid();
        ChallengeRuntime runtime = ChallengeTestFixtures.createRuntime(challengeUid, PLAYER_ID, CHALLENGE_ID);
        challengeManager.put(runtime);
        log.info("注册测试挑战: challengeUid={}, playerId={}, challengeId={}, waveCount={}, status={}",
                challengeUid, PLAYER_ID, CHALLENGE_ID, runtime.getWaveCount(), runtime.getStatus());
        return challengeUid;
    }

    private Channel loggedInChannel(long uid) {
        Channel channel = mock(Channel.class);
        when(contextResolver.resolvePlayerId(channel)).thenReturn((int) (uid & 0xffffffffL));
        when(contextResolver.resolveUid(channel)).thenReturn(OptionalLong.of(uid));
        log.info("模拟登录 Channel: uid={}, playerId={}", uid, (int) (uid & 0xffffffffL));
        return channel;
    }
}
