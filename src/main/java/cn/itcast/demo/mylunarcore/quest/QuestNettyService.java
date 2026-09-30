// 任务系统 Netty 协议适配层：负责将客户端 protobuf 请求转换为领域服务调用，并将结果封装为 protobuf 响应
package cn.itcast.demo.mylunarcore.quest;

import cn.itcast.demo.mylunarcore.model.QuestProgressEntity;
import cn.itcast.demo.mylunarcore.player.PlayerContextResolver;
import cn.itcast.demo.mylunarcore.protocol.QuestSystemProto;
import io.netty.channel.Channel;
import org.springframework.stereotype.Service;

/**
 * 任务系统 Netty 协议适配器。
 * <p>
 * 本类不直接操作数据库，仅负责协议层校验、参数提取、调用应用服务、组装响应。
 * 通用 retcode 约定：1 = 未登录；各接口另有 2/3/4/5 等业务错误码，由应用层透传。
 * </p>
 */
@Service
public class QuestNettyService {

    private final QuestProgressApplicationService questProgressApplicationService;
    private final QuestConfigRepository questConfigRepository;
    private final PlayerContextResolver contextResolver;

    public QuestNettyService(QuestProgressApplicationService questProgressApplicationService,
                             QuestConfigRepository questConfigRepository,
                             PlayerContextResolver contextResolver) {
        this.questProgressApplicationService = questProgressApplicationService;
        this.questConfigRepository = questConfigRepository;
        this.contextResolver = contextResolver;
    }

    public QuestSystemProto.GetQuestListScRsp handleGetQuestList(
            QuestSystemProto.GetQuestListCsReq req, Channel channel) {
        int playerId = contextResolver.resolvePlayerId(channel);
        if (playerId <= 0) {
            return QuestSystemProto.GetQuestListScRsp.newBuilder().setRetcode(1).build();
        }
        var quests = questProgressApplicationService.list(playerId);
        QuestSystemProto.GetQuestListScRsp.Builder builder =
                QuestSystemProto.GetQuestListScRsp.newBuilder().setRetcode(0);
        for (var q : quests) {
            builder.addQuests(toQuestInfo(q));
        }
        return builder.build();
    }

    public QuestSystemProto.AcceptQuestScRsp handleAcceptQuest(
            QuestSystemProto.AcceptQuestCsReq req, Channel channel) {
        int playerId = contextResolver.resolvePlayerId(channel);
        if (playerId <= 0) {
            return QuestSystemProto.AcceptQuestScRsp.newBuilder().setRetcode(1).build();
        }
        QuestConfigRepository.QuestConfig config = questConfigRepository.find(req.getQuestId());
        if (config == null) {
            return QuestSystemProto.AcceptQuestScRsp.newBuilder().setRetcode(2).build();
        }
        var result = questProgressApplicationService.accept(playerId, config);
        return QuestSystemProto.AcceptQuestScRsp.newBuilder()
                .setRetcode(result.success() ? 0 : result.retcode())
                .build();
    }

    public QuestSystemProto.SubmitQuestScRsp handleSubmitQuest(
            QuestSystemProto.SubmitQuestCsReq req, Channel channel) {
        int playerId = contextResolver.resolvePlayerId(channel);
        if (playerId <= 0) {
            return QuestSystemProto.SubmitQuestScRsp.newBuilder().setRetcode(1).build();
        }
        var result = questProgressApplicationService.submit(playerId, req.getQuestId());
        if (!result.success()) {
            return QuestSystemProto.SubmitQuestScRsp.newBuilder().setRetcode(result.retcode()).build();
        }
        // 在线发奖已由 PlayerAggregateService.commit 推送 DATA_CHANGE
        return QuestSystemProto.SubmitQuestScRsp.newBuilder().setRetcode(0).build();
    }

    public QuestSystemProto.AbandonQuestScRsp handleAbandonQuest(
            QuestSystemProto.AbandonQuestCsReq req, Channel channel) {
        int playerId = contextResolver.resolvePlayerId(channel);
        if (playerId <= 0) {
            return QuestSystemProto.AbandonQuestScRsp.newBuilder().setRetcode(1).build();
        }
        var result = questProgressApplicationService.abandon(playerId, req.getQuestId());
        return QuestSystemProto.AbandonQuestScRsp.newBuilder()
                .setRetcode(result.success() ? 0 : result.retcode())
                .setQuestId(req.getQuestId())
                .build();
    }

    private static QuestSystemProto.QuestInfo toQuestInfo(QuestProgressEntity entity) {
        return QuestSystemProto.QuestInfo.newBuilder()
                .setQuestId(entity.getQuestId())
                .setStatus(entity.getStatus())
                .build();
    }
}
