// 挑战玩法 Netty 业务服务所在包
package cn.itcast.demo.mylunarcore.challenge;

import cn.itcast.demo.mylunarcore.battle.BattleContext;
import cn.itcast.demo.mylunarcore.battle.BattleManager;
import cn.itcast.demo.mylunarcore.battle.BattleSceneFactory;
import cn.itcast.demo.mylunarcore.battle.EncounterConfig;
import cn.itcast.demo.mylunarcore.economy.RewardDistributor;
import cn.itcast.demo.mylunarcore.matchmaking.ChallengeMatchCoordinator;
import cn.itcast.demo.mylunarcore.matchmaking.RoomService;
import cn.itcast.demo.mylunarcore.model.ChallengeGroupRewardEntity;
import cn.itcast.demo.mylunarcore.model.ChallengeHistoryEntity;
import cn.itcast.demo.mylunarcore.player.PlayerContextResolver;
import cn.itcast.demo.mylunarcore.protocol.BattleSystemProto;
import cn.itcast.demo.mylunarcore.protocol.ChallengeSystemProto;
import cn.itcast.demo.mylunarcore.repo.BattleMonsterWaveRepository;
import cn.itcast.demo.mylunarcore.repo.BattleRepository;
import cn.itcast.demo.mylunarcore.repo.ChallengeGroupRewardRepository;
import cn.itcast.demo.mylunarcore.repo.ChallengeHistoryRepository;
import cn.itcast.demo.mylunarcore.common.AppLogger;
import cn.itcast.demo.mylunarcore.common.LogCategory;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 挑战玩法 Netty 业务：开战、选波、结算与奖励；匹配成局时创建共享 {@link BattleContext}。
 */
@Service
public class ChallengeNettyService {

    private static final Logger log = AppLogger.logger(LogCategory.BUSINESS_CHALLENGE, ChallengeNettyService.class);

    private final ChallengeManager challengeManager;
    private final BattleMonsterWaveRepository waveRepository;
    private final ChallengeHistoryRepository historyRepository;
    private final ChallengeGroupRewardRepository groupRewardRepository;
    private final PlayerContextResolver contextResolver;
    private final ChallengeMatchCoordinator challengeMatchCoordinator;
    private final RoomService roomService;
    private final BattleManager battleManager;
    private final BattleSceneFactory battleSceneFactory;
    private final BattleRepository battleRepository;
    private final RewardDistributor rewardDistributor;

    public ChallengeNettyService(ChallengeManager challengeManager,
                                 BattleMonsterWaveRepository waveRepository,
                                 ChallengeHistoryRepository historyRepository,
                                 ChallengeGroupRewardRepository groupRewardRepository,
                                 PlayerContextResolver contextResolver,
                                 ChallengeMatchCoordinator challengeMatchCoordinator,
                                 RoomService roomService,
                                 BattleManager battleManager,
                                 BattleSceneFactory battleSceneFactory,
                                 BattleRepository battleRepository,
                                 RewardDistributor rewardDistributor) {
        this.challengeManager = challengeManager;
        this.waveRepository = waveRepository;
        this.historyRepository = historyRepository;
        this.groupRewardRepository = groupRewardRepository;
        this.contextResolver = contextResolver;
        this.challengeMatchCoordinator = challengeMatchCoordinator;
        this.roomService = roomService;
        this.battleManager = battleManager;
        this.battleSceneFactory = battleSceneFactory;
        this.battleRepository = battleRepository;
        this.rewardDistributor = rewardDistributor;
    }

