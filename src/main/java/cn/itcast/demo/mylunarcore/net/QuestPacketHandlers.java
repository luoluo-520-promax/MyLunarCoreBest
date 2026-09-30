// 任务系统协议处理器：将上行 cmdId 路由到 QuestNettyService
package cn.itcast.demo.mylunarcore.net;

import cn.itcast.demo.mylunarcore.protocol.QuestSystemProto;
import cn.itcast.demo.mylunarcore.quest.QuestNettyService;
import io.netty.channel.ChannelHandlerContext;
import org.springframework.stereotype.Component;

/**
 * 任务系统协议入口。
 * <p>
 * 通过 {@link PacketCmd} 绑定任务列表查询、接取、提交、放弃四个上行命令，
 * 完成 Protobuf 反序列化 → QuestNettyService 业务处理 → 响应下发的标准流程。
 */
@Component
public class QuestPacketHandlers {

    private final QuestNettyService questNettyService;

    public QuestPacketHandlers(QuestNettyService questNettyService) {
        this.questNettyService = questNettyService;
    }

    /**
     * 处理 GET_QUEST_LIST_CS_REQ：返回玩家全部任务及当前进度。
     */
    @PacketCmd(CmdIds.GET_QUEST_LIST_CS_REQ)
    public void onGetQuestList(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        QuestSystemProto.GetQuestListCsReq req = QuestSystemProto.GetQuestListCsReq.parseFrom(packet.getPayload());
        QuestSystemProto.GetQuestListScRsp rsp = questNettyService.handleGetQuestList(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.GET_QUEST_LIST_SC_RSP, rsp.toByteArray()));
    }

    /**
     * 处理 ACCEPT_QUEST_CS_REQ：接取指定 questId 的任务。
     */
    @PacketCmd(CmdIds.ACCEPT_QUEST_CS_REQ)
    public void onAcceptQuest(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        QuestSystemProto.AcceptQuestCsReq req = QuestSystemProto.AcceptQuestCsReq.parseFrom(packet.getPayload());
        QuestSystemProto.AcceptQuestScRsp rsp = questNettyService.handleAcceptQuest(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.ACCEPT_QUEST_SC_RSP, rsp.toByteArray()));
    }

    /**
     * 处理 SUBMIT_QUEST_CS_REQ：提交已完成任务并领取奖励。
     */
    @PacketCmd(CmdIds.SUBMIT_QUEST_CS_REQ)
    public void onSubmitQuest(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        QuestSystemProto.SubmitQuestCsReq req = QuestSystemProto.SubmitQuestCsReq.parseFrom(packet.getPayload());
        QuestSystemProto.SubmitQuestScRsp rsp = questNettyService.handleSubmitQuest(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.SUBMIT_QUEST_SC_RSP, rsp.toByteArray()));
    }

    /**
     * 处理 ABANDON_QUEST_CS_REQ：放弃进行中的任务。
     */
    @PacketCmd(CmdIds.ABANDON_QUEST_CS_REQ)
    public void onAbandonQuest(ChannelHandlerContext ctx, GamePacket packet) throws Exception {
        QuestSystemProto.AbandonQuestCsReq req = QuestSystemProto.AbandonQuestCsReq.parseFrom(packet.getPayload());
        QuestSystemProto.AbandonQuestScRsp rsp = questNettyService.handleAbandonQuest(req, ctx.channel());
        ctx.writeAndFlush(new GamePacket(CmdIds.ABANDON_QUEST_SC_RSP, rsp.toByteArray()));
    }
}
