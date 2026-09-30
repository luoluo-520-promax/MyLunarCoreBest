package cn.itcast.demo.mylunarcore.character;



import cn.itcast.demo.mylunarcore.assist.cultivation.AssistCultivationAdvisorService;

import cn.itcast.demo.mylunarcore.player.GameSessionManager;

import cn.itcast.demo.mylunarcore.player.PlayerContextResolver;

import cn.itcast.demo.mylunarcore.protocol.CharacterSystemProto;

import io.netty.channel.Channel;

import org.springframework.stereotype.Service;



/**

 * 养成计划协议适配（CmdId 1046–1047），含 AI 养成优先级预览。

 */

@Service

public class CharacterDevelopmentNettyService {



    private final DevelopmentPlanService developmentPlanService;

    private final PlayerContextResolver playerContextResolver;

    private final GameSessionManager sessionManager;

    private final AssistCultivationAdvisorService cultivationAdvisorService;



    public CharacterDevelopmentNettyService(DevelopmentPlanService developmentPlanService,

                                            PlayerContextResolver playerContextResolver,

                                            GameSessionManager sessionManager,

                                            AssistCultivationAdvisorService cultivationAdvisorService) {

        this.developmentPlanService = developmentPlanService;

        this.playerContextResolver = playerContextResolver;

        this.sessionManager = sessionManager;

        this.cultivationAdvisorService = cultivationAdvisorService;

    }



    public CharacterSystemProto.CalculateUpgradeMaterialsScRsp handleCalculate(

            CharacterSystemProto.CalculateUpgradeMaterialsCsReq req, Channel channel) {

        int playerId = playerContextResolver.resolvePlayerId(channel);

        if (playerId <= 0) {

            return CharacterSystemProto.CalculateUpgradeMaterialsScRsp.newBuilder().setRetcode(1).build();

        }

        DevelopmentPlanService.PlanResult r =

                developmentPlanService.calculate(playerId, req.getAvatarId(), req.getTargetLevel());

        CharacterSystemProto.CalculateUpgradeMaterialsScRsp.Builder b =

                CharacterSystemProto.CalculateUpgradeMaterialsScRsp.newBuilder()

                        .setRetcode(r.retcode())

                        .setAvatarId(r.avatarId())

                        .setCurrentLevel(r.currentLevel())

                        .setTargetLevel(r.targetLevel())

                        .setTotalStaminaCost(r.totalStaminaCost())

                        .setStaminaCurrent(r.staminaCurrent())

                        .setStaminaDeficit(r.staminaDeficit())

                        .setCanOneClickSweep(r.canOneClickSweep());

        if (r.materials() != null) {

            for (DevelopmentPlanService.MaterialNeed m : r.materials()) {

                b.addMaterials(CharacterSystemProto.UpgradeMaterialNeed.newBuilder()

                        .setItemId(m.itemId())

                        .setRequiredCount(m.required())

                        .setOwnedCount(m.owned())

                        .setDeficit(m.deficit())

                        .build());

            }

        }

        if (r.stages() != null) {

            for (DevelopmentPlanService.StageAdvice s : r.stages()) {

                b.addRecommendedStages(CharacterSystemProto.RecommendedStage.newBuilder()

                        .setStageId(s.stageId())

                        .setStageName(s.stageName() == null ? "" : s.stageName())

                        .setDropItemId(s.dropItemId())

                        .setEstimatedDrops(s.estimatedDrops())

                        .setStaminaCost(s.staminaCost())

                        .setEfficiencyBp(s.efficiencyBp())

                        .setRecommendedTimes(s.recommendedTimes())

                        .setSweepUnlocked(s.sweepUnlocked())

                        .build());

            }

        }

        var session = sessionManager.getOrNull(playerId);

        if (session != null && session.getPlayerData() != null) {

            var advice = cultivationAdvisorService.advise(session.getPlayerData(),

                    "先拉 avatarId " + req.getAvatarId());

            if (advice.present()) {

                b.setExpectedDmgIncreasePercent(advice.expectedDmgIncreasePercent())

                        .setRecommendedPriorityAvatarId(advice.recommendedAvatarId())

                        .setCultivationReason(advice.reason());

            }

        }

        return b.build();

    }



    public CharacterSystemProto.CalculateOptimalScheduleScRsp handleSchedule(

            CharacterSystemProto.CalculateOptimalScheduleCsReq req, Channel channel) {

        int playerId = playerContextResolver.resolvePlayerId(channel);

        if (playerId <= 0) {

            return CharacterSystemProto.CalculateOptimalScheduleScRsp.newBuilder().setRetcode(1).build();

        }

        DevelopmentPlanService.ScheduleResult r =

                developmentPlanService.calculateOptimalSchedule(playerId, req.getAvatarId(), req.getTargetLevel());

        CharacterSystemProto.CalculateOptimalScheduleScRsp rsp =

                CharacterSystemProto.CalculateOptimalScheduleScRsp.newBuilder()

                        .setRetcode(r.retcode())

                        .setAvatarId(r.avatarId())

                        .setTargetLevel(r.targetLevel())

                        .setTodayProgressBp(r.todayProgressBp())

                        .setNaturalReadyAtMs(r.naturalReadyAtMs())

                        .setStaminaAvailableToday(r.staminaAvailableToday())

                        .setStaminaNeeded(r.staminaNeeded())

                        .setReserveStamina(r.reserveStamina())

                        .setDailyBuyRemaining(r.dailyBuyRemaining())

                        .setSummary(r.summary() == null ? "" : r.summary())

                        .build();

        if (r.ok() && channel != null && channel.isActive()) {

            CharacterSystemProto.DevelopmentScheduleScNotify notify =

                    CharacterSystemProto.DevelopmentScheduleScNotify.newBuilder()

                            .setAvatarId(r.avatarId())

                            .setTodayProgressBp(r.todayProgressBp())

                            .setNaturalReadyAtMs(r.naturalReadyAtMs())

                            .setSummary(r.summary() == null ? "" : r.summary())

                            .build();

            channel.writeAndFlush(new cn.itcast.demo.mylunarcore.net.GamePacket(

                    cn.itcast.demo.mylunarcore.net.CmdIds.DEVELOPMENT_SCHEDULE_SC_NOTIFY, notify.toByteArray()));

        }

        return rsp;

    }

}