    public ChallengeSystemProto.StartChallengeScRsp handleStartChallenge(ChallengeSystemProto.StartChallengeCsReq req, Channel channel) {
        Integer playerId = getPlayerId(channel);
        if (playerId == null) {
            return ChallengeSystemProto.StartChallengeScRsp.newBuilder().setRetcode(1).build();
        }
        int challengeType = (int) req.getChallengeType();
        int challengeId = (int) req.getChallengeId();
        int lineupId = (int) req.getLineupId();
        if (challengeType <= 0 || challengeId <= 0 || lineupId <= 0) {
            return ChallengeSystemProto.StartChallengeScRsp.newBuilder().setRetcode(2).build();
        }
        ChallengeMatchCoordinator.MatchGateResult gate = challengeMatchCoordinator.ensureReadyForChallenge(
                playerId, req.getUseMatchmaking(), req.getRoomId());
        if (!gate.ready()) {
            return ChallengeSystemProto.StartChallengeScRsp.newBuilder().setRetcode(gate.retcode()).build();
        }

        int groupId = deriveGroupId(challengeId);
        int stageId = deriveStageId(challengeId);
        long nowSeconds = System.currentTimeMillis() / 1000L;

        List<BattleMonsterWaveRepository.WaveConfig> waves;
        try {
            waves = waveRepository.loadWavesByStageId(stageId);
        } catch (Exception e) {
            log.warn("loadWavesByStageId failed, stageId={}", stageId, e);
            waves = Collections.emptyList();
        }

        List<BattleSystemProto.EnemyInfo> enemyInfo = buildEnemyInfoFromWaves(waves);

        List<Integer> members = new ArrayList<>();
        members.add(playerId);
        long sharedBattleId = 0L;
        if (gate.roomId() > 0) {
            RoomService.Room room = roomService.findRoom(gate.roomId());
            if (room != null) {
                members.clear();
                for (RoomService.RoomMember m : room.members()) {
                    members.add(m.playerId());
                }
                if (!members.contains(playerId)) {
                    members.add(playerId);
                }
            }
            try {
                sharedBattleId = battleRepository.insertBattle(playerId, lineupId, stageId,
                        new Timestamp(System.currentTimeMillis()));
                BattleContext battle = battleSceneFactory.createBattleScene(
                        sharedBattleId, playerId, lineupId, stageId, nowSeconds, waves);
                if (!battleManager.put(battle) && !battleManager.instancePool().isQueued(battle.getBattleId())) {
                    return ChallengeSystemProto.StartChallengeScRsp.newBuilder().setRetcode(8).build();
                }
            } catch (Exception e) {
                log.warn("create shared challenge battle failed, playerId={}, stageId={}", playerId, stageId, e);
                return ChallengeSystemProto.StartChallengeScRsp.newBuilder().setRetcode(3).build();
            }
        }

        long challengeUid = challengeManager.nextUid();
        ChallengeRuntime runtime = new ChallengeRuntime(
                challengeUid,
                playerId,
                challengeType,
                challengeId,
                groupId,
                stageId,
                nowSeconds,
                Math.max(0, waves.size()),
                enemyInfo,
                members,
                sharedBattleId
        );
        challengeManager.put(runtime);

        int roundsLeft = challengeType == 1 ? 5 : 0;
        ChallengeSystemProto.ChallengeStageInfo stageInfo = ChallengeSystemProto.ChallengeStageInfo.newBuilder()
                .setStageId(stageId)
                .setWaveCount(runtime.getWaveCount())
                .setRoundsLeft(roundsLeft)
                .build();

        return ChallengeSystemProto.StartChallengeScRsp.newBuilder()
                .setRetcode(0)
                .setChallengeUid(challengeUid)
                .setChallengeType(challengeType)
                .setChallengeId(challengeId)
                .setStageInfo(stageInfo)
                .addAllEnemyInfo(enemyInfo)
                .setStartTime(nowSeconds)
                .build();
    }

    public ChallengeSystemProto.GetChallengeInfoScRsp handleGetChallengeInfo(ChallengeSystemProto.GetChallengeInfoCsReq req,
                                                                            Channel channel) {
        Integer playerId = getPlayerId(channel);
        if (playerId == null) {
            return ChallengeSystemProto.GetChallengeInfoScRsp.newBuilder().setRetcode(1).build();
        }
        long uid = req.getChallengeUid();
        ChallengeRuntime runtime = challengeManager.get(uid);
        if (runtime == null || !runtime.isParticipant(playerId)) {
            return ChallengeSystemProto.GetChallengeInfoScRsp.newBuilder()
                    .setRetcode(2)
                    .setChallengeUid(uid)
                    .build();
        }

        return ChallengeSystemProto.GetChallengeInfoScRsp.newBuilder()
                .setRetcode(0)
                .setChallengeUid(uid)
                .setStatus(runtime.getStatus())
                .setCurrentStage(runtime.getCurrentStage())
                .setRoundsUsed(runtime.getRoundsUsed())
                .setCurrentScore(runtime.getCurrentScore())
                .setCurrentStars(runtime.getCurrentStarsMask())
                .addAllEnemyInfo(runtime.getEnemyInfo())
                .build();
    }

    public ChallengeSystemProto.ReportChallengeResultScRsp handleReportResult(ChallengeSystemProto.ReportChallengeResultCsReq req,
                                                                             Channel channel) {
        Integer playerId = getPlayerId(channel);
        if (playerId == null) {
            return ChallengeSystemProto.ReportChallengeResultScRsp.newBuilder().setRetcode(1).build();
        }

        long uid = req.getChallengeUid();
        ChallengeRuntime runtime = challengeManager.get(uid);
        if (runtime == null || !runtime.isParticipant(playerId)) {
            return ChallengeSystemProto.ReportChallengeResultScRsp.newBuilder()
                    .setRetcode(2)
                    .setChallengeUid(uid)
                    .build();
        }

        boolean win = req.getIsWin();
        int finalScore = (int) req.getFinalScore();
        int finalStars = (int) req.getFinalStars();
        int roundsUsed = (int) req.getRoundsUsed();

        // 共享战局：服务端权威胜负
        if (runtime.getSharedBattleId() > 0) {
            BattleContext battle = battleManager.get(runtime.getSharedBattleId());
            if (battle == null || battle.isEnded()) {
                return ChallengeSystemProto.ReportChallengeResultScRsp.newBuilder()
                        .setRetcode(4)
                        .setChallengeUid(uid)
                        .build();
            }
            int endStatus = battle.resolveAuthoritativeEndStatus();
            if (endStatus == 0) {
                return ChallengeSystemProto.ReportChallengeResultScRsp.newBuilder()
                        .setRetcode(4) // 战局未结束，拒绝客户端假结算
                        .setChallengeUid(uid)
                        .build();
            }
            win = endStatus == 1;
            synchronized (battle.getLock()) {
                battle.setEnded(true);
            }
        }

        runtime.markSettled(win, finalScore, finalStars, roundsUsed);
        challengeManager.remove(uid);
        if (runtime.getSharedBattleId() > 0) {
            battleManager.remove(runtime.getSharedBattleId());
        }

        boolean isNewRecord = false;
        int bestStars = finalStars;
        int bestScore = Math.max(0, finalScore);
        List<BattleSystemProto.RewardItem> rewards = new ArrayList<>();

        if (win) {
            Optional<ChallengeHistoryEntity> old = historyRepository.findByPlayerAndChallenge(playerId, runtime.getChallengeId());
            if (old.isPresent()) {
                int oldStars = old.get().getStars();
                int oldScore = old.get().getScore();
                int mergedStars = oldStars | finalStars;
                int mergedScore = Math.max(oldScore, bestScore);
                isNewRecord = mergedStars != oldStars || mergedScore != oldScore;
                bestStars = mergedStars;
                bestScore = mergedScore;
            } else {
                isNewRecord = true;
            }

            try {
                historyRepository.upsertBestResult(playerId, runtime.getChallengeId(), runtime.getGroupId(), bestStars, bestScore);
            } catch (Exception e) {
                log.warn("upsertBestResult failed, playerId={}, challengeId={}", playerId, runtime.getChallengeId(), e);
                return ChallengeSystemProto.ReportChallengeResultScRsp.newBuilder()
                        .setRetcode(3)
                        .setChallengeUid(uid)
                        .build();
            }

            List<EncounterConfig.DropEntry> drops = List.of(
                    new EncounterConfig.DropEntry(30_000 + runtime.getChallengeId() % 1000,
                            Math.max(1, Integer.bitCount(finalStars)), null, null));
            int exp = Math.max(0, finalScore / 10);
            for (int memberId : runtime.getMemberPlayerIds()) {
                List<RewardDistributor.GrantedItem> granted =
                        rewardDistributor.grantBattleRewards(memberId, drops, exp, "challenge_win");
                if (memberId == playerId) {
                    for (RewardDistributor.GrantedItem g : granted) {
                        rewards.add(BattleSystemProto.RewardItem.newBuilder()
                                .setItemId(g.itemId())
                                .setCount(g.count())
                                .build());
                    }
                }
            }
        }

        return ChallengeSystemProto.ReportChallengeResultScRsp.newBuilder()
                .setRetcode(0)
                .setChallengeUid(uid)
                .setIsNewRecord(isNewRecord)
                .setBestStars(bestStars)
                .setBestScore(bestScore)
                .addAllRewards(rewards)
                .build();
    }

    public ChallengeSystemProto.ClaimChallengeGroupRewardScRsp handleClaimGroupReward(ChallengeSystemProto.ClaimChallengeGroupRewardCsReq req,
                                                                                     Channel channel) {
        Integer playerId = getPlayerId(channel);
        if (playerId == null) {
            return ChallengeSystemProto.ClaimChallengeGroupRewardScRsp.newBuilder().setRetcode(1).build();
        }
        int groupId = (int) req.getGroupId();
        int starCount = (int) req.getStarCount();
        if (groupId <= 0 || starCount <= 0 || starCount > 31) {
            return ChallengeSystemProto.ClaimChallengeGroupRewardScRsp.newBuilder()
                    .setRetcode(2)
                    .setGroupId(groupId)
                    .setStarCount(starCount)
                    .build();
        }

        int availableStars = computeAvailableStars(playerId, groupId);
        if (availableStars < starCount) {
            return ChallengeSystemProto.ClaimChallengeGroupRewardScRsp.newBuilder()
                    .setRetcode(3)
                    .setGroupId(groupId)
                    .setStarCount(starCount)
                    .addAllRewards(Collections.emptyList())
                    .setUpdatedTakenStars(0)
                    .build();
        }

        int claimMask = (1 << starCount) - 1;
        int takenMask = groupRewardRepository.find(playerId, groupId)
                .map(ChallengeGroupRewardEntity::getTakenStars)
                .orElse(0);

        int newMask = takenMask | claimMask;
        if (newMask == takenMask) {
            return ChallengeSystemProto.ClaimChallengeGroupRewardScRsp.newBuilder()
                    .setRetcode(4)
                    .setGroupId(groupId)
                    .setStarCount(starCount)
                    .addAllRewards(Collections.emptyList())
                    .setUpdatedTakenStars(takenMask)
                    .build();
        }

        try {
            groupRewardRepository.updateTakenStars(playerId, groupId, newMask);
        } catch (Exception e) {
            log.warn("updateTakenStars failed, playerId={}, groupId={}", playerId, groupId, e);
            return ChallengeSystemProto.ClaimChallengeGroupRewardScRsp.newBuilder()
                    .setRetcode(5)
                    .setGroupId(groupId)
                    .setStarCount(starCount)
                    .build();
        }

        List<EncounterConfig.DropEntry> drops = List.of(
                new EncounterConfig.DropEntry(20_000 + starCount, starCount, null, null));
        List<RewardDistributor.GrantedItem> granted =
                rewardDistributor.grantBattleRewards(playerId, drops, 0, "challenge_group_reward");
        List<BattleSystemProto.RewardItem> rewards = new ArrayList<>();
        for (RewardDistributor.GrantedItem g : granted) {
            rewards.add(BattleSystemProto.RewardItem.newBuilder()
                    .setItemId(g.itemId())
                    .setCount(g.count())
                    .build());
        }

        return ChallengeSystemProto.ClaimChallengeGroupRewardScRsp.newBuilder()
                .setRetcode(0)
                .setGroupId(groupId)
                .setStarCount(starCount)
                .addAllRewards(rewards)
                .setUpdatedTakenStars(newMask)
                .build();
    }

    public ChallengeSystemProto.GetChallengeHistoryScRsp handleGetHistory(ChallengeSystemProto.GetChallengeHistoryCsReq req,
                                                                         Channel channel) {
        Integer playerId = getPlayerId(channel);
        if (playerId == null) {
            return ChallengeSystemProto.GetChallengeHistoryScRsp.newBuilder().setRetcode(1).build();
        }
        int groupId = (int) req.getGroupId();
        int challengeId = (int) req.getChallengeId();
        int page = req.getPage() <= 0 ? 1 : (int) req.getPage();
        int pageSize = req.getPageSize() <= 0 ? 20 : (int) req.getPageSize();
        pageSize = Math.min(100, pageSize);
        int offset = (page - 1) * pageSize;

        int total;
        List<ChallengeHistoryEntity> list;
        try {
            total = historyRepository.countHistory(playerId, groupId, challengeId);
            list = historyRepository.listHistory(playerId, groupId, challengeId, offset, pageSize);
        } catch (Exception e) {
            log.warn("getHistory failed, playerId={}, groupId={}, challengeId={}", playerId, groupId, challengeId, e);
            return ChallengeSystemProto.GetChallengeHistoryScRsp.newBuilder().setRetcode(2).build();
        }

        List<ChallengeSystemProto.ChallengeHistoryInfo> infos = new ArrayList<>();
        for (ChallengeHistoryEntity h : list) {
            long completeSeconds = h.getUpdatedAt() == null ? 0L : (h.getUpdatedAt().getTime() / 1000L);
            infos.add(ChallengeSystemProto.ChallengeHistoryInfo.newBuilder()
                    .setChallengeId(h.getChallengeId())
                    .setGroupId(h.getGroupId())
                    .setStars(h.getStars())
                    .setScore(h.getScore())
                    .setCompleteTime(completeSeconds)
                    .build());
        }

        return ChallengeSystemProto.GetChallengeHistoryScRsp.newBuilder()
                .setRetcode(0)
                .setTotalCount(total)
                .addAllHistories(infos)
                .build();
    }

    public ChallengeSystemProto.GetChallengeGroupRewardScRsp handleGetGroupRewardState(ChallengeSystemProto.GetChallengeGroupRewardCsReq req,
                                                                                      Channel channel) {
        Integer playerId = getPlayerId(channel);
        if (playerId == null) {
            return ChallengeSystemProto.GetChallengeGroupRewardScRsp.newBuilder().setRetcode(1).build();
        }
        int groupId = (int) req.getGroupId();
        if (groupId <= 0) {
            return ChallengeSystemProto.GetChallengeGroupRewardScRsp.newBuilder().setRetcode(2).setGroupId(groupId).build();
        }

        int takenMask;
        try {
            takenMask = groupRewardRepository.find(playerId, groupId).map(ChallengeGroupRewardEntity::getTakenStars).orElse(0);
        } catch (Exception e) {
            log.warn("load group reward failed, playerId={}, groupId={}", playerId, groupId, e);
            return ChallengeSystemProto.GetChallengeGroupRewardScRsp.newBuilder().setRetcode(3).setGroupId(groupId).build();
        }

        int availableStars = computeAvailableStars(playerId, groupId);
        return ChallengeSystemProto.GetChallengeGroupRewardScRsp.newBuilder()
                .setRetcode(0)
                .setGroupId(groupId)
                .setTakenStarsMask(takenMask)
                .setAvailableStars(availableStars)
                .build();
    }

    private Integer getPlayerId(Channel channel) {
        int playerId = contextResolver.resolvePlayerId(channel);
        return playerId <= 0 ? null : playerId;
    }

    private int computeAvailableStars(int playerId, int groupId) {
        try {
            List<ChallengeHistoryEntity> histories = historyRepository.listAllInGroup(playerId, groupId);
            int total = 0;
            for (ChallengeHistoryEntity h : histories) {
                total += Integer.bitCount(h.getStars());
            }
            return Math.max(0, total);
        } catch (Exception e) {
            log.warn("computeAvailableStars failed, playerId={}, groupId={}", playerId, groupId, e);
            return 0;
        }
    }

    private static int deriveGroupId(int challengeId) {
        return (challengeId / 1000) * 100;
    }

    private static int deriveStageId(int challengeId) {
        return 1000 + challengeId;
    }

    private static List<BattleSystemProto.EnemyInfo> buildEnemyInfoFromWaves(List<BattleMonsterWaveRepository.WaveConfig> waves) {
        List<BattleSystemProto.EnemyInfo> list = new ArrayList<>();
        for (int i = 0; i < waves.size(); i++) {
            list.add(BattleSystemProto.EnemyInfo.newBuilder()
                    .setWaveIndex(i + 1)
                    .build());
        }
        return list;
    }
}
